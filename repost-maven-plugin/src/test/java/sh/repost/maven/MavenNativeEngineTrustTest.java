package sh.repost.maven;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;
import sh.repost.buildplugin.internal.NativeEngineTrust;

final class MavenNativeEngineTrustTest {
    @Test
    void loadsAnEmbeddedPinnedX509PublicKey() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair keyPair = generator.generateKeyPair();
        String pem = "-----BEGIN PUBLIC KEY-----\n" +
            Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(keyPair.getPublic().getEncoded()) +
            "\n-----END PUBLIC KEY-----\n";

        List<java.security.PublicKey> keys = NativeEngineTrust.load(loader(pem));

        assertEquals(1, keys.size());
        assertEquals(keyPair.getPublic(), keys.get(0));
    }

    @Test
    void missingTrustRootsFailWithoutLookingOutsideThePlugin() {
        NativeEngineTrust.TrustException failure = assertThrows(
            NativeEngineTrust.TrustException.class,
            () -> NativeEngineTrust.load(loader(null))
        );
        assertEquals(
            "native engine signing trust roots are unavailable in this plugin artifact",
            failure.getMessage()
        );
    }

    @Test
    void malformedTrustRootsFailClosed() {
        NativeEngineTrust.TrustException failure = assertThrows(
            NativeEngineTrust.TrustException.class,
            () -> NativeEngineTrust.load(loader("-----BEGIN PRIVATE KEY-----\nsecret\n-----END PRIVATE KEY-----\n"))
        );
        assertEquals(
            "native engine signing trust roots are unavailable in this plugin artifact",
            failure.getMessage()
        );
    }

    private static ClassLoader loader(String value) {
        return new ClassLoader(null) {
            @Override
            public InputStream getResourceAsStream(String name) {
                if (!NativeEngineTrust.RESOURCE.equals(name) || value == null) {
                    return null;
                }
                return new ByteArrayInputStream(value.getBytes(StandardCharsets.US_ASCII));
            }
        };
    }
}
