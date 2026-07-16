package com.repost.fixture;

import com.acme.orders.LineItem;
import com.acme.orders.Order;
import com.acme.orders.OrderStatus;
import com.acme.orders.OrdersClient;
import java.time.Instant;
import java.util.List;
import sh.repost.client.ClientOptions;
import sh.repost.client.DefaultValueGenerators;
import sh.repost.client.SendResult;
import sh.repost.client.test.StubTransport;

public final class CoreNativeSmoke {
    private static final String TIMESTAMP = "2026-01-01T00:00:00.000Z";

    private CoreNativeSmoke() { }

    public static void main(String[] args) {
        StubTransport transport = new StubTransport().enqueueResponse(202, acceptedResponse());
        ClientOptions options = ClientOptions.builder()
                .apiKey("key_test")
                .transport(transport)
                .maxAttempts(1)
                .defaultValueGenerators(DefaultValueGenerators.fixed(
                        Instant.parse(TIMESTAMP),
                        "00000000-0000-4000-8000-000000000001",
                        "csequence000000000000001"))
                .idempotencyKeyGenerator(() -> "native-smoke-key")
                .build();
        Order order = Order.builder()
                .number("order-123")
                .items(List.of(LineItem.builder().sku("sku-1").build()))
                .status(OrderStatus.OPEN)
                .build();

        OrdersClient client = OrdersClient.create(options);
        try (OrdersClient ignored = client) {
            SendResult result = client.webhooks().order().created("customer_123", order);
            require("msg_native".equals(result.getId()), "unexpected send result");
            require(transport.getRequests().size() == 1, "send did not reach the transport once");
        }
        require(client.diagnostics().isClosed(), "runtime did not shut down");
        System.out.println("REPOST_CORE_NATIVE_SMOKE_OK send=pass shutdown=pass");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static String acceptedResponse() {
        return "{\"id\":\"msg_native\",\"type\":\"order.created\","
                + "\"customerId\":\"customer_123\",\"timestamp\":\"" + TIMESTAMP + "\"}";
    }
}
