package sh.repost.client.spring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import sh.repost.client.GeneratedRepostClientFactory;
import sh.repost.client.RepostRuntime;

final class GeneratedSpringGlueTest {
    @Test
    void zeroGeneratedClientsLeavesOnlyTheRuntime() {
        runWith(new Class<?>[0], context -> {
            assertEquals(1, context.getBeansOfType(RepostRuntime.class).size());
            assertTrue(context.getBeansOfType(OrdersClient.class).isEmpty());
            assertTrue(context.getBeansOfType(BillingClient.class).isEmpty());
        });
    }

    @Test
    void oneGeneratedClientUsesItsDirectFactoryReference() {
        runWith(new Class<?>[]{OrdersGeneratedAutoConfiguration.class}, context -> {
            OrdersClient client = context.getBean(OrdersClient.class);
            assertSame(context.getBean(RepostRuntime.class), client.runtime);
            assertSame(OrdersClient.class, OrdersClientFactory.INSTANCE.clientType());
        });
    }

    @Test
    void twoGeneratedClientsShareOneRuntime() {
        runWith(
                new Class<?>[]{
                    OrdersGeneratedAutoConfiguration.class,
                    BillingGeneratedAutoConfiguration.class
                },
                context -> {
                    RepostRuntime runtime = context.getBean(RepostRuntime.class);
                    assertSame(runtime, context.getBean(OrdersClient.class).runtime);
                    assertSame(runtime, context.getBean(BillingClient.class).runtime);
                });
    }

    @Test
    void customNamedGeneratedClientUsesItsCustomConcreteTypeAndFactory() {
        runWith(new Class<?>[]{CustomNamedGeneratedAutoConfiguration.class}, context -> {
            LedgerEvents custom = context.getBean(LedgerEvents.class);
            assertSame(context.getBean(RepostRuntime.class), custom.runtime);
        });
    }

    @Test
    void duplicateGeneratedGlueCannotCreateTwoBeansOfOneConcreteType() {
        runWith(
                new Class<?>[]{
                    OrdersGeneratedAutoConfiguration.class,
                    DuplicateOrdersGeneratedAutoConfiguration.class
                },
                context -> assertEquals(1, context.getBeansOfType(OrdersClient.class).size()));
    }

    @Test
    void userTypedClientSuppressesGeneratedClient() {
        OrdersClient userClient = new OrdersClient(null);
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        RepostClientAutoConfiguration.class,
                        OrdersGeneratedAutoConfiguration.class))
                .withPropertyValues("repost.client.api-key=test-key")
                .withBean("userOrders", OrdersClient.class, () -> userClient)
                .run(context -> {
                    assertEquals(1, context.getBeansOfType(OrdersClient.class).size());
                    assertSame(userClient, context.getBean(OrdersClient.class));
                });
    }

    @Test
    void generatedClientsCloseBeforeTheOwnedRuntime() {
        AtomicReference<RepostRuntime> runtime = new AtomicReference<>();
        AtomicReference<LifecycleClient> client = new AtomicReference<>();
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        RepostClientAutoConfiguration.class,
                        LifecycleGeneratedAutoConfiguration.class))
                .withPropertyValues("repost.client.api-key=test-key")
                .run(context -> {
                    runtime.set(context.getBean(RepostRuntime.class));
                    client.set(context.getBean(LifecycleClient.class));
                    assertFalse(runtime.get().isClosed());
                });
        assertTrue(client.get().closed);
        assertFalse(client.get().runtimeClosedAtClientClose);
        assertTrue(runtime.get().isClosed());
    }

    private static void runWith(
            Class<?>[] generatedConfigurations,
            java.util.function.Consumer<org.springframework.context.ConfigurableApplicationContext>
                    assertions) {
        Class<?>[] configurations = new Class<?>[generatedConfigurations.length + 1];
        configurations[0] = RepostClientAutoConfiguration.class;
        System.arraycopy(
                generatedConfigurations, 0, configurations, 1, generatedConfigurations.length);
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(configurations))
                .withPropertyValues("repost.client.api-key=test-key")
                .run(context -> assertions.accept(context));
    }
}

final class OrdersClient {
    final RepostRuntime runtime;

    OrdersClient(RepostRuntime runtime) {
        this.runtime = runtime;
    }
}

enum OrdersClientFactory implements GeneratedRepostClientFactory<OrdersClient> {
    INSTANCE;

    @Override
    public Class<OrdersClient> clientType() {
        return OrdersClient.class;
    }

    @Override
    public OrdersClient create(RepostRuntime runtime) {
        return new OrdersClient(runtime);
    }
}

final class BillingClient {
    final RepostRuntime runtime;

    BillingClient(RepostRuntime runtime) {
        this.runtime = runtime;
    }
}

enum BillingClientFactory implements GeneratedRepostClientFactory<BillingClient> {
    INSTANCE;

    @Override
    public Class<BillingClient> clientType() {
        return BillingClient.class;
    }

    @Override
    public BillingClient create(RepostRuntime runtime) {
        return new BillingClient(runtime);
    }
}

final class LedgerEvents {
    final RepostRuntime runtime;

    LedgerEvents(RepostRuntime runtime) {
        this.runtime = runtime;
    }
}

enum LedgerEventsFactory implements GeneratedRepostClientFactory<LedgerEvents> {
    INSTANCE;

    @Override
    public Class<LedgerEvents> clientType() {
        return LedgerEvents.class;
    }

    @Override
    public LedgerEvents create(RepostRuntime runtime) {
        return new LedgerEvents(runtime);
    }
}

@AutoConfiguration(after = RepostClientAutoConfiguration.class)
class OrdersGeneratedAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean(OrdersClient.class)
    OrdersClient ordersClient(RepostRuntime runtime) {
        return OrdersClientFactory.INSTANCE.create(runtime);
    }
}

@AutoConfiguration(after = RepostClientAutoConfiguration.class)
class BillingGeneratedAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean(BillingClient.class)
    BillingClient billingClient(RepostRuntime runtime) {
        return BillingClientFactory.INSTANCE.create(runtime);
    }
}

@AutoConfiguration(after = RepostClientAutoConfiguration.class)
class CustomNamedGeneratedAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean(LedgerEvents.class)
    LedgerEvents ledgerEvents(RepostRuntime runtime) {
        return LedgerEventsFactory.INSTANCE.create(runtime);
    }
}

@AutoConfiguration(after = RepostClientAutoConfiguration.class)
class DuplicateOrdersGeneratedAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean(OrdersClient.class)
    OrdersClient duplicateOrdersClient(RepostRuntime runtime) {
        return OrdersClientFactory.INSTANCE.create(runtime);
    }
}

final class LifecycleClient implements AutoCloseable {
    final RepostRuntime runtime;
    boolean closed;
    boolean runtimeClosedAtClientClose;

    LifecycleClient(RepostRuntime runtime) {
        this.runtime = runtime;
    }

    @Override
    public void close() {
        runtimeClosedAtClientClose = runtime.isClosed();
        closed = true;
    }
}

@AutoConfiguration(after = RepostClientAutoConfiguration.class)
class LifecycleGeneratedAutoConfiguration {
    @Bean(destroyMethod = "close")
    LifecycleClient lifecycleClient(RepostRuntime runtime) {
        return new LifecycleClient(runtime);
    }
}
