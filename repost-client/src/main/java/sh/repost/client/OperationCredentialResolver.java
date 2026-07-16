package sh.repost.client;

import java.util.Collections;
import java.util.Objects;
import java.util.function.Function;
import sh.repost.client.error.RepostConfigurationException;

/** Resolves one credential once for one admitted operation. */
final class OperationCredentialResolver {
    private final ApiKeyProvider provider;
    private final String fixedApiKey;
    private final String snapshottedEnvironmentApiKey;
    private final boolean environmentSnapshotFailure;

    OperationCredentialResolver(
            ApiKeyProvider provider,
            String fixedApiKey,
            Function<String, String> environment) {
        this.provider = provider;
        this.fixedApiKey = fixedApiKey;
        Objects.requireNonNull(environment, "environment");
        String selected = null;
        boolean failed = false;
        if (provider == null && fixedApiKey == null) {
            try {
                selected = environment.apply("REPOST_SEND_API_KEY");
                if (selected == null) {
                    selected = environment.apply("REPOST_TOKEN");
                }
            } catch (Error fatal) {
                throw fatal;
            } catch (RuntimeException ignored) {
                failed = true;
            }
        }
        this.snapshottedEnvironmentApiKey = selected;
        this.environmentSnapshotFailure = failed;
    }

    ResolvedCredential resolve() {
        String selected;
        RepostCauseCategory cause = null;
        if (provider != null) {
            cause = RepostCauseCategory.API_KEY_PROVIDER;
            try {
                selected = provider.getApiKey();
            } catch (Error fatal) {
                throw fatal;
            } catch (RuntimeException failure) {
                throw invalid(ConfigurationIssueCode.INVALID_VALUE, cause);
            }
        } else if (fixedApiKey != null) {
            selected = fixedApiKey;
        } else {
            if (environmentSnapshotFailure) {
                throw invalid(ConfigurationIssueCode.INVALID_VALUE, RepostCauseCategory.UNKNOWN);
            }
            selected = snapshottedEnvironmentApiKey;
        }
        if (selected == null) {
            throw invalid(ConfigurationIssueCode.MISSING, cause);
        }
        try {
            return new ResolvedCredential(ClientOptions.validateApiKey(selected));
        } catch (IllegalArgumentException invalid) {
            throw invalid(ConfigurationIssueCode.INVALID_VALUE, cause);
        }
    }

    @Override
    public String toString() {
        return "OperationCredentialResolver[REDACTED]";
    }

    private static RepostConfigurationException invalid(
            ConfigurationIssueCode issueCode,
            RepostCauseCategory cause) {
        RepostErrorDetails.Builder details = RepostErrorDetails.builder(
                        RepostErrorCode.CONFIGURATION, DeliveryState.NOT_SENT)
                .configurationIssues(
                        Collections.singletonList(ConfigurationIssue.of(
                                issueCode,
                                Collections.singletonList(ClientOptionKey.API_KEY))),
                        1,
                        false);
        if (cause != null) {
            details.causeCategory(cause);
        }
        return new RepostConfigurationException(details.build());
    }

    static final class ResolvedCredential {
        private final String value;

        private ResolvedCredential(String value) {
            this.value = value;
        }

        String value() {
            return value;
        }

        @Override
        public String toString() {
            return "ResolvedCredential[REDACTED]";
        }
    }
}
