package sh.repost.client;

import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import org.junit.jupiter.api.Test;

public final class SendOperationSecurityContractTest {
    @Test
    void operationCannotBeDowncastOrExternallySettled() {
        SendOperation operation = placeholderOperation();
        assertFalse(operation instanceof CompletableFuture<?>);

        CompletableFuture<SendResult> attack = operation.toCompletableFuture();
        assertSame(attack, operation.toCompletableFuture());
        assertThrows(UnsupportedOperationException.class,
                () -> attack.obtrudeValue(fakeResult()));
        assertFailureCode(RepostErrorCode.IO, operation);
        assertFalse(attack.complete(fakeResult()));
        assertFalse(attack.completeExceptionally(new SentinelAttackException()));
        assertThrows(UnsupportedOperationException.class,
                () -> attack.obtrudeException(new SentinelAttackException()));
        assertSame(attack, attack.orTimeout(1, java.util.concurrent.TimeUnit.NANOSECONDS));
        assertSame(attack, attack.completeOnTimeout(
                fakeResult(), 1, java.util.concurrent.TimeUnit.NANOSECONDS));
        assertFailureCode(RepostErrorCode.IO, operation);
    }

    @Test
    void derivedStagesAndOutcomeAreDetached() {
        SendOperation operation = placeholderOperation();
        CompletionStage<SendResult> derived = operation.thenApply(value -> value);
        CompletableFuture<SendResult> derivedFuture = derived.toCompletableFuture();
        derivedFuture.obtrudeValue(fakeResult());
        assertFailureCode(RepostErrorCode.IO, operation);

        CompletionStage<SendOutcome> outcomeStage = operation.outcome();
        SendOutcome original = outcomeStage.toCompletableFuture().join();
        CompletableFuture<SendOutcome> outcomeAttack = outcomeStage.toCompletableFuture();
        outcomeAttack.obtrudeValue(SendOutcome.accepted(
                "op_12345678-1234-4234-9234-123456789abc", 1, "key", 200));
        assertSame(original, operation.outcome().toCompletableFuture().join());
        assertEquals(RepostErrorCode.IO, original.getErrorCode());
    }

    private static SendOperation placeholderOperation() {
        RepostRuntime runtime = RepostRuntime.createForTesting(
                ClientOptions.builder()
                        .apiKey("key")
                        .transport(request -> {
                            throw new IllegalStateException("deterministic test transport failure");
                        })
                        .build(),
                key -> null);
        sh.repost.client.descriptor.SchemaDescriptor schema =
                sh.repost.client.descriptor.SchemaDescriptor.builder(2)
                        .addModel(sh.repost.client.descriptor.ModelDescriptor.of(
                                "Payload", java.util.Collections.emptyList()))
                        .addEvent("events", "created",
                                sh.repost.client.descriptor.EventDescriptor.of(
                                        "event.created", "Payload"))
                        .build();
        SendOperation operation = runtime.sendAsync(
                schema,
                schema.getWebhooks().get("events").get("created"),
                "customer_123",
                new RepostModel() {
                    @Override public boolean __repostIsPresent(int fieldIndex) { return false; }
                    @Override public Object __repostValue(int fieldIndex) { return null; }
                },
                SendOptions.defaults());
        try {
            operation.toCompletableFuture().join();
        } catch (java.util.concurrent.CompletionException expectedPlaceholderFailure) {
            // The security assertions below require the placeholder IO settlement to win close.
        }
        runtime.close();
        return operation;
    }

    private static SendResult fakeResult() {
        return SendResult.builder()
                .id("msg_fake")
                .type("fake")
                .customerId("fake")
                .timestamp(java.time.Instant.EPOCH)
                .build();
    }

    private static void assertFailureCode(RepostErrorCode code, SendOperation operation) {
        try {
            operation.toCompletableFuture().join();
            throw new AssertionError("expected failure");
        } catch (java.util.concurrent.CompletionException exception) {
            assertEquals(code,
                    ((sh.repost.client.error.RepostException) exception.getCause()).getErrorCode());
        }
    }

    private static void assertEquals(Object expected, Object actual) {
        if (!java.util.Objects.equals(expected, actual)) {
            throw new AssertionError("expected " + expected + " but got " + actual);
        }
    }

    private static void assertSame(Object expected, Object actual) {
        if (expected != actual) {
            throw new AssertionError("expected identical objects");
        }
    }

    private static void assertFalse(boolean value) {
        if (value) {
            throw new AssertionError("expected false");
        }
    }

    private static final class SentinelAttackException extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }
}
