package sh.repost.buildplugin.internal;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class StrictJson {
    private static final int MAX_DEPTH = 32;

    private final String input;
    private int offset;

    private StrictJson(String input) {
        this.input = input;
    }

    static Object parse(byte[] bytes) {
        final String decoded;
        try {
            decoded = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString();
        } catch (CharacterCodingException exception) {
            throw new GenerationProtocolV1.ProtocolException("response is not valid UTF-8", exception);
        }
        StrictJson parser = new StrictJson(decoded);
        Object result = parser.value(0);
        parser.whitespace();
        if (parser.offset != decoded.length()) {
            throw parser.failure("trailing JSON content");
        }
        return result;
    }

    private Object value(int depth) {
        if (depth > MAX_DEPTH) {
            throw failure("JSON nesting exceeds the protocol limit");
        }
        whitespace();
        if (offset == input.length()) {
            throw failure("unexpected end of JSON");
        }
        char current = input.charAt(offset);
        switch (current) {
            case '{':
                return object(depth + 1);
            case '[':
                return array(depth + 1);
            case '"':
                return string();
            case 't':
                literal("true");
                return Boolean.TRUE;
            case 'f':
                literal("false");
                return Boolean.FALSE;
            case 'n':
                literal("null");
                return null;
            default:
                if (current == '-' || isDigit(current)) {
                    return integer();
                }
                throw failure("unexpected JSON token");
        }
    }

    private Map<String, Object> object(int depth) {
        expect('{');
        whitespace();
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        if (take('}')) {
            return Collections.unmodifiableMap(result);
        }
        while (true) {
            whitespace();
            if (offset == input.length() || input.charAt(offset) != '"') {
                throw failure("object key must be a string");
            }
            String key = string();
            if (result.containsKey(key)) {
                throw failure("duplicate object key");
            }
            whitespace();
            expect(':');
            result.put(key, value(depth));
            whitespace();
            if (take('}')) {
                return Collections.unmodifiableMap(result);
            }
            expect(',');
        }
    }

    private List<Object> array(int depth) {
        expect('[');
        whitespace();
        List<Object> result = new ArrayList<>();
        if (take(']')) {
            return Collections.unmodifiableList(result);
        }
        while (true) {
            result.add(value(depth));
            whitespace();
            if (take(']')) {
                return Collections.unmodifiableList(result);
            }
            expect(',');
        }
    }

    private String string() {
        expect('"');
        StringBuilder result = new StringBuilder();
        while (offset < input.length()) {
            char current = input.charAt(offset++);
            if (current == '"') {
                return result.toString();
            }
            if (current == '\\') {
                escape(result);
            } else if (current < 0x20) {
                throw failure("unescaped control character in string");
            } else if (Character.isHighSurrogate(current)) {
                if (offset == input.length() || !Character.isLowSurrogate(input.charAt(offset))) {
                    throw failure("unpaired high surrogate in string");
                }
                result.append(current).append(input.charAt(offset++));
            } else if (Character.isLowSurrogate(current)) {
                throw failure("unpaired low surrogate in string");
            } else {
                result.append(current);
            }
        }
        throw failure("unterminated string");
    }

    private void escape(StringBuilder result) {
        if (offset == input.length()) {
            throw failure("unterminated string escape");
        }
        char escaped = input.charAt(offset++);
        switch (escaped) {
            case '"':
            case '\\':
            case '/':
                result.append(escaped);
                return;
            case 'b':
                result.append('\b');
                return;
            case 'f':
                result.append('\f');
                return;
            case 'n':
                result.append('\n');
                return;
            case 'r':
                result.append('\r');
                return;
            case 't':
                result.append('\t');
                return;
            case 'u':
                appendUnicodeEscape(result);
                return;
            default:
                throw failure("unknown string escape");
        }
    }

    private void appendUnicodeEscape(StringBuilder result) {
        char first = readHexUnit();
        if (Character.isHighSurrogate(first)) {
            if (offset + 2 > input.length() || input.charAt(offset) != '\\' || input.charAt(offset + 1) != 'u') {
                throw failure("escaped high surrogate has no low surrogate");
            }
            offset += 2;
            char second = readHexUnit();
            if (!Character.isLowSurrogate(second)) {
                throw failure("escaped high surrogate has invalid pair");
            }
            result.append(first).append(second);
        } else if (Character.isLowSurrogate(first)) {
            throw failure("escaped low surrogate has no high surrogate");
        } else {
            result.append(first);
        }
    }

    private char readHexUnit() {
        if (offset + 4 > input.length()) {
            throw failure("short Unicode escape");
        }
        int value = 0;
        for (int index = 0; index < 4; index++) {
            int digit = Character.digit(input.charAt(offset++), 16);
            if (digit < 0) {
                throw failure("invalid Unicode escape");
            }
            value = (value << 4) | digit;
        }
        return (char) value;
    }

    private Long integer() {
        int start = offset;
        if (take('-') && offset == input.length()) {
            throw failure("invalid JSON integer");
        }
        if (take('0')) {
            if (offset < input.length() && isDigit(input.charAt(offset))) {
                throw failure("leading zero in JSON integer");
            }
        } else {
            if (offset == input.length() || !isDigitOneToNine(input.charAt(offset))) {
                throw failure("invalid JSON integer");
            }
            while (offset < input.length() && isDigit(input.charAt(offset))) {
                offset++;
            }
        }
        if (offset < input.length()) {
            char suffix = input.charAt(offset);
            if (suffix == '.' || suffix == 'e' || suffix == 'E') {
                throw failure("non-integer JSON number");
            }
        }
        try {
            return Long.valueOf(input.substring(start, offset));
        } catch (NumberFormatException ignored) {
            throw new GenerationProtocolV1.ProtocolException("JSON integer is out of range");
        }
    }

    private void literal(String literal) {
        if (!input.regionMatches(offset, literal, 0, literal.length())) {
            throw failure("invalid JSON literal");
        }
        offset += literal.length();
    }

    private void whitespace() {
        while (offset < input.length()) {
            char current = input.charAt(offset);
            if (current != ' ' && current != '\n' && current != '\r' && current != '\t') {
                return;
            }
            offset++;
        }
    }

    private void expect(char expected) {
        if (!take(expected)) {
            throw failure("expected JSON delimiter");
        }
    }

    private boolean take(char expected) {
        if (offset < input.length() && input.charAt(offset) == expected) {
            offset++;
            return true;
        }
        return false;
    }

    private GenerationProtocolV1.ProtocolException failure(String reason) {
        return new GenerationProtocolV1.ProtocolException(reason + " at character " + offset);
    }

    private static boolean isDigit(char value) {
        return value >= '0' && value <= '9';
    }

    private static boolean isDigitOneToNine(char value) {
        return value >= '1' && value <= '9';
    }
}
