package com.repost.fixture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.acme.billing.BillingClient;
import com.acme.billing.BillingClientFactory;
import com.acme.orders.LineItem;
import com.acme.orders.Order;
import com.acme.orders.OrderStatus;
import com.acme.orders.OrderWebhooks;
import com.acme.orders.OrdersClient;
import com.acme.orders.OrdersClientFactory;
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import sh.repost.client.ClientOptions;
import sh.repost.client.DefaultValueGenerators;
import sh.repost.client.DeliveryState;
import sh.repost.client.RepostErrorCode;
import sh.repost.client.RepostRuntime;
import sh.repost.client.SendResult;
import sh.repost.client.error.RepostTransportException;
import sh.repost.client.test.StubTransport;

final class GeneratedModelContractTest {
    private static final String TIMESTAMP = "2026-01-01T00:00:00.000Z";

    @Test
    void generatedModelsSnapshotListsAndJsonMapsForPresenceAwareEquality() {
        LineItem line = LineItem.builder().sku("secret-sku").build();
        ArrayList<LineItem> items = new ArrayList<>(List.of(line));
        ArrayList<String> tags = new ArrayList<>(List.of("first"));
        LinkedHashMap<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("note", "secret-note");
        metadata.put("tags", tags);

        Order first = Order.builder()
                .number("secret-number")
                .items(items)
                .metadata(metadata)
                .status(OrderStatus.OPEN)
                .build();
        Order equal = Order.builder()
                .number("secret-number")
                .items(List.of(LineItem.builder().sku("secret-sku").build()))
                .metadata(Map.of("tags", List.of("first"), "note", "secret-note"))
                .status(OrderStatus.OPEN)
                .build();

        items.clear();
        tags.add("mutated");
        metadata.put("new", "mutated");

        assertEquals(equal, first);
        assertEquals(equal.hashCode(), first.hashCode());
        assertEquals(1, first.getItems().size());
        @SuppressWarnings("unchecked")
        Map<String, Object> capturedMetadata = (Map<String, Object>) first.getMetadata();
        assertEquals(Map.of("note", "secret-note", "tags", List.of("first")), capturedMetadata);
        assertThrows(UnsupportedOperationException.class, () -> capturedMetadata.put("x", "y"));

        Order absent = first.toBuilder().metadata(null).build().toBuilder().build();
        Order omitted = Order.builder()
                .number("secret-number")
                .items(List.of(line))
                .status(OrderStatus.OPEN)
                .build();
        assertTrue(absent.hasMetadata());
        assertFalse(omitted.hasMetadata());
        assertNotEquals(absent, omitted);
        assertTrue(absent.toBuilder().build().hasMetadata());
        assertFalse(omitted.toBuilder().build().hasMetadata());

        assertEquals("Order{setFields=[number, items, metadata, status]}", first.toString());
        assertFalse(first.toString().contains("secret-number"));
        assertFalse(first.toString().contains("secret-note"));
        assertFalse(first.toString().contains("secret-sku"));
    }

    @Test
    void generatedFactoriesDeprecationAndLifecycleExecuteThroughProductionRuntime() {
        assertEquals(OrdersClient.class, OrdersClientFactory.INSTANCE.clientType());
        assertEquals(BillingClient.class, BillingClientFactory.INSTANCE.clientType());
        assertEquals(4, deprecatedLegacySendMethods());

        Order order = Order.builder()
                .number("order-123")
                .items(List.of(LineItem.builder().sku("sku-1").build()))
                .status(OrderStatus.OPEN)
                .build();

        StubTransport borrowedTransport = new StubTransport()
                .enqueueResponse(202, acceptedResponse("msg_borrowed"));
        try (RepostRuntime shared = RepostRuntime.create(options(borrowedTransport))) {
            OrdersClient orders = OrdersClientFactory.INSTANCE.create(shared);
            BillingClient billing = BillingClientFactory.INSTANCE.create(shared);
            orders.close();
            billing.close();
            assertFalse(shared.isClosed());

            SendResult result = orders.webhooks().order().created("customer_123", order);
            assertEquals("msg_borrowed", result.getId());
            assertEquals(1, borrowedTransport.getRequests().size());
        }

        StubTransport ownedTransport = new StubTransport();
        OrdersClient owned = OrdersClient.create(options(ownedTransport));
        owned.close();
        assertTrue(owned.diagnostics().isClosed());
        RepostTransportException failure = assertThrows(
                RepostTransportException.class,
                () -> owned.webhooks().order().created("customer_123", order));
        assertEquals(RepostErrorCode.CLOSED, failure.getErrorCode());
        assertEquals(DeliveryState.NOT_SENT, failure.getDeliveryState());
        assertEquals(0, ownedTransport.getRequests().size());
    }

    private static int deprecatedLegacySendMethods() {
        int count = 0;
        for (Method method : OrderWebhooks.class.getDeclaredMethods()) {
            if ((method.getName().equals("legacyCreated")
                            || method.getName().equals("legacyCreatedAsync"))
                    && method.isAnnotationPresent(Deprecated.class)) {
                count++;
            }
        }
        return count;
    }

    private static ClientOptions options(StubTransport transport) {
        return ClientOptions.builder()
                .apiKey("key_test")
                .transport(transport)
                .maxAttempts(1)
                .defaultValueGenerators(DefaultValueGenerators.fixed(
                        Instant.parse(TIMESTAMP),
                        "00000000-0000-4000-8000-000000000001",
                        "csequence000000000000001"))
                .idempotencyKeyGenerator(() -> "generated-key")
                .build();
    }

    private static String acceptedResponse(String id) {
        return "{\"id\":\"" + id + "\",\"type\":\"order.created\","
                + "\"customerId\":\"customer_123\",\"timestamp\":\"" + TIMESTAMP + "\"}";
    }
}
