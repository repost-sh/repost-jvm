package sh.repost.buildplugin.internal;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;

/** Shared loader for pinned native-engine signing trust roots embedded in build plugins. */
public final class NativeEngineTrust {
    /** Classpath location populated by the signed build-plugin release. */
    public static final String RESOURCE = "META-INF/repost/native-engine-signing-keys.pem";
    private static final int MAX_RESOURCE_BYTES = 65_536;

    private NativeEngineTrust() {
    }

    /**
     * Loads approved X.509 RSA/EC public keys from one bounded plugin resource.
     *
     * @param classLoader released plugin class loader
     * @return immutable pinned key list
     */
    public static List<PublicKey> load(ClassLoader classLoader) {
        if (classLoader == null) {
            throw unavailable();
        }
        byte[] resource;
        try (InputStream input = classLoader.getResourceAsStream(RESOURCE)) {
            if (input == null) {
                throw unavailable();
            }
            resource = readBounded(input);
        } catch (IOException exception) {
            throw unavailable();
        }
        String pem = new String(resource, StandardCharsets.US_ASCII);
        List<PublicKey> keys = new ArrayList<>();
        String begin = "-----BEGIN PUBLIC KEY-----";
        String end = "-----END PUBLIC KEY-----";
        int offset = 0;
        while (true) {
            int start = pem.indexOf(begin, offset);
            if (start < 0) {
                break;
            }
            if (!pem.substring(offset, start).trim().isEmpty()) {
                throw unavailable();
            }
            int finish = pem.indexOf(end, start + begin.length());
            if (finish < 0) {
                throw unavailable();
            }
            String encoded = pem.substring(start + begin.length(), finish).replaceAll("\\s", "");
            keys.add(decode(encoded));
            offset = finish + end.length();
        }
        if (keys.isEmpty() || !pem.substring(offset).trim().isEmpty()) {
            throw unavailable();
        }
        return Collections.unmodifiableList(keys);
    }

    private static byte[] readBounded(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int read;
        while ((read = input.read(buffer)) >= 0) {
            if (read == 0) {
                continue;
            }
            if (output.size() + read > MAX_RESOURCE_BYTES) {
                throw new IOException("trust resource is too large");
            }
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    private static PublicKey decode(String encoded) {
        final byte[] value;
        try {
            value = Base64.getDecoder().decode(encoded);
        } catch (IllegalArgumentException exception) {
            throw unavailable();
        }
        X509EncodedKeySpec specification = new X509EncodedKeySpec(value);
        for (String algorithm : new String[]{"RSA", "EC"}) {
            try {
                return KeyFactory.getInstance(algorithm).generatePublic(specification);
            } catch (GeneralSecurityException ignored) {
                // Try the other approved key family.
            }
        }
        throw unavailable();
    }

    private static TrustException unavailable() {
        return new TrustException("native engine signing trust roots are unavailable in this plugin artifact");
    }

    /** Sanitized missing or malformed trust-root failure. */
    public static final class TrustException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        private TrustException(String message) {
            super(message);
        }
    }
}
