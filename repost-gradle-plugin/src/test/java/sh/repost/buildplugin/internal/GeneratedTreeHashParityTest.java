package sh.repost.buildplugin.internal;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

final class GeneratedTreeHashParityTest {
    @Test
    void authenticatesTheAuthoritativeRustTreeHashVectorsWithoutChangingBytes() throws Exception {
        String manifestProperty = System.getProperty("repost.entrypointParityManifest");
        assertNotNull(manifestProperty, "entrypoint parity manifest was not configured");
        @SuppressWarnings("unchecked")
        Map<String, Object> manifest = (Map<String, Object>) StrictJson.parse(
            Files.readAllBytes(Path.of(manifestProperty))
        );
        Map<String, Object> vectors = object(manifest, "hashVectors");
        Map<String, Object> sourceVector = object(vectors, "source");
        Map<String, Object> resourceVector = object(vectors, "resource");

        Path root = Files.createTempDirectory("repost-tree-hash-parity-");
        try {
            Path sourceOutput = root.resolve("source");
            Path resourceOutput = root.resolve("resource");
            Path sourceRoot = sourceOutput.resolve("com/acme/orders");
            Path resourceRoot = resourceOutput.resolve(
                "META-INF/repost/generated-clients/v1/0123456789abcdef"
            );
            Files.createDirectories(sourceRoot);
            Files.createDirectories(resourceRoot);
            writeVector(sourceRoot, sourceVector);
            writeVector(resourceRoot, resourceVector);
            Files.writeString(
                sourceRoot.resolve(".repost-client.json"),
                marker("source", sourceVector),
                StandardCharsets.UTF_8
            );
            Files.writeString(
                resourceRoot.resolve(".repost-client.json"),
                marker("registry", resourceVector),
                StandardCharsets.UTF_8
            );
            BuildPluginEngine.Generator generator = new BuildPluginEngine.Generator(
                "javaSdk",
                sourceOutput.toString(),
                resourceOutput.toString(),
                root.resolve("source-state").toString(),
                root.resolve("resource-state").toString()
            );

            assertDoesNotThrow(() -> GeneratedOutputVerifier.verify(generator));
            assertVectorUnchanged(sourceRoot, sourceVector);
            assertVectorUnchanged(resourceRoot, resourceVector);
        } finally {
            deleteTree(root);
        }
    }

    private static Map<String, Object> object(Map<String, Object> parent, String name) {
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) parent.get(name);
        assertNotNull(result, name);
        return result;
    }

    private static void writeVector(Path root, Map<String, Object> vector) throws IOException {
        Files.writeString(
            root.resolve(string(vector, "file")),
            string(vector, "contents"),
            StandardCharsets.UTF_8
        );
    }

    private static void assertVectorUnchanged(Path root, Map<String, Object> vector) throws IOException {
        org.junit.jupiter.api.Assertions.assertEquals(
            string(vector, "contents"),
            Files.readString(root.resolve(string(vector, "file")), StandardCharsets.UTF_8)
        );
    }

    private static String marker(String outputKind, Map<String, Object> vector) {
        return "{\n" +
            "  \"formatVersion\": 2,\n" +
            "  \"language\": \"java\",\n" +
            "  \"engineVersion\": \"0.9.0\",\n" +
            "  \"generator\": \"javaSdk\",\n" +
            "  \"outputKind\": \"" + outputKind + "\",\n" +
            "  \"generatorId\": \"0123456789abcdef\",\n" +
            "  \"packageName\": \"com.acme.orders\",\n" +
            "  \"schemaHash\": \"sha256:" + "a".repeat(64) + "\",\n" +
            "  \"runtimeVersion\": \"1.0.0\",\n" +
            "  \"descriptorVersion\": 2,\n" +
            "  \"clientName\": \"OrdersClient\",\n" +
            "  \"regenerateCommand\": \"./gradlew repostGenerate\",\n" +
            "  \"treeHash\": \"" + string(vector, "treeHash") + "\",\n" +
            "  \"files\": [\n" +
            "    \"" + string(vector, "file") + "\"\n" +
            "  ]\n" +
            "}\n";
    }

    private static String string(Map<String, Object> object, String name) {
        return (String) object.get(name);
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) return;
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).collect(Collectors.toList())) {
                Files.deleteIfExists(path);
            }
        }
    }
}
