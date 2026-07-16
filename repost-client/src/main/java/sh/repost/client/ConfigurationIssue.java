package sh.repost.client;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** One immutable configuration issue naming only stable option keys. */
public final class ConfigurationIssue {
    private final ConfigurationIssueCode code;
    private final List<ClientOptionKey> optionKeys;

    private ConfigurationIssue(ConfigurationIssueCode code, List<ClientOptionKey> optionKeys) {
        this.code = Objects.requireNonNull(code, "code");
        Objects.requireNonNull(optionKeys, "optionKeys");
        int expectedSize = code == ConfigurationIssueCode.CONFLICT
                || code == ConfigurationIssueCode.RESOURCE_MISMATCH ? 2 : 1;
        if (optionKeys.size() != expectedSize) {
            throw new IllegalArgumentException("configuration issue has the wrong option-key count");
        }
        ArrayList<ClientOptionKey> copy = new ArrayList<>(expectedSize);
        int priorOrdinal = -1;
        for (ClientOptionKey key : optionKeys) {
            Objects.requireNonNull(key, "optionKeys contains null");
            if (key.ordinal() <= priorOrdinal) {
                throw new IllegalArgumentException("optionKeys must be distinct declaration-order values");
            }
            priorOrdinal = key.ordinal();
            copy.add(key);
        }
        this.optionKeys = Collections.unmodifiableList(copy);
    }

    /**
     * Creates a validated configuration issue.
     *
     * @param code stable configuration issue code
     * @param optionKeys one or two declaration-ordered option keys
     * @return immutable issue
     */
    public static ConfigurationIssue of(
            ConfigurationIssueCode code,
            List<ClientOptionKey> optionKeys) {
        return new ConfigurationIssue(code, optionKeys);
    }

    /**
     * Returns issue code.
     *
     * @return issue code
     */
    public ConfigurationIssueCode getCode() { return code; }
    /**
     * Returns immutable declaration-ordered option keys.
     *
     * @return immutable declaration-ordered option keys
     */
    public List<ClientOptionKey> getOptionKeys() { return optionKeys; }

    @Override
    public String toString() {
        return "ConfigurationIssue[code=" + code + ", optionKeys=" + optionKeys + "]";
    }
}
