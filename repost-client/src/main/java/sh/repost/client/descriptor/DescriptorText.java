package sh.repost.client.descriptor;

final class DescriptorText {
    private DescriptorText() { }

    static String requireIdentifier(String value, String name) {
        requireText(value, name);
        char first = value.charAt(0);
        if (!isIdentifierStart(first)) {
            throw new IllegalArgumentException(name + " is not a target identifier");
        }
        for (int index = 1; index < value.length(); index++) {
            char character = value.charAt(index);
            if (!isIdentifierStart(character) && (character < '0' || character > '9')) {
                throw new IllegalArgumentException(name + " is not a target identifier");
            }
        }
        return value;
    }

    static String requireText(String value, String name) {
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be empty");
        }
        return requireScalar(value, name);
    }

    static String requireScalar(String value, String name) {
        if (value == null) {
            throw new IllegalArgumentException(name + " must not be null");
        }
        for (int index = 0; index < value.length();) {
            char character = value.charAt(index);
            if (Character.isHighSurrogate(character)) {
                if (index + 1 >= value.length()
                        || !Character.isLowSurrogate(value.charAt(index + 1))) {
                    throw new IllegalArgumentException(name + " contains invalid Unicode");
                }
                index += 2;
            } else if (Character.isLowSurrogate(character)) {
                throw new IllegalArgumentException(name + " contains invalid Unicode");
            } else {
                index++;
            }
        }
        return value;
    }

    private static boolean isIdentifierStart(char character) {
        return character == '_' || character >= 'A' && character <= 'Z'
                || character >= 'a' && character <= 'z';
    }
}
