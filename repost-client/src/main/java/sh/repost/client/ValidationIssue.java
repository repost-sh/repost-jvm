package sh.repost.client;

import java.util.Objects;
import java.util.regex.Pattern;

/** One immutable validation issue containing only a stable code and safe schema path. */
public final class ValidationIssue {
    private static final Pattern PATH = Pattern.compile(
            "^\\$(?:(?:\\.[A-Za-z_][A-Za-z0-9_]*)|(?:\\[(?:0|[1-9][0-9]*)\\])|(?:\\[\\{\\*\\}\\]))*(?:\\.<truncated>)?$");

    private final ValidationIssueCode code;
    private final String path;

    private ValidationIssue(ValidationIssueCode code, String path) {
        this.code = Objects.requireNonNull(code, "code");
        this.path = Objects.requireNonNull(path, "path");
        if (!PATH.matcher(path).matches() || utf8Bytes(path) > RepostErrorDetails.MAX_ISSUE_PATH_UTF8_BYTES) {
            throw new IllegalArgumentException("path is outside the validation-path contract");
        }
    }

    /**
     * Creates a validated issue.
     *
     * @param code stable validation code
     * @param path bounded schema-derived path
     * @return immutable issue
     */
    public static ValidationIssue of(ValidationIssueCode code, String path) {
        return new ValidationIssue(code, path);
    }

    /**
     * Returns validation code.
     *
     * @return validation code
     */
    public ValidationIssueCode getCode() { return code; }
    /**
     * Returns safe bounded schema-derived path.
     *
     * @return safe bounded schema-derived path
     */
    public String getPath() { return path; }

    @Override
    public String toString() {
        return "ValidationIssue[code=" + code + ", path=" + path + "]";
    }

    static int utf8Bytes(String value) {
        int bytes = 0;
        for (int index = 0; index < value.length();) {
            char first = value.charAt(index);
            int codePoint;
            if (Character.isHighSurrogate(first)) {
                if (index + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(index + 1))) {
                    throw new IllegalArgumentException("string contains invalid Unicode");
                }
                codePoint = Character.toCodePoint(first, value.charAt(index + 1));
                index += 2;
            } else if (Character.isLowSurrogate(first)) {
                throw new IllegalArgumentException("string contains invalid Unicode");
            } else {
                codePoint = first;
                index++;
            }
            bytes += codePoint <= 0x7f ? 1 : codePoint <= 0x7ff ? 2 : codePoint <= 0xffff ? 3 : 4;
        }
        return bytes;
    }
}
