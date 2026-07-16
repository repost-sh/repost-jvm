package sh.repost.buildplugin.internal;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class GeneratedOutputVerifierParityTest {
    @TempDir
    Path temporary;

    @Test
    void acceptsTheRustCanonicalU64BigEndianTreeHashFraming() throws Exception {
        Path source = temporary.resolve("source");
        Path resource = temporary.resolve("resource");
        Path sourcePackage = Files.createDirectories(source.resolve("com/acme"));
        Path resourcePackage = Files.createDirectories(resource.resolve(
            "META-INF/repost/generated-clients/v1/0123456789abcdef"));
        Files.writeString(sourcePackage.resolve("Client.java"), "client\n");
        Files.writeString(resourcePackage.resolve("client.json"), "{}\n");
        Files.writeString(sourcePackage.resolve(".repost-client.json"), marker(
            "source", "577a164a04e9dc82975d89b3874d50411f0e107413e3ec50f916860958c1a941",
            "Client.java"));
        Files.writeString(resourcePackage.resolve(".repost-client.json"), marker(
            "registry", "9b93543a07d64d68528711f24177694db2ea768ce017a805d24fc790244084b3",
            "client.json"));
        BuildPluginEngine.Generator generator = new BuildPluginEngine.Generator(
            "javaSdk", source.toString(), resource.toString(),
            temporary.resolve("source-control").toString(),
            temporary.resolve("resource-control").toString()
        );

        GeneratedOutputVerifier.verify(generator);
    }

    private static String marker(String outputKind, String treeHash, String file) {
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
            + "  \"files\": [\"" + file + "\"]\n"
            + "}\n";
    }
}
