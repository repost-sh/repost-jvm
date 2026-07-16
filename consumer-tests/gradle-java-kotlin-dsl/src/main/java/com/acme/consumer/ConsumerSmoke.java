package com.acme.consumer;

import com.acme.consumer.generated.Order;
import com.acme.consumer.generated.RepostClient;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import sh.repost.client.ClientOptions;
import sh.repost.client.DefaultValueGenerators;
import sh.repost.client.test.StubTransport;

public final class ConsumerSmoke {
    private ConsumerSmoke() { }

    public static void main(String[] args) {
        StubTransport transport = new StubTransport()
                .enqueueResponse(202, accepted("msg_sync"))
                .enqueueResponse(202, accepted("msg_async"));
        ClientOptions options = ClientOptions.builder().apiKey("test-key").transport(transport)
                .maxAttempts(1).defaultValueGenerators(DefaultValueGenerators.fixed(
                        Instant.EPOCH, "00000000-0000-4000-8000-000000000001",
                        "csequence000000000000001")).build();
        Order order = Order.builder().id("order-123").build();
        try (RepostClient client = RepostClient.create(options)) {
            require("msg_sync".equals(client.webhooks().order().created("customer-123", order).getId()));
            require("msg_async".equals(client.webhooks().order().createdAsync("customer-123", order)
                    .toCompletableFuture().join().getId()));
        }
        require(transport.getRequests().size() == 2);
        String payload = new String(transport.getRequests().get(0).getBodyBytes(), StandardCharsets.UTF_8);
        require(payload.contains("\"type\":\"order.created\""));
        require(payload.contains("\"id\":\"order-123\""));
    }

    private static String accepted(String id) {
        return "{\"id\":\"" + id + "\",\"type\":\"order.created\"," +
                "\"customerId\":\"customer-123\",\"timestamp\":\"1970-01-01T00:00:00.000Z\"}";
    }

    private static void require(boolean condition) {
        if (!condition) throw new AssertionError("consumer assertion failed");
    }
}
