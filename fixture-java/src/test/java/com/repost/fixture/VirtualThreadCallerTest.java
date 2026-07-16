package com.repost.fixture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.acme.orders.LineItem;
import com.acme.orders.Order;
import com.acme.orders.OrderStatus;
import com.acme.orders.OrdersClient;
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import sh.repost.client.ClientOptions;
import sh.repost.client.DefaultValueGenerators;
import sh.repost.client.test.StubTransport;

final class VirtualThreadCallerTest {
    private static final int CALLERS = 8;
    private static final String TIMESTAMP = "2026-01-01T00:00:00.000Z";

    @Test
    void generatedClientSupportsConcurrentVirtualThreadCallers() throws Exception {
        assumeTrue(Runtime.version().feature() >= 21, "virtual threads require JDK 21+");
        Method isVirtual = Thread.class.getMethod("isVirtual");
        Method newVirtualExecutor = Executors.class.getMethod("newVirtualThreadPerTaskExecutor");

        StubTransport transport = new StubTransport();
        for (int index = 0; index < CALLERS; index++) {
            transport.enqueueResponse(202, acceptedResponse());
        }
        ClientOptions options = ClientOptions.builder()
                .apiKey("key_test")
                .transport(transport)
                .maxAttempts(1)
                .defaultValueGenerators(DefaultValueGenerators.fixed(
                        Instant.parse(TIMESTAMP),
                        "00000000-0000-4000-8000-000000000001",
                        "csequence000000000000001"))
                .idempotencyKeyGenerator(() -> "virtual-thread-key")
                .build();
        Order order = Order.builder()
                .number("order-123")
                .items(List.of(LineItem.builder().sku("sku-1").build()))
                .status(OrderStatus.OPEN)
                .build();
        OrdersClient client = OrdersClient.create(options);
        ExecutorService callers = (ExecutorService) newVirtualExecutor.invoke(null);
        try {
            List<Future<Boolean>> sends = new ArrayList<>();
            for (int index = 0; index < CALLERS; index++) {
                sends.add(callers.submit(() -> {
                    boolean virtual = (Boolean) isVirtual.invoke(Thread.currentThread());
                    assertEquals(
                            "msg_virtual",
                            client.webhooks().order().created("customer_123", order).getId());
                    return virtual;
                }));
            }
            for (Future<Boolean> send : sends) {
                assertTrue(send.get(10, TimeUnit.SECONDS));
            }
        } finally {
            callers.shutdownNow();
            assertTrue(callers.awaitTermination(10, TimeUnit.SECONDS));
            client.close();
        }

        assertTrue(client.diagnostics().isClosed());
        assertEquals(CALLERS, transport.getRequests().size());
    }

    private static String acceptedResponse() {
        return "{\"id\":\"msg_virtual\",\"type\":\"order.created\","
                + "\"customerId\":\"customer_123\",\"timestamp\":\"" + TIMESTAMP + "\"}";
    }
}
