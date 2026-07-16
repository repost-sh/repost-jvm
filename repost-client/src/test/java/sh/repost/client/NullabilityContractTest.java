package sh.repost.client;

import java.lang.reflect.Method;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import sh.repost.client.descriptor.DefaultSpec;
import sh.repost.client.descriptor.FieldDescriptor;
import sh.repost.client.error.RepostException;

public final class NullabilityContractTest {
    @Test
    void nullMarksEveryPublicPackage() {
        assertTrue(RepostRuntime.class.getPackage().isAnnotationPresent(NullMarked.class));
        assertTrue(FieldDescriptor.class.getPackage().isAnnotationPresent(NullMarked.class));
        assertTrue(RepostException.class.getPackage().isAnnotationPresent(NullMarked.class));
        assertTrue(sh.repost.client.internal.UriValidator.class.getPackage()
                .isAnnotationPresent(NullMarked.class));
    }

    @Test
    void marksEveryNullablePublicReturnType() throws Exception {
        assertNullableReturns(RepostErrorDetails.class,
                "getFailureReason", "getCauseCategory", "getOperationId",
                "getIdempotencyKey", "getHttpStatus");
        assertNullableReturns(RepostException.class,
                "getFailureReason", "getCauseCategory", "getOperationId",
                "getIdempotencyKey", "getHttpStatus");
        assertNullableReturns(SendOutcome.class,
                "getOperationId", "getErrorCode", "getFailureReason",
                "getCauseCategory", "getIdempotencyKey", "getHttpStatus");
        assertNullableReturns(ObserverEvent.class,
                "getAttemptNumber", "getDuration", "getOutcome", "getErrorCode",
                "getDeliveryState", "getRetryDelay", "getOperationStartedAt",
                "getOperationEndedAt");
        assertNullableReturns(AttemptSummary.class, "getErrorCode");
        assertNullableReturns(TelemetryAttemptEnd.class, "getErrorCode", "getFailureReason");
        assertNullableReturns(TelemetryOperationEnd.class, "getErrorCode", "getFailureReason");
        assertNullableReturns(TransportFailure.class, "getFailureReason");
        assertNullableReturns(FieldDescriptor.class, "getDescriptorId", "getDefaultSpec");
        assertNullableReturns(DefaultSpec.class, "getLiteralValue");
        assertNullableReturns(RepostModel.class, "__repostValue", int.class);
        assertNullableReturns(ApiKeyProvider.class, "getApiKey");

        assertFalse(RepostErrorDetails.class.getMethod("getErrorCode")
                .getAnnotatedReturnType().isAnnotationPresent(Nullable.class));
        assertFalse(SendOutcome.class.getMethod("getDeliveryState")
                .getAnnotatedReturnType().isAnnotationPresent(Nullable.class));
    }

    @Test
    void marksNullablePublicParametersWithoutWeakeningRequiredTypes()
            throws Exception {
        assertNullableParameter(DefaultSpec.class.getMethod("literal", Object.class), 0);
        assertNullableParameter(
                FieldDescriptor.Builder.class.getMethod("descriptorId", String.class), 0);
        assertNullableParameter(
                FieldDescriptor.Builder.class.getMethod("defaultSpec", DefaultSpec.class), 0);
        assertFalse(ClientOptions.Builder.class.getMethod("apiKey", String.class)
                .getAnnotatedParameterTypes()[0].isAnnotationPresent(Nullable.class));
    }

    private static void assertNullableReturns(Class<?> owner, String... methods) throws Exception {
        for (String method : methods) {
            assertTrue(owner.getMethod(method).getAnnotatedReturnType()
                    .isAnnotationPresent(Nullable.class));
        }
    }

    private static void assertNullableReturns(
            Class<?> owner,
            String method,
            Class<?> parameter) throws Exception {
        assertTrue(owner.getMethod(method, parameter).getAnnotatedReturnType()
                .isAnnotationPresent(Nullable.class));
    }

    private static void assertNullableParameter(Method method, int index) {
        assertTrue(method.getAnnotatedParameterTypes()[index].isAnnotationPresent(Nullable.class));
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
}
