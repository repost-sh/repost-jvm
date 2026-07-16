package sh.repost.buildplugin.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class BuildPluginRegistryTransactionTest {
    @TempDir
    Path temporary;

    @Test
    void missingDependencyRegistryExplainsHowToFixAggregateOnlyMode() {
        BuildPluginRegistry.Request request = new BuildPluginRegistry.Request(
            List.of(), Set.of(), Map.of(), temporary.resolve("source"), temporary.resolve("resource"),
            "com.acme", "orders", "", true
        );

        IllegalArgumentException failure = assertThrows(
            IllegalArgumentException.class,
            () -> BuildPluginRegistry.aggregate(request)
        );

        assertEquals(
            "Repost AGGREGATE_ONLY requires at least one dependency registry resource; "
                + "add a dependency on a generated Repost client module or set schemaMode=GENERATE",
            failure.getMessage()
        );
    }

    @Test
    void failurePublishingSecondRootRollsBothRootsBackToAllOld() throws Exception {
        Path dependency = dependencyRegistry(temporary.resolve("dependency"));
        Path source = Files.createDirectories(temporary.resolve("source"));
        Path resource = Files.createDirectories(temporary.resolve("resource"));
        Files.writeString(source.resolve("old-source.txt"), "old-source\n");
        Files.writeString(resource.resolve("old-resource.txt"), "old-resource\n");
        BuildPluginRegistry.Request request = new BuildPluginRegistry.Request(
            List.of(), Set.of(dependency), Map.of(), source, resource,
            "com.acme", "orders", "", true
        );
        AtomicInteger moves = new AtomicInteger();

        assertThrows(IllegalArgumentException.class, () ->
            BuildPluginRegistry.aggregate(request, (from, to) -> {
                if (moves.incrementAndGet() == 4) {
                    throw new IOException("deterministic second-root publication failure");
                }
                Files.move(from, to, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            })
        );

        assertEquals("old-source\n", Files.readString(source.resolve("old-source.txt")));
        assertEquals("old-resource\n", Files.readString(resource.resolve("old-resource.txt")));
        assertFalse(Files.exists(source.resolve("sh/repost/generated")));
        assertFalse(Files.exists(resource.resolve("META-INF/repost/registries")));
        assertFalse(Files.exists(temporary.resolve("source.repost-stage")));
        assertFalse(Files.exists(temporary.resolve("resource.repost-stage")));
    }

    @Test
    void nextExecutionRecoversAProcessCrashThatLeftMixedRoots() throws Exception {
        Path dependency = dependencyRegistry(temporary.resolve("crash-dependency"));
        Path source = Files.createDirectories(temporary.resolve("crash-source"));
        Path resource = Files.createDirectories(temporary.resolve("crash-resource"));
        Files.writeString(source.resolve("partial-new.txt"), "partial-new\n");
        Files.writeString(resource.resolve("old-resource.txt"), "old-resource\n");
        Path sourceBackup = Files.createDirectories(temporary.resolve("crash-source.repost-backup"));
        Files.writeString(sourceBackup.resolve("old-source.txt"), "old-source\n");
        Files.writeString(
            temporary.resolve("crash-source.repost-transaction"),
            "format=1\nsourceOld=true\nresourceOld=true\n"
        );
        BuildPluginRegistry.Request request = new BuildPluginRegistry.Request(
            List.of(), Set.of(dependency), Map.of(), source, resource,
            "com.acme", "orders", "", true
        );

        assertThrows(IllegalArgumentException.class, () ->
            BuildPluginRegistry.aggregate(request, (from, to) -> {
                throw new IOException("stop immediately after crash recovery");
            })
        );

        assertEquals("old-source\n", Files.readString(source.resolve("old-source.txt")));
        assertEquals("old-resource\n", Files.readString(resource.resolve("old-resource.txt")));
        assertFalse(Files.exists(source.resolve("partial-new.txt")));
        assertFalse(Files.exists(temporary.resolve("crash-source.repost-transaction")));
    }

    private static Path dependencyRegistry(Path root) throws Exception {
        Path manifest = root.resolve(
            "META-INF/repost/registries/v1/1111111111111111/registry.json");
        Files.createDirectories(manifest.getParent());
        Files.writeString(manifest, "{\n"
            + "  \"formatVersion\": 1,\n"
            + "  \"registryId\": \"1111111111111111\",\n"
            + "  \"registryType\": \"module\",\n"
            + "  \"clients\": [\n"
            + "    {\n"
            + "      \"formatVersion\": 1,\n"
            + "      \"generatorId\": \"0123456789abcdef\",\n"
            + "      \"language\": \"java\",\n"
            + "      \"packageName\": \"com.acme.orders\",\n"
            + "      \"clientType\": \"com.acme.orders.OrdersClient\",\n"
            + "      \"factoryType\": \"com.acme.orders.OrdersClientFactory\",\n"
            + "      \"schemaHash\": \"sha256:" + "a".repeat(64) + "\",\n"
            + "      \"descriptorVersion\": 2,\n"
            + "      \"runtimeVersion\": \"1.0.0\"\n"
            + "    }\n"
            + "  ]\n"
            + "}\n");
        return root;
    }
}
