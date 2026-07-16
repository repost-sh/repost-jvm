package sh.repost.maven;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sh.repost.buildplugin.internal.BuildPluginEngine;

final class MavenIncrementalStateTest {
    @TempDir
    Path temporary;

    @Test
    void hashOnlyStateVerifiesMarkersAndPreservesOutputMtimesOnReuse() throws Exception {
        Path schema = Files.writeString(temporary.resolve("schema.repost"), "model Empty {}\n");
        Path source = temporary.resolve("source");
        Path resource = temporary.resolve("resource");
        writeVerifiedOutput(source, resource);
        Path aggregateSource = Files.createDirectories(temporary.resolve("aggregate-source"));
        Path aggregateResource = Files.createDirectories(temporary.resolve("aggregate-resource"));
        Files.writeString(aggregateSource.resolve("Registry.java"), "registry\n");
        Files.writeString(aggregateResource.resolve("registry.json"), "{}\n");
        Path dependency = temporary.resolve("dependency");
        Path dependencyRegistry = dependency.resolve(
            "META-INF/repost/registries/v1/1111111111111111/registry.json");
        Files.createDirectories(dependencyRegistry.getParent());
        Files.writeString(dependencyRegistry, "dependency-registry\n");
        BuildPluginEngine.Generator generator = new BuildPluginEngine.Generator(
            "javaSdk", source.toString(), resource.toString(),
            temporary.resolve("control-source").toString(),
            temporary.resolve("control-resource").toString()
        );
        BuildPluginEngine.Request first = request(schema, generator, "sentinel-secret-one");
        BuildPluginEngine.Request second = request(schema, generator, "sentinel-secret-two");
        String firstFingerprint = MavenIncrementalState.fingerprint(
            first, "GENERATE", "NONE", Set.of(dependency));
        String secondFingerprint = MavenIncrementalState.fingerprint(
            second, "GENERATE", "NONE", Set.of(dependency));
        assertNotEquals(firstFingerprint, secondFingerprint);
        assertFalse(firstFingerprint.contains("sentinel-secret"));
        Path state = temporary.resolve("state");
        MavenIncrementalState.store(
            state, firstFingerprint, List.of(generator), aggregateSource, aggregateResource);
        String persisted = Files.readString(state.resolve("incremental-v1.state"));
        assertFalse(persisted.contains("sentinel-secret"));
        assertFalse(persisted.contains("TOKEN_ALIAS"));
        FileTime sourceTime = Files.getLastModifiedTime(source.resolve("com/acme/Client.java"));
        FileTime registryTime = Files.getLastModifiedTime(aggregateSource.resolve("Registry.java"));

        assertTrue(MavenIncrementalState.canReuse(
            state, firstFingerprint, List.of(generator), aggregateSource, aggregateResource));
        assertEquals(sourceTime, Files.getLastModifiedTime(source.resolve("com/acme/Client.java")));
        assertEquals(registryTime, Files.getLastModifiedTime(aggregateSource.resolve("Registry.java")));

        Files.writeString(source.resolve("com/acme/Client.java"), "tampered\n");
        assertFalse(MavenIncrementalState.canReuse(
            state, firstFingerprint, List.of(generator), aggregateSource, aggregateResource));
    }

    private BuildPluginEngine.Request request(
        Path schema,
        BuildPluginEngine.Generator generator,
        String environmentValue
    ) {
        return new BuildPluginEngine.Request(
            temporary.resolve("engine"),
            "1.0.0",
            "0.9.0",
            "1.0.0",
            schema.toString(),
            List.of(generator),
            Map.of("TOKEN_ALIAS", environmentValue),
            BuildPluginEngine.BuildIdentity.maven("com.acme", "orders"),
            false,
            temporary.resolve("requests"),
            Duration.ofMinutes(5)
        );
    }

    private static void writeVerifiedOutput(Path source, Path resource) throws Exception {
        Path sourcePackage = Files.createDirectories(source.resolve("com/acme"));
        byte[] sourceBytes = "client\n".getBytes(StandardCharsets.UTF_8);
        Files.write(sourcePackage.resolve("Client.java"), sourceBytes);
        Path resourcePackage = Files.createDirectories(resource.resolve(
            "META-INF/repost/generated-clients/v1/0123456789abcdef"));
        byte[] resourceBytes = "{}\n".getBytes(StandardCharsets.UTF_8);
        Files.write(resourcePackage.resolve("client.json"), resourceBytes);
        Files.writeString(sourcePackage.resolve(".repost-client.json"), marker(
            "source", treeHash("Client.java", sourceBytes)));
        Files.writeString(resourcePackage.resolve(".repost-client.json"), marker(
            "registry", treeHash("client.json", resourceBytes)));
    }

    private static String marker(String outputKind, String treeHash) {
        return "{\n"
            + "  \"formatVersion\": 2,\n"
            + "  \"language\": \"java\",\n"
            + "  \"engineVersion\": \"0.9.0\",\n"
            + "  \"generator\": \"javaSdk\",\n"
            + "  \"outputKind\": \"" + outputKind + "\",\n"
            + "  \"generatorId\": \"0123456789abcdef\",\n"
            + "  \"packageName\": \"com.acme\",\n"
            + "  \"schemaHash\": \"sha256:" + "a".repeat(64) + "\",\n"
            + "  \"runtimeVersion\": \"1.0.0\",\n"
            + "  \"descriptorVersion\": 2,\n"
            + "  \"clientName\": \"Client\",\n"
            + "  \"regenerateCommand\": \"mvn repost:generate\",\n"
            + "  \"treeHash\": \"sha256:" + treeHash + "\",\n"
            + "  \"files\": [\"" + ("source".equals(outputKind) ? "Client.java" : "client.json") + "\"]\n"
            + "}\n";
    }

    private static String treeHash(String name, byte[] bytes) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] nameBytes = name.getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(8).putLong(nameBytes.length).array());
        digest.update(nameBytes);
        digest.update(ByteBuffer.allocate(8).putLong(bytes.length).array());
        digest.update(bytes);
        StringBuilder output = new StringBuilder();
        for (byte value : digest.digest()) output.append(String.format("%02x", value & 0xff));
        return output.toString();
    }
}
