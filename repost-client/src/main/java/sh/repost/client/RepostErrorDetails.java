package sh.repost.client;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/** Immutable structured failure details with no raw message or throwable channel. */
public final class RepostErrorDetails {
    /** Maximum number of issue records retained in a public failure. */
    public static final int MAX_ISSUES = 32;
    /** Saturating maximum total issue count. */
    public static final int MAX_ISSUE_COUNT = 2_147_483_647;
    /** Maximum UTF-8 bytes retained for one validation path. */
    public static final int MAX_ISSUE_PATH_UTF8_BYTES = 1_024;
    /** Maximum aggregate UTF-8 bytes retained across validation paths. */
    public static final int MAX_TOTAL_ISSUE_PATH_UTF8_BYTES = 16_384;

    private static final Pattern OPERATION_ID = Pattern.compile(
            "^op_[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$");
    private static final List<RepostCauseCategory> CLOSE_ORDER = Collections.unmodifiableList(
            Arrays.asList(
                    RepostCauseCategory.SCHEDULER_CLOSE,
                    RepostCauseCategory.TRANSPORT_CLOSE,
                    RepostCauseCategory.DNS_EXECUTOR_CLOSE,
                    RepostCauseCategory.PROXY_CREDENTIAL_EXECUTOR_CLOSE,
                    RepostCauseCategory.TLS_EXECUTOR_CLOSE,
                    RepostCauseCategory.OPERATION_EXECUTOR_CLOSE,
                    RepostCauseCategory.TERMINAL_SETTLEMENT_CLOSE,
                    RepostCauseCategory.OBSERVER_CLOSE));

    private final RepostErrorCode errorCode;
    private final RepostFailureReason failureReason;
    private final RepostCauseCategory causeCategory;
    private final DeliveryState deliveryState;
    private final String operationId;
    private final String idempotencyKey;
    private final int attemptCount;
    private final Integer httpStatus;
    private final boolean retryable;
    private final long compressedBytes;
    private final long decompressedBytes;
    private final int responseHeaderFields;
    private final long responseHeaderBytes;
    private final boolean truncated;
    private final List<RepostCauseCategory> closeFailureCategories;
    private final List<ValidationIssue> validationIssues;
    private final List<ConfigurationIssue> configurationIssues;
    private final int issueCount;
    private final boolean issuesTruncated;

    private RepostErrorDetails(Builder builder) {
        this.errorCode = builder.errorCode;
        this.deliveryState = builder.deliveryState;
        this.failureReason = builder.failureReason;
        this.causeCategory = builder.causeCategory;
        this.operationId = validateOperationId(builder.operationId);
        this.idempotencyKey = validateIdempotencyKey(builder.idempotencyKey);
        this.attemptCount = requireRange(builder.attemptCount, 0, 10, "attemptCount");
        this.httpStatus = validateHttpStatus(builder.httpStatus);
        this.retryable = builder.retryable;
        this.compressedBytes = requireNonnegative(builder.compressedBytes, "compressedBytes");
        this.decompressedBytes = requireNonnegative(builder.decompressedBytes, "decompressedBytes");
        this.responseHeaderFields = requireNonnegative(
                builder.responseHeaderFields, "responseHeaderFields");
        this.responseHeaderBytes = requireNonnegative(
                builder.responseHeaderBytes, "responseHeaderBytes");
        this.truncated = builder.truncated;
        this.closeFailureCategories = validateCloseFailures(
                errorCode, deliveryState, builder.closeFailureCategories);
        PublicFailureSemantics.validateDetails(
                errorCode,
                deliveryState,
                failureReason,
                causeCategory,
                attemptCount,
                httpStatus,
                retryable,
                closeFailureCategories.size());

        IssueLists issues = validateIssues(builder);
        this.validationIssues = issues.validationIssues;
        this.configurationIssues = issues.configurationIssues;
        this.issueCount = issues.issueCount;
        this.issuesTruncated = issues.truncated;
    }

    /**
     * Starts structured details for one stable error and delivery state.
     *
     * @param code stable error code
     * @param deliveryState best-known delivery state
     * @return details builder
     */
    public static Builder builder(RepostErrorCode code, DeliveryState deliveryState) {
        return new Builder(code, deliveryState);
    }

    /**
     * Returns the stable error code.
     *
     * @return stable error code
     */
    public RepostErrorCode getErrorCode() { return errorCode; }
    /**
     * Returns the network reason when applicable.
     *
     * @return failure reason or {@code null}
     */
    public @Nullable RepostFailureReason getFailureReason() { return failureReason; }
    /**
     * Returns the safe local cause category.
     *
     * @return cause category or {@code null}
     */
    public @Nullable RepostCauseCategory getCauseCategory() { return causeCategory; }
    /**
     * Returns the best-known delivery state.
     *
     * @return delivery state
     */
    public DeliveryState getDeliveryState() { return deliveryState; }
    /**
     * Returns the operation identifier.
     *
     * @return operation identifier or {@code null}
     */
    public @Nullable String getOperationId() { return operationId; }
    /**
     * Returns the reconciliation key.
     *
     * @return idempotency key or {@code null}
     */
    public @Nullable String getIdempotencyKey() { return idempotencyKey; }
    /**
     * Returns the number of started attempts.
     *
     * @return attempt count
     */
    public int getAttemptCount() { return attemptCount; }
    /**
     * Returns the final HTTP status.
     *
     * @return HTTP status or {@code null}
     */
    public @Nullable Integer getHttpStatus() { return httpStatus; }
    /**
     * Reports retry-policy eligibility.
     *
     * @return whether the operation is retryable
     */
    public boolean isRetryable() { return retryable; }
    /**
     * Returns observed compressed response bytes.
     *
     * @return compressed byte count
     */
    public long getCompressedBytes() { return compressedBytes; }
    /**
     * Returns observed decompressed response bytes.
     *
     * @return decompressed byte count
     */
    public long getDecompressedBytes() { return decompressedBytes; }
    /**
     * Returns observed response header fields.
     *
     * @return response header field count
     */
    public int getResponseHeaderFields() { return responseHeaderFields; }
    /**
     * Returns observed logical response header bytes.
     *
     * @return response header byte count
     */
    public long getResponseHeaderBytes() { return responseHeaderBytes; }
    /**
     * Reports bounded diagnostic truncation.
     *
     * @return whether diagnostic data was truncated
     */
    public boolean isTruncated() { return truncated; }
    /**
     * Returns the total close-failure count.
     *
     * @return close-failure count
     */
    public int getCloseFailureCount() { return closeFailureCategories.size(); }
    /**
     * Returns retained close-failure categories.
     *
     * @return immutable ordered categories
     */
    public List<RepostCauseCategory> getCloseFailureCategories() { return closeFailureCategories; }
    /**
     * Returns retained validation issues.
     *
     * @return immutable validation issues
     */
    public List<ValidationIssue> getValidationIssues() { return validationIssues; }
    /**
     * Returns retained configuration issues.
     *
     * @return immutable configuration issues
     */
    public List<ConfigurationIssue> getConfigurationIssues() { return configurationIssues; }
    /**
     * Returns total issues including truncated records.
     *
     * @return total issue count
     */
    public int getIssueCount() { return issueCount; }
    /**
     * Reports issue-list truncation.
     *
     * @return whether issue records were truncated
     */
    public boolean isIssuesTruncated() { return issuesTruncated; }

    @Override
    public String toString() {
        return "RepostErrorDetails[code=" + errorCode + ", delivery=" + deliveryState + "]";
    }

    private static String validateOperationId(String value) {
        if (value != null && !OPERATION_ID.matcher(value).matches()) {
            throw new IllegalArgumentException("operationId has invalid SDK-local grammar");
        }
        return value;
    }

    private static String validateIdempotencyKey(String value) {
        if (value == null) {
            return null;
        }
        if (value.isEmpty() || value.length() > 255
                || value.charAt(0) == ' ' || value.charAt(value.length() - 1) == ' ') {
            throw new IllegalArgumentException("idempotencyKey has invalid length or spacing");
        }
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character < 0x20 || character > 0x7e) {
                throw new IllegalArgumentException("idempotencyKey must be printable ASCII");
            }
        }
        return value;
    }

    private static Integer validateHttpStatus(Integer value) {
        if (value != null && (value < 200 || value > 599)) {
            throw new IllegalArgumentException("httpStatus must be within 200..599");
        }
        return value;
    }

    private static List<RepostCauseCategory> validateCloseFailures(
            RepostErrorCode code,
            DeliveryState delivery,
            List<RepostCauseCategory> source) {
        if (source == null || source.isEmpty()) {
            return Collections.emptyList();
        }
        if (code != RepostErrorCode.IO || delivery != DeliveryState.NOT_SENT || source.size() > 8) {
            throw new IllegalArgumentException("close failures require IO/NOT_SENT and at most eight entries");
        }
        ArrayList<RepostCauseCategory> copy = new ArrayList<>(source.size());
        int priorIndex = -1;
        for (RepostCauseCategory category : source) {
            Objects.requireNonNull(category, "closeFailureCategories contains null");
            int index = CLOSE_ORDER.indexOf(category);
            if (index < 0 || index <= priorIndex) {
                throw new IllegalArgumentException("close failures must be unique and close-attempt ordered");
            }
            priorIndex = index;
            copy.add(category);
        }
        return Collections.unmodifiableList(copy);
    }

    private static IssueLists validateIssues(Builder builder) {
        List<ValidationIssue> validation = builder.validationIssues == null
                ? Collections.emptyList() : copyValidationIssues(builder.validationIssues);
        List<ConfigurationIssue> configuration = builder.configurationIssues == null
                ? Collections.emptyList() : copyConfigurationIssues(builder.configurationIssues);
        if (!validation.isEmpty() && !configuration.isEmpty()) {
            throw new IllegalArgumentException("validation and configuration issues are mutually exclusive");
        }
        if (builder.errorCode == RepostErrorCode.VALIDATION) {
            if (validation.isEmpty() || !configuration.isEmpty()) {
                throw new IllegalArgumentException("VALIDATION requires validation issues only");
            }
        } else if (builder.errorCode == RepostErrorCode.CONFIGURATION) {
            if (configuration.isEmpty() || !validation.isEmpty()) {
                throw new IllegalArgumentException("CONFIGURATION requires configuration issues only");
            }
        } else if (!validation.isEmpty() || !configuration.isEmpty()) {
            throw new IllegalArgumentException("this error code cannot carry issues");
        }
        int retained = validation.size() + configuration.size();
        int count = builder.issueMethodCalled ? builder.issueCount : 0;
        boolean truncated = builder.issueMethodCalled && builder.issuesTruncated;
        if (count < retained || count > MAX_ISSUE_COUNT || truncated != (count != retained)) {
            throw new IllegalArgumentException("issue count/truncation is inconsistent");
        }
        if (retained == 0 && (count != 0 || truncated)) {
            throw new IllegalArgumentException("errors without issue lists must use zero issue metadata");
        }
        return new IssueLists(validation, configuration, count, truncated);
    }

    private static List<ValidationIssue> copyValidationIssues(List<ValidationIssue> source) {
        Objects.requireNonNull(source, "validationIssues");
        if (source.size() > MAX_ISSUES) {
            throw new IllegalArgumentException("too many validation issues");
        }
        ArrayList<ValidationIssue> copy = new ArrayList<>(source.size());
        Set<String> seenPaths = new HashSet<>();
        int pathBytes = 0;
        for (ValidationIssue issue : source) {
            Objects.requireNonNull(issue, "validationIssues contains null");
            if (!seenPaths.add(issue.getPath())) {
                throw new IllegalArgumentException("validation issues contain a duplicate path");
            }
            pathBytes += ValidationIssue.utf8Bytes(issue.getPath());
            if (pathBytes > MAX_TOTAL_ISSUE_PATH_UTF8_BYTES) {
                throw new IllegalArgumentException("validation issue paths exceed the aggregate bound");
            }
            copy.add(issue);
        }
        return Collections.unmodifiableList(copy);
    }

    private static List<ConfigurationIssue> copyConfigurationIssues(List<ConfigurationIssue> source) {
        Objects.requireNonNull(source, "configurationIssues");
        if (source.size() > MAX_ISSUES) {
            throw new IllegalArgumentException("too many configuration issues");
        }
        ArrayList<ConfigurationIssue> copy = new ArrayList<>(source.size());
        int previousKey = -1;
        int previousCode = -1;
        for (ConfigurationIssue issue : source) {
            Objects.requireNonNull(issue, "configurationIssues contains null");
            int key = issue.getOptionKeys().get(0).ordinal();
            int code = issue.getCode().ordinal();
            if (key < previousKey || (key == previousKey && code <= previousCode)) {
                throw new IllegalArgumentException("configuration issues are not declaration ordered");
            }
            previousKey = key;
            previousCode = code;
            copy.add(issue);
        }
        return Collections.unmodifiableList(copy);
    }

    private static int requireRange(int value, int minimum, int maximum, String name) {
        if (value < minimum || value > maximum) {
            throw new IllegalArgumentException(name + " is outside its range");
        }
        return value;
    }

    private static int requireNonnegative(int value, String name) {
        return requireRange(value, 0, Integer.MAX_VALUE, name);
    }

    private static long requireNonnegative(long value, String name) {
        if (value < 0L) {
            throw new IllegalArgumentException(name + " must not be negative");
        }
        return value;
    }

    /** Builder for immutable structured failure details. */
    public static final class Builder {
        private final RepostErrorCode errorCode;
        private final DeliveryState deliveryState;
        private RepostFailureReason failureReason;
        private RepostCauseCategory causeCategory;
        private String operationId;
        private String idempotencyKey;
        private int attemptCount;
        private Integer httpStatus;
        private boolean retryable;
        private long compressedBytes;
        private long decompressedBytes;
        private int responseHeaderFields;
        private long responseHeaderBytes;
        private boolean truncated;
        private List<RepostCauseCategory> closeFailureCategories;
        private List<ValidationIssue> validationIssues;
        private List<ConfigurationIssue> configurationIssues;
        private int issueCount;
        private boolean issuesTruncated;
        private boolean issueMethodCalled;

        private Builder(RepostErrorCode errorCode, DeliveryState deliveryState) {
            this.errorCode = Objects.requireNonNull(errorCode, "errorCode");
            this.deliveryState = Objects.requireNonNull(deliveryState, "deliveryState");
        }

        /**
         * Sets the operation identifier.
         *
         * @param value identifier or {@code null}
         * @return this builder
         */
        public Builder operationId(@Nullable String value) { this.operationId = value; return this; }
        /**
         * Sets the reconciliation key.
         *
         * @param value key or {@code null}
         * @return this builder
         */
        public Builder idempotencyKey(@Nullable String value) { this.idempotencyKey = value; return this; }
        /**
         * Sets the started-attempt count.
         *
         * @param value attempt count
         * @return this builder
         */
        public Builder attemptCount(int value) { this.attemptCount = value; return this; }
        /**
         * Sets the final HTTP status.
         *
         * @param value status or {@code null}
         * @return this builder
         */
        public Builder httpStatus(@Nullable Integer value) { this.httpStatus = value; return this; }
        /**
         * Sets retry-policy eligibility.
         *
         * @param value retryable flag
         * @return this builder
         */
        public Builder retryable(boolean value) { this.retryable = value; return this; }
        /**
         * Sets compressed response bytes.
         *
         * @param value nonnegative byte count
         * @return this builder
         */
        public Builder compressedBytes(long value) { this.compressedBytes = value; return this; }
        /**
         * Sets decompressed response bytes.
         *
         * @param value nonnegative byte count
         * @return this builder
         */
        public Builder decompressedBytes(long value) { this.decompressedBytes = value; return this; }
        /**
         * Sets response header fields.
         *
         * @param value nonnegative field count
         * @return this builder
         */
        public Builder responseHeaderFields(int value) { this.responseHeaderFields = value; return this; }
        /**
         * Sets response header bytes.
         *
         * @param value nonnegative byte count
         * @return this builder
         */
        public Builder responseHeaderBytes(long value) { this.responseHeaderBytes = value; return this; }
        /**
         * Sets diagnostic truncation.
         *
         * @param value truncation flag
         * @return this builder
         */
        public Builder truncated(boolean value) { this.truncated = value; return this; }
        /**
         * Sets the network reason.
         *
         * @param value reason or {@code null}
         * @return this builder
         */
        public Builder failureReason(@Nullable RepostFailureReason value) { this.failureReason = value; return this; }
        /**
         * Sets the local cause category.
         *
         * @param value category or {@code null}
         * @return this builder
         */
        public Builder causeCategory(@Nullable RepostCauseCategory value) { this.causeCategory = value; return this; }

        /**
         * Sets close failures in the fixed close-attempt order.
         * @param categories categories or {@code null}
         * @return this builder
         */
        public Builder closeFailures(@Nullable List<RepostCauseCategory> categories) {
            this.closeFailureCategories = categories == null ? null : new ArrayList<>(categories);
            return this;
        }

        /**
         * Sets bounded validation issues.
         * @param issues retained issues or {@code null}
         * @param issueCount total issue count
         * @param truncated whether retained records are truncated
         * @return this builder
         */
        public Builder validationIssues(
                @Nullable List<ValidationIssue> issues,
                int issueCount,
                boolean truncated) {
            if (issueMethodCalled) {
                throw new IllegalArgumentException("issue list already configured");
            }
            this.issueMethodCalled = true;
            this.validationIssues = issues == null ? null : new ArrayList<>(issues);
            this.issueCount = issueCount;
            this.issuesTruncated = truncated;
            return this;
        }

        /**
         * Sets bounded configuration issues.
         * @param issues retained issues or {@code null}
         * @param issueCount total issue count
         * @param truncated whether retained records are truncated
         * @return this builder
         */
        public Builder configurationIssues(
                @Nullable List<ConfigurationIssue> issues,
                int issueCount,
                boolean truncated) {
            if (issueMethodCalled) {
                throw new IllegalArgumentException("issue list already configured");
            }
            this.issueMethodCalled = true;
            this.configurationIssues = issues == null ? null : new ArrayList<>(issues);
            this.issueCount = issueCount;
            this.issuesTruncated = truncated;
            return this;
        }

        /**
         * Builds validated details.
         *
         * @return immutable structured details
         */
        public RepostErrorDetails build() { return new RepostErrorDetails(this); }
    }

    private static final class IssueLists {
        private final List<ValidationIssue> validationIssues;
        private final List<ConfigurationIssue> configurationIssues;
        private final int issueCount;
        private final boolean truncated;

        private IssueLists(
                List<ValidationIssue> validationIssues,
                List<ConfigurationIssue> configurationIssues,
                int issueCount,
                boolean truncated) {
            this.validationIssues = validationIssues;
            this.configurationIssues = configurationIssues;
            this.issueCount = issueCount;
            this.truncated = truncated;
        }
    }
}
