package sh.repost.client.spring;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.ref.WeakReference;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import sh.repost.client.ClientOptions;
import sh.repost.client.RepostRuntime;

final class RepostContextLifecycleTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(RepostClientAutoConfiguration.class))
            .withPropertyValues("repost.client.api-key=test-key");

    @Test
    void twoContextsInOneJvmOwnIsolatedRuntimes() {
        AtomicReference<RepostRuntime> first = new AtomicReference<>();
        AtomicReference<RepostRuntime> second = new AtomicReference<>();
        runner.run(firstContext -> {
            first.set(firstContext.getBean(RepostRuntime.class));
            runner.run(secondContext -> {
                second.set(secondContext.getBean(RepostRuntime.class));
                assertNotSame(first.get(), second.get());
                assertFalse(first.get().isClosed());
                assertFalse(second.get().isClosed());
            });
            assertTrue(second.get().isClosed());
            assertFalse(first.get().isClosed());
        });
        assertTrue(first.get().isClosed());
    }

    @Test
    void childContextBorrowsParentRuntimeWithoutClosingIt() {
        RepostRuntime borrowed = RepostRuntime.create(ClientOptions.builder()
                .apiKey("parent-key")
                .transport(request -> CompletableFuture.failedFuture(
                        new AssertionError("transport must not run")))
                .build());
        AnnotationConfigApplicationContext parent = new AnnotationConfigApplicationContext();
        parent.getBeanFactory().registerSingleton("parentRuntime", borrowed);
        parent.refresh();
        runner.withParent(parent).run(child -> {
            assertTrue(child.getBean(RepostRuntime.class) == borrowed);
            assertFalse(borrowed.isClosed());
        });
        assertFalse(borrowed.isClosed());
        parent.close();
        borrowed.close();
    }

    @Test
    void concurrentContextStartAndCloseLeavesEveryRuntimeClosed() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(4);
        List<CompletableFuture<RepostRuntime>> contexts = new ArrayList<>();
        for (int index = 0; index < 12; index++) {
            contexts.add(CompletableFuture.supplyAsync(() -> {
                AtomicReference<RepostRuntime> runtime = new AtomicReference<>();
                runner.run(context -> runtime.set(context.getBean(RepostRuntime.class)));
                return runtime.get();
            }, executor));
        }
        for (CompletableFuture<RepostRuntime> context : contexts) {
            assertTrue(context.get(10, TimeUnit.SECONDS).isClosed());
        }
        executor.shutdownNow();
        assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS));
    }

    @Test
    void oneHundredRestartCyclesReleaseContextsRuntimesAndClassloaders() {
        List<WeakReference<?>> references = new ArrayList<>();
        for (int index = 0; index < 100; index++) {
            URLClassLoader restartLoader = new URLClassLoader(new java.net.URL[0],
                    getClass().getClassLoader());
            AtomicReference<RepostRuntime> runtime = new AtomicReference<>();
            runner.withInitializer(context -> context.setClassLoader(restartLoader))
                    .run(context -> {
                        runtime.set(context.getBean(RepostRuntime.class));
                        references.add(new WeakReference<>(context.getSourceApplicationContext()));
                    });
            assertTrue(runtime.get().isClosed());
            references.add(new WeakReference<>(runtime.get()));
            references.add(new WeakReference<>(restartLoader));
            runtime.set(null);
            try {
                restartLoader.close();
            } catch (java.io.IOException failure) {
                throw new AssertionError(failure);
            }
        }
        awaitCollection(references);
        assertTrue(references.stream().allMatch(reference -> reference.get() == null));
    }

    private static void awaitCollection(List<WeakReference<?>> references) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (references.stream().anyMatch(reference -> reference.get() != null)
                && System.nanoTime() < deadline) {
            System.gc();
            System.runFinalization();
            Thread.onSpinWait();
        }
    }
}
