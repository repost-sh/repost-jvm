package sh.repost.client.internal;

import java.net.URI;
import java.net.URISyntaxException;

/** Canonical ASCII URI validation shared by runtime construction and framework adapters. */
public final class UriValidator {
    private static final int MAX_URI_BYTES = 2_048;
    private static final int MAX_AUTHORITY_BYTES = 512;
    private static final int MAX_BASE_PATH_BYTES = 1_536;
    private static final int MAX_TARGET_BYTES = 2_048;
    private static final int MAX_CANONICAL_URI_BYTES = 4_096;
    private static final String ENDPOINT = "/v1/messages";

    private UriValidator() { }

    /**
     * Validates and appends the fixed Repost message endpoint.
     *
     * @param rawValue raw configured base URI
     * @return canonical message endpoint URI
     */
    public static URI canonicalEndpoint(String rawValue) {
        requireAscii(rawValue, "baseUri", MAX_URI_BYTES);
        String scheme;
        if (rawValue.startsWith("https://")) {
            scheme = "https";
        } else if (rawValue.startsWith("http://")) {
            scheme = "http";
        } else {
            throw invalid("baseUri must use lowercase http:// or https://");
        }
        if (containsForbidden(rawValue)) {
            throw invalid("baseUri contains a forbidden delimiter or character");
        }

        int authorityStart = scheme.length() + 3;
        int pathStart = rawValue.indexOf('/', authorityStart);
        String authority = pathStart < 0
                ? rawValue.substring(authorityStart)
                : rawValue.substring(authorityStart, pathStart);
        String path = pathStart < 0 ? "" : rawValue.substring(pathStart);
        if (authority.isEmpty() || authority.length() > MAX_AUTHORITY_BYTES) {
            throw invalid("baseUri authority is empty or too long");
        }
        if (path.length() > MAX_BASE_PATH_BYTES) {
            throw invalid("baseUri path is too long");
        }
        validateAuthority(authority, scheme);
        validatePath(path);

        URI parsed;
        try {
            parsed = new URI(rawValue);
        } catch (URISyntaxException exception) {
            throw invalid("baseUri is not a valid URI");
        }
        if (!parsed.isAbsolute()
                || parsed.getRawUserInfo() != null
                || parsed.getRawQuery() != null
                || parsed.getRawFragment() != null
                || !rawValue.equals(parsed.toASCIIString())) {
            throw invalid("baseUri is not a canonical absolute URI");
        }

        String basePath = stripTrailingSlashes(path);
        String target = basePath.endsWith(ENDPOINT) ? basePath : basePath + ENDPOINT;
        if (target.isEmpty()) {
            target = ENDPOINT;
        }
        if (target.length() > MAX_TARGET_BYTES) {
            throw invalid("baseUri produces a request target that is too long");
        }
        String canonical = scheme + "://" + authority + target;
        if (canonical.length() > MAX_CANONICAL_URI_BYTES) {
            throw invalid("baseUri produces a canonical URI that is too long");
        }
        return URI.create(canonical);
    }

    /**
     * Validates an explicit HTTP proxy URI.
     *
     * @param rawValue raw configured proxy URI
     * @return canonical proxy URI
     */
    public static URI canonicalProxy(String rawValue) {
        requireAscii(rawValue, "proxy uri", MAX_URI_BYTES);
        if (!rawValue.startsWith("http://") || containsForbidden(rawValue)) {
            throw invalid("proxy uri must use lowercase http://");
        }
        int authorityStart = "http://".length();
        int pathStart = rawValue.indexOf('/', authorityStart);
        String authority = pathStart < 0
                ? rawValue.substring(authorityStart)
                : rawValue.substring(authorityStart, pathStart);
        String path = pathStart < 0 ? "" : rawValue.substring(pathStart);
        if (!path.isEmpty() && !"/".equals(path)) {
            throw invalid("proxy uri path must be empty or /");
        }
        HostPort hostPort = splitAuthority(authority, true);
        validateHost(hostPort.host);
        if (hostPort.port == null) {
            throw invalid("proxy uri requires an explicit port");
        }
        try {
            URI parsed = new URI(rawValue);
            if (!rawValue.equals(parsed.toASCIIString())) {
                throw invalid("proxy uri is not canonical");
            }
            return parsed;
        } catch (URISyntaxException exception) {
            throw invalid("proxy uri is not valid");
        }
    }

    private static void validateAuthority(String authority, String scheme) {
        HostPort hostPort = splitAuthority(authority, false);
        validateHost(hostPort.host);
        if ("http".equals(scheme) && !isAllowedHttpLoopback(hostPort.host)) {
            throw invalid("http baseUri is allowed only for exact loopback hosts");
        }
    }

    private static HostPort splitAuthority(String authority, boolean requirePort) {
        if (authority.indexOf('@') >= 0) {
            throw invalid("userinfo is forbidden");
        }
        String host;
        String port = null;
        if (authority.startsWith("[")) {
            int end = authority.indexOf(']');
            if (end < 0) {
                throw invalid("unterminated IP literal");
            }
            host = authority.substring(0, end + 1);
            if (end + 1 < authority.length()) {
                if (authority.charAt(end + 1) != ':') {
                    throw invalid("invalid authority suffix");
                }
                port = authority.substring(end + 2);
            }
        } else {
            int colon = authority.lastIndexOf(':');
            if (colon >= 0) {
                if (authority.indexOf(':') != colon) {
                    throw invalid("IPv6 literals must be bracketed");
                }
                host = authority.substring(0, colon);
                port = authority.substring(colon + 1);
            } else {
                host = authority;
            }
        }
        if (host.isEmpty()) {
            throw invalid("host is empty");
        }
        if (port != null) {
            validatePort(port);
        } else if (requirePort) {
            throw invalid("explicit port is required");
        }
        return new HostPort(host, port);
    }

    private static void validateHost(String host) {
        if (host.startsWith("[") && host.endsWith("]")) {
            String literal = host.substring(1, host.length() - 1);
            if (literal.isEmpty() || literal.indexOf('%') >= 0) {
                throw invalid("invalid IP literal");
            }
            for (int index = 0; index < literal.length(); index++) {
                char value = literal.charAt(index);
                if (!(isHex(value) || value == ':' || value == '.')) {
                    throw invalid("invalid IP literal");
                }
            }
            return;
        }
        if (host.startsWith(".") || host.endsWith(".") || host.contains("..")) {
            throw invalid("invalid host");
        }
        boolean numeric = true;
        for (int index = 0; index < host.length(); index++) {
            char value = host.charAt(index);
            if (!isAsciiLetter(value) && !isDigit(value) && value != '-' && value != '.') {
                throw invalid("invalid ASCII host");
            }
            numeric &= isDigit(value) || value == '.';
        }
        if (numeric && !isCanonicalIpv4(host)) {
            throw invalid("numeric hosts must use canonical dotted-decimal IPv4");
        }
    }

    private static boolean isAllowedHttpLoopback(String host) {
        if ("localhost".equals(host) || "[::1]".equals(host)) {
            return true;
        }
        if (!isCanonicalIpv4(host)) {
            return false;
        }
        String[] octets = host.split("\\.", -1);
        return "127".equals(octets[0]);
    }

    private static boolean isCanonicalIpv4(String host) {
        String[] octets = host.split("\\.", -1);
        if (octets.length != 4) {
            return false;
        }
        for (String octet : octets) {
            if (octet.isEmpty() || (octet.length() > 1 && octet.charAt(0) == '0')) {
                return false;
            }
            int value = 0;
            for (int index = 0; index < octet.length(); index++) {
                if (!isDigit(octet.charAt(index))) {
                    return false;
                }
                value = value * 10 + octet.charAt(index) - '0';
            }
            if (value > 255) {
                return false;
            }
        }
        return true;
    }

    private static void validatePort(String value) {
        if (value.isEmpty() || value.length() > 5 || (value.length() > 1 && value.charAt(0) == '0')) {
            throw invalid("port must use canonical decimal spelling");
        }
        int port = 0;
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (!isDigit(character)) {
                throw invalid("port must be decimal");
            }
            port = port * 10 + character - '0';
        }
        if (port < 1 || port > 65_535) {
            throw invalid("port is outside 1..65535");
        }
    }

    private static void validatePath(String path) {
        int segmentStart = 0;
        for (int index = 0; index < path.length(); index++) {
            char value = path.charAt(index);
            if (value == '/') {
                validateDotSegment(path, segmentStart, index);
                segmentStart = index + 1;
                continue;
            }
            if (value == '%') {
                if (index + 2 >= path.length()
                        || !isUpperHex(path.charAt(index + 1))
                        || !isUpperHex(path.charAt(index + 2))) {
                    throw invalid("percent escapes must use uppercase hexadecimal");
                }
                int decoded = hexValue(path.charAt(index + 1)) * 16 + hexValue(path.charAt(index + 2));
                if (isUnreserved((char) decoded) || decoded == '/' || decoded == '\\') {
                    throw invalid("percent escape encodes a forbidden or canonical character");
                }
                index += 2;
                continue;
            }
            if (!isPathCharacter(value)) {
                throw invalid("invalid baseUri path character");
            }
        }
        validateDotSegment(path, segmentStart, path.length());
    }

    private static void validateDotSegment(String path, int start, int end) {
        int length = end - start;
        if ((length == 1 && path.charAt(start) == '.')
                || (length == 2 && path.charAt(start) == '.' && path.charAt(start + 1) == '.')) {
            throw invalid("dot path segments are forbidden");
        }
    }

    private static boolean containsForbidden(String value) {
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character <= 0x20 || character == 0x7f || character == '\\'
                    || character == '?' || character == '#') {
                return true;
            }
        }
        return false;
    }

    private static String stripTrailingSlashes(String value) {
        int end = value.length();
        while (end > 0 && value.charAt(end - 1) == '/') {
            end--;
        }
        return value.substring(0, end);
    }

    private static void requireAscii(String value, String name, int maxBytes) {
        if (value == null) {
            throw new NullPointerException(name);
        }
        if (value.isEmpty() || value.length() > maxBytes) {
            throw invalid(name + " is empty or too long");
        }
        for (int index = 0; index < value.length(); index++) {
            if (value.charAt(index) > 0x7f) {
                throw invalid(name + " must be ASCII");
            }
        }
    }

    private static boolean isPathCharacter(char value) {
        return isUnreserved(value) || value == '!' || value == '$' || value == '&'
                || value == '\'' || value == '(' || value == ')' || value == '*'
                || value == '+' || value == ',' || value == ';' || value == '='
                || value == ':' || value == '@';
    }

    private static boolean isUnreserved(char value) {
        return isAsciiLetter(value) || isDigit(value)
                || value == '-' || value == '.' || value == '_' || value == '~';
    }

    private static boolean isAsciiLetter(char value) {
        return (value >= 'a' && value <= 'z') || (value >= 'A' && value <= 'Z');
    }

    private static boolean isDigit(char value) {
        return value >= '0' && value <= '9';
    }

    private static boolean isHex(char value) {
        return isDigit(value)
                || (value >= 'a' && value <= 'f')
                || (value >= 'A' && value <= 'F');
    }

    private static boolean isUpperHex(char value) {
        return isDigit(value) || (value >= 'A' && value <= 'F');
    }

    private static int hexValue(char value) {
        if (isDigit(value)) {
            return value - '0';
        }
        return value - 'A' + 10;
    }

    private static IllegalArgumentException invalid(String message) {
        return new IllegalArgumentException(message);
    }

    private static final class HostPort {
        private final String host;
        private final String port;

        private HostPort(String host, String port) {
            this.host = host;
            this.port = port;
        }
    }
}
