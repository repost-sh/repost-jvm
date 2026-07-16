package sh.repost.client;

import java.time.Instant;
import org.junit.jupiter.api.Test;

public final class SendValueContractTest {
    @Test
    void validatesSendOptions() {
        assertEquals(null, SendOptions.defaults().getIdempotencyKey());
        assertEquals("order-123", SendOptions.builder().idempotencyKey("order-123").build()
                .getIdempotencyKey());
        expectThrows(IllegalArgumentException.class,
                () -> SendOptions.builder().idempotencyKey(" leading").build());
        expectThrows(IllegalArgumentException.class,
                () -> SendOptions.builder().idempotencyKey("trailing ").build());
    }

    @Test
    void buildsAcceptedImmutableResults() {
        Instant timestamp = Instant.parse("2026-07-14T12:34:56.789Z");
        SendResult result = SendResult.builder()
                .id("msg_abc-123")
                .type("book.created")
                .customerId("customer_123")
                .timestamp(timestamp)
                .build();
        assertEquals("msg_abc-123", result.getId());
        assertEquals("book.created", result.getType());
        assertEquals("customer_123", result.getCustomerId());
        assertEquals(timestamp, result.getTimestamp());
        assertEquals(DeliveryState.ACCEPTED, result.getDeliveryState());
        assertFalse(result.toString().contains("customer_123"));
    }

    @Test
    void enforcesAcceptedAndFailedOutcomeShapes() {
        SendOutcome accepted = SendOutcome.accepted(
                "op_12345678-1234-4234-9234-123456789abc",
                1,
                "order-123",
                200);
        assertTrue(accepted.isAccepted());
        assertEquals(DeliveryState.ACCEPTED, accepted.getDeliveryState());
        assertEquals(null, accepted.getErrorCode());

        SendOutcome failed = SendOutcome.failed(
                "op_12345678-1234-4234-9234-123456789abc",
                DeliveryState.POSSIBLY_SENT,
                RepostErrorCode.CONNECT,
                RepostFailureReason.CONNECT_TIMEOUT,
                RepostCauseCategory.CUSTOM_TRANSPORT,
                2,
                "order-123",
                null);
        assertFalse(failed.isAccepted());
        assertEquals(RepostErrorCode.CONNECT, failed.getErrorCode());
        assertEquals(RepostFailureReason.CONNECT_TIMEOUT, failed.getFailureReason());
        assertEquals(RepostCauseCategory.CUSTOM_TRANSPORT, failed.getCauseCategory());
        assertFalse(failed.toString().contains("order-123"));

        expectThrows(IllegalArgumentException.class, () -> SendOutcome.failed(
                null,
                DeliveryState.ACCEPTED,
                RepostErrorCode.IO,
                null,
                null,
                0,
                null,
                null));
    }

    private static void assertEquals(Object expected, Object actual) {
        if (!java.util.Objects.equals(expected, actual)) {
            throw new AssertionError("expected " + expected + " but got " + actual);
        }
    }

    private static void assertTrue(boolean value) {
        if (!value) {
            throw new AssertionError("expected true");
        }
    }

    private static void assertFalse(boolean value) {
        if (value) {
            throw new AssertionError("expected false");
        }
    }

    private static <T extends Throwable> void expectThrows(Class<T> type, Runnable action) {
        try {
            action.run();
        } catch (Throwable throwable) {
            if (type.isInstance(throwable)) {
                return;
            }
            throw new AssertionError("expected " + type.getName() + " but got " + throwable, throwable);
        }
        throw new AssertionError("expected " + type.getName());
    }
}
