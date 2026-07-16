package sh.repost.gradle;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarFile;
import org.junit.jupiter.api.Test;
import sh.repost.buildplugin.internal.NativeEngineTrust;

final class NativeEngineTrustEmbeddingTest {
    @Test
    void embedsOneIdenticalValidatedPublicTrustResourceInBothPluginJars() throws Exception {
        byte[] expected = Files.readAllBytes(propertyPath("repost.nativeEngineSigningKeysFile"));
        assertFalse(new String(expected, StandardCharsets.US_ASCII).contains("PRIVATE KEY"));

        byte[] gradleTrust = trustBytes(propertyPath("repost.gradlePluginJar"));
        byte[] mavenTrust = trustBytes(propertyPath("repost.mavenPluginJar"));
        assertArrayEquals(expected, gradleTrust);
        assertArrayEquals(expected, mavenTrust);
        assertArrayEquals(gradleTrust, mavenTrust);
    }

    private static Path propertyPath(String name) {
        String value = System.getProperty(name);
        if (value == null) {
            throw new IllegalStateException("missing test system property " + name);
        }
        return Path.of(value);
    }

    private static byte[] trustBytes(Path jar) throws Exception {
        try (JarFile archive = new JarFile(jar.toFile())) {
            long count = archive.stream()
                .filter(entry -> NativeEngineTrust.RESOURCE.equals(entry.getName()))
                .count();
            assertEquals(1, count, "expected one trust resource in " + jar);
            byte[] value = archive.getInputStream(archive.getJarEntry(NativeEngineTrust.RESOURCE)).readAllBytes();
            try (URLClassLoader loader = new URLClassLoader(new java.net.URL[]{jar.toUri().toURL()}, null)) {
                assertFalse(NativeEngineTrust.load(loader).isEmpty());
            }
            return value;
        }
    }
}
