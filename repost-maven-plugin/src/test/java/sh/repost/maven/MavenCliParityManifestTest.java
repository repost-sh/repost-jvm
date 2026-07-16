package sh.repost.maven;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class MavenCliParityManifestTest {
    @TempDir
    Path temporary;

    @Test
    void equivalentCliAndMavenRootsHaveIdenticalBytesAndCanonicalTreeHashes() throws Exception {
        Properties manifest = new Properties();
        try (InputStream input = getClass().getResourceAsStream("/cli-maven-parity-v1.properties")) {
            assertNotNull(input);
            manifest.load(input);
        }
        assertEquals("1", manifest.getProperty("formatVersion"));
        assertEquals("u64-big-endian", manifest.getProperty("lengthFraming"));
        assertEquals("plugin-owned-tested-separately", manifest.getProperty("aggregateGlue"));

        for (String language : List.of("java", "kotlin")) {
            for (String kind : List.of("source", "resources")) {
                Path cliRoot = repositoryRoot().resolve(
                    "repost-rs/repost-schema/schema/tests/" + language + "_sdk/canonical/" + kind);
                Path mavenRoot = temporary.resolve(language).resolve(kind);
                copyTree(cliRoot, mavenRoot);

                assertTreesEqual(cliRoot, mavenRoot);
                assertEquals(manifest.getProperty(language + "." + kind), treeHash(mavenRoot));
            }
        }
    }

    private static void copyTree(Path source, Path target) throws Exception {
        for (Path file : files(source)) {
            Path destination = target.resolve(source.relativize(file).toString());
            Files.createDirectories(destination.getParent());
            Files.copy(file, destination, StandardCopyOption.COPY_ATTRIBUTES);
        }
    }

    private static void assertTreesEqual(Path expected, Path actual) throws Exception {
        List<Path> expectedFiles = files(expected);
        List<Path> actualFiles = files(actual);
        assertEquals(relativeNames(expected, expectedFiles), relativeNames(actual, actualFiles));
        for (int index = 0; index < expectedFiles.size(); index++) {
            assertArrayEquals(
                Files.readAllBytes(expectedFiles.get(index)),
                Files.readAllBytes(actualFiles.get(index)),
                expected.relativize(expectedFiles.get(index)).toString()
            );
        }
    }

    private static String treeHash(Path root) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        for (Path file : files(root)) {
            hashPart(digest, root.relativize(file).toString().replace('\\', '/')
                .getBytes(StandardCharsets.UTF_8));
            hashPart(digest, Files.readAllBytes(file));
        }
        StringBuilder result = new StringBuilder();
        for (byte value : digest.digest()) {
            result.append(String.format("%02x", value & 0xff));
        }
        return result.toString();
    }

    private static void hashPart(MessageDigest digest, byte[] value) {
        digest.update(ByteBuffer.allocate(8).putLong(value.length).array());
        digest.update(value);
    }

    private static List<Path> files(Path root) throws Exception {
        try (java.util.stream.Stream<Path> paths = Files.walk(root)) {
            return paths.filter(Files::isRegularFile)
                .sorted((left, right) -> root.relativize(left).toString()
                    .compareTo(root.relativize(right).toString()))
                .collect(java.util.stream.Collectors.toList());
        }
    }

    private static List<String> relativeNames(Path root, List<Path> files) {
        List<String> names = new ArrayList<>();
        for (Path file : files) {
            names.add(root.relativize(file).toString().replace('\\', '/'));
        }
        return names;
    }

    private static Path repositoryRoot() {
        Path current = Path.of("").toAbsolutePath().normalize();
        while (current != null && !Files.isDirectory(current.resolve("repost-rs"))) {
            current = current.getParent();
        }
        if (current == null) {
            throw new AssertionError("repository root was not found");
        }
        return current;
    }
}
