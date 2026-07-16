package sh.repost.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

final class DnsIsolationContractTest {
    private static final Duration TEST_TIMEOUT = Duration.ofSeconds(2);

    @Test
    void snapshotsDeduplicatesAndBoundsProviderResults() throws Exception {
        InetAddress first = address(1);
        InetAddress second = address(2);
        ArrayList<InetAddress> providerResult = new ArrayList<>(
                Arrays.asList(first, first, second));
        AtomicInteger calls = new AtomicInteger();
        MutableClock clock = new MutableClock();

        try (TestTimer timer = timer();
                DnsIsolation isolation = new DnsIsolation(host -> {
                    calls.incrementAndGet();
                    return providerResult;
                }, 2, clock)) {
            List<InetAddress> resolved = isolation
                    .resolve("origin.example", TEST_TIMEOUT, timer)
                    .get(2, TimeUnit.SECONDS);
            providerResult.clear();

            assertEquals(Arrays.asList(first, second), resolved);
            assertThrows(UnsupportedOperationException.class, () -> resolved.add(address(3)));
            assertEquals(1, calls.get());
        }

        ArrayList<InetAddress> tooMany = new ArrayList<>();
        for (int index = 0; index < 65; index++) {
            tooMany.add(address(index + 1));
        }
        assertProviderFailure(tooMany);
        assertProviderFailure(Collections.emptyList());
        assertProviderFailure(Arrays.asList(address(1), null));
    }

    @Test
    void coalescesPerHostAndCachesFromMonotonicCompletionTime() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        MutableClock clock = new MutableClock();

        try (TestTimer timer = timer();
                DnsIsolation isolation = new DnsIsolation(host -> {
                    calls.incrementAndGet();
                    entered.countDown();
                    await(release);
                    return Collections.singletonList(address(7));
                }, 4, clock)) {
            CompletableFuture<List<InetAddress>> first = isolation.resolve(
                    "origin.example", TEST_TIMEOUT, timer);
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            CompletableFuture<List<InetAddress>> second = isolation.resolve(
                    "origin.example", TEST_TIMEOUT, timer);
            release.countDown();

            assertEquals(first.get(2, TimeUnit.SECONDS), second.get(2, TimeUnit.SECONDS));
            assertEquals(1, calls.get());

            clock.set(Duration.ofSeconds(59).toNanos());
            isolation.resolve("origin.example", TEST_TIMEOUT, timer).get(2, TimeUnit.SECONDS);
            assertEquals(1, calls.get());

            clock.set(Duration.ofSeconds(60).toNanos());
            isolation.resolve("origin.example", TEST_TIMEOUT, timer).get(2, TimeUnit.SECONDS);
            assertEquals(2, calls.get());
        }
    }

    @Test
    void cachesOnlyUnknownHostFailuresForTenSeconds() throws Exception {
        AtomicInteger unknownCalls = new AtomicInteger();
        MutableClock clock = new MutableClock();
        try (TestTimer timer = timer();
                DnsIsolation isolation = new DnsIsolation(host -> {
                    unknownCalls.incrementAndGet();
                    throw new UnknownHostException("remote-secret-must-not-escape");
                }, 2, clock)) {
            assertKind(DnsLookupFailure.Kind.NOT_FOUND,
                    isolation.resolve("missing.example", TEST_TIMEOUT, timer));
            assertKind(DnsLookupFailure.Kind.NOT_FOUND,
                    isolation.resolve("missing.example", TEST_TIMEOUT, timer));
            assertEquals(1, unknownCalls.get());

            clock.set(Duration.ofSeconds(10).toNanos());
            assertKind(DnsLookupFailure.Kind.NOT_FOUND,
                    isolation.resolve("missing.example", TEST_TIMEOUT, timer));
            assertEquals(2, unknownCalls.get());
        }

        AtomicInteger arbitraryCalls = new AtomicInteger();
        try (TestTimer timer = timer();
                DnsIsolation isolation = new DnsIsolation(host -> {
                    arbitraryCalls.incrementAndGet();
                    throw new IllegalStateException("provider-secret");
                }, 2, clock)) {
            assertKind(DnsLookupFailure.Kind.PROVIDER,
                    isolation.resolve("broken.example", TEST_TIMEOUT, timer));
            assertKind(DnsLookupFailure.Kind.PROVIDER,
                    isolation.resolve("broken.example", TEST_TIMEOUT, timer));
            assertEquals(2, arbitraryCalls.get());
        }
    }

    @Test
    void runsBorrowedProviderOnlyOnBoundedOwnedDnsThreads() throws Exception {
        AtomicReference<Thread> providerThread = new AtomicReference<>();
        try (TestTimer timer = timer();
                DnsIsolation isolation = new DnsIsolation(host -> {
                    providerThread.set(Thread.currentThread());
                    return Collections.singletonList(address(9));
                }, 8, System::nanoTime)) {
            isolation.resolve("origin.example", TEST_TIMEOUT, timer).get(2, TimeUnit.SECONDS);
        }

        Thread observed = providerThread.get();
        assertTrue(observed.getName().startsWith("repost-dns-"));
        assertTrue(observed.isDaemon());
        assertNull(observed.getContextClassLoader());
        assertFalse(Thread.currentThread() == observed);
    }

    @Test
    void neverClosesTheBorrowedResolver() throws Exception {
        BorrowedResolver resolver = new BorrowedResolver();
        try (TestTimer timer = timer();
                DnsIsolation isolation = new DnsIsolation(resolver, 2, System::nanoTime)) {
            isolation.resolve("origin.example", TEST_TIMEOUT, timer).get(2, TimeUnit.SECONDS);
        }
        assertEquals(1, resolver.resolveCalls.get());
        assertEquals(0, resolver.closeCalls.get());
    }

    @Test
    void distinguishesMonotonicClockFailureFromResolverFailure() throws Exception {
        try (TestTimer timer = timer();
                DnsIsolation isolation = new DnsIsolation(
                        host -> Collections.singletonList(address(14)),
                        2,
                        () -> {
                            throw new IllegalStateException("clock-secret");
                        })) {
            assertKind(DnsLookupFailure.Kind.CLOCK,
                    isolation.resolve("origin.example", TEST_TIMEOUT, timer));
        }
    }

    @Test
    void rejectsSaturationWithoutRunningOrCachingRejectedLookup() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch firstEntered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (TestTimer timer = timer();
                DnsIsolation isolation = new DnsIsolation(host -> {
                    calls.incrementAndGet();
                    if (host.equals("first.example")) {
                        firstEntered.countDown();
                        await(release);
                    }
                    return Collections.singletonList(address(10));
                }, 1, System::nanoTime)) {
            CompletableFuture<List<InetAddress>> first = isolation.resolve(
                    "first.example", TEST_TIMEOUT, timer);
            assertTrue(firstEntered.await(2, TimeUnit.SECONDS));
            assertKind(DnsLookupFailure.Kind.OVERLOADED,
                    isolation.resolve("second.example", TEST_TIMEOUT, timer));
            assertEquals(1, calls.get());

            release.countDown();
            first.get(2, TimeUnit.SECONDS);
            isolation.resolve("second.example", TEST_TIMEOUT, timer).get(2, TimeUnit.SECONDS);
            assertEquals(2, calls.get());
        }
    }

    @Test
    void timeoutCancellationAndCloseRejectLateResultsWithoutCaching() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch closeEntered = new CountDownLatch(1);
        CountDownLatch closeRelease = new CountDownLatch(1);
        DnsIsolation isolation = new DnsIsolation(host -> {
            calls.incrementAndGet();
            if (host.equals("close.example")) {
                closeEntered.countDown();
                await(closeRelease);
            } else {
                entered.countDown();
                await(release);
            }
            return Collections.singletonList(address(11));
        }, 2, System::nanoTime);
        try (TestTimer timer = timer()) {
            CompletableFuture<List<InetAddress>> timed = isolation.resolve(
                    "timeout.example", TEST_TIMEOUT, timer);
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            timer.runNext();
            assertKind(DnsLookupFailure.Kind.TIMEOUT, timed);
            release.countDown();

            CountDownLatch cancelEntered = new CountDownLatch(1);
            CountDownLatch cancelRelease = new CountDownLatch(1);
            DnsIsolation cancellationIsolation = new DnsIsolation(host -> {
                calls.incrementAndGet();
                cancelEntered.countDown();
                await(cancelRelease);
                return Collections.singletonList(address(12));
            }, 2, System::nanoTime);
            try {
                CompletableFuture<List<InetAddress>> cancelled = cancellationIsolation.resolve(
                        "cancel.example", TEST_TIMEOUT, timer);
                assertTrue(cancelEntered.await(2, TimeUnit.SECONDS));
                assertTrue(cancelled.cancel(false));
                cancelRelease.countDown();
                assertTrue(cancelled.isCancelled());
            } finally {
                cancellationIsolation.close();
            }

            CompletableFuture<List<InetAddress>> closing = isolation.resolve(
                    "close.example", TEST_TIMEOUT, timer);
            assertTrue(closeEntered.await(2, TimeUnit.SECONDS));
            isolation.close();
            closeRelease.countDown();
            assertKind(DnsLookupFailure.Kind.CLOSED, closing);
            assertKind(DnsLookupFailure.Kind.CLOSED,
                    isolation.resolve("after-close.example", TEST_TIMEOUT, timer));
        } finally {
            release.countDown();
            closeRelease.countDown();
            isolation.close();
        }
    }

    private static void assertProviderFailure(List<InetAddress> values) throws Exception {
        try (TestTimer timer = timer();
                DnsIsolation isolation = new DnsIsolation(host -> values, 2, System::nanoTime)) {
            assertKind(DnsLookupFailure.Kind.PROVIDER,
                    isolation.resolve("invalid.example", TEST_TIMEOUT, timer));
        }
    }

    private static void assertKind(
            DnsLookupFailure.Kind expected,
            CompletableFuture<List<InetAddress>> future) throws Exception {
        ExecutionException failure = assertThrows(
                ExecutionException.class,
                () -> future.get(2, TimeUnit.SECONDS));
        assertTrue(failure.getCause() instanceof DnsLookupFailure);
        assertEquals(expected, ((DnsLookupFailure) failure.getCause()).kind());
        assertEquals("DnsLookupFailure[REDACTED]", failure.getCause().toString());
    }

    private static InetAddress address(int suffix) throws UnknownHostException {
        return InetAddress.getByAddress(new byte[] {
                127, 0, (byte) ((suffix >>> 8) & 0xff), (byte) (suffix & 0xff)});
    }

    private static TestTimer timer() {
        TestTimer timer = new TestTimer();
        timer.setRemoveOnCancelPolicy(true);
        return timer;
    }

    private static void await(CountDownLatch latch) {
        boolean interrupted = false;
        while (true) {
            try {
                latch.await();
                break;
            } catch (InterruptedException failure) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static final class MutableClock implements MonotonicClock {
        private final AtomicLong now = new AtomicLong();

        @Override
        public long nanoTime() {
            return now.get();
        }

        private void set(long value) {
            now.set(value);
        }
    }

    private static final class TestTimer extends ScheduledThreadPoolExecutor
            implements AutoCloseable {
        private TestTimer() {
            super(1);
        }

        @Override
        public void close() {
            shutdownNow();
        }

        private void runNext() {
            getQueue().iterator().next().run();
        }
    }

    private static final class BorrowedResolver implements DnsResolver, AutoCloseable {
        private final AtomicInteger resolveCalls = new AtomicInteger();
        private final AtomicInteger closeCalls = new AtomicInteger();

        @Override
        public List<InetAddress> resolve(String asciiHost) throws UnknownHostException {
            resolveCalls.incrementAndGet();
            return Collections.singletonList(address(13));
        }

        @Override
        public void close() {
            closeCalls.incrementAndGet();
        }
    }
}
