package sh.repost.buildplugin.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class BuildPluginFrameworkGlueTest {
    @TempDir
    Path temporary;

    @Test
    void springBootRendersDirectReferenceAutoConfigurationBesideNeutralRegistry() throws Exception {
        Path source = temporary.resolve("spring-source");
        Path resource = temporary.resolve("spring-resource");

        BuildPluginRegistry.aggregate(request(source, resource, "SPRING_BOOT"));

        Path generatedPackage = onlyGeneratedPackage(source);
        String registry = Files.readString(generatedPackage.resolve("RepostGeneratedClientRegistry.java"));
        String configuration = Files.readString(
            generatedPackage.resolve("RepostGeneratedClientAutoConfiguration.java"));
        String configurationType = packageName(generatedPackage)
            + ".RepostGeneratedClientAutoConfiguration";
        assertFalse(registry.contains("org.springframework"));
        assertFalse(registry.contains("jakarta.enterprise"));
        assertTrue(configuration.contains("@AutoConfiguration"));
        assertTrue(configuration.contains(
            "@ConditionalOnMissingBean(com.acme.orders.OrdersClient.class)"));
        assertTrue(configuration.contains(
            "return com.acme.orders.OrdersClientFactory.INSTANCE.create(runtime);"));
        assertTrue(configuration.contains(
            "public RepostGeneratedClientRegistry repostGeneratedClientRegistry()"));
        assertFalse(configuration.contains("Class.forName"));
        assertFalse(configuration.contains("ServiceLoader"));
        assertEquals(
            configurationType + "\n",
            Files.readString(resource.resolve(
                "META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports"))
        );
    }

    @Test
    void cdiRendersOneDirectReferenceCreatorPerClientAndBuildCompatibleExtensionService()
        throws Exception {
        Path source = temporary.resolve("cdi-source");
        Path resource = temporary.resolve("cdi-resource");

        BuildPluginRegistry.aggregate(request(source, resource, "CDI"));

        Path generatedPackage = onlyGeneratedPackage(source);
        String extension = Files.readString(
            generatedPackage.resolve("RepostGeneratedClientBuildCompatibleExtension.java"));
        String creator = Files.readString(generatedPackage.resolve("OrdersClientCdiCreator.java"));
        String extensionType = packageName(generatedPackage)
            + ".RepostGeneratedClientBuildCompatibleExtension";
        assertTrue(extension.contains("implements BuildCompatibleExtension"));
        assertTrue(extension.contains("@Synthesis"));
        assertTrue(extension.contains("components.addBean(com.acme.orders.OrdersClient.class)"));
        assertTrue(extension.contains(".type(com.acme.orders.OrdersClient.class)"));
        assertTrue(extension.contains(".scope(jakarta.inject.Singleton.class)"));
        assertTrue(extension.contains(".createWith(OrdersClientCdiCreator.class);"));
        assertTrue(creator.contains(
            "public final class OrdersClientCdiCreator implements SyntheticBeanCreator<com.acme.orders.OrdersClient>"));
        assertTrue(creator.contains("public OrdersClientCdiCreator() {}"));
        assertTrue(creator.contains(
            "RepostRuntime runtime = lookup.select(RepostRuntime.class).get();"));
        assertTrue(creator.contains(
            "return com.acme.orders.OrdersClientFactory.INSTANCE.create(runtime);"));
        assertFalse(creator.contains("@ApplicationScoped"));
        assertFalse(creator.contains("@Dependent"));
        assertFalse(extension.contains("Class.forName"));
        assertEquals(
            extensionType + "\n",
            Files.readString(resource.resolve(
                "META-INF/services/jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension"))
        );
    }

    private BuildPluginRegistry.Request request(Path source, Path resource, String integration)
        throws Exception {
        return new BuildPluginRegistry.Request(
            List.of(), Set.of(dependencyRegistry()), Map.of(), source, resource,
            "com.acme", "orders", "", true, integration
        );
    }

    private Path dependencyRegistry() throws Exception {
        Path root = temporary.resolve("dependency");
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

    private static Path onlyGeneratedPackage(Path source) throws Exception {
        try (java.util.stream.Stream<Path> directories = Files.list(
            source.resolve("sh/repost/generated"))) {
            return directories.findFirst().orElseThrow();
        }
    }

    private static String packageName(Path generatedPackage) {
        return "sh.repost.generated." + generatedPackage.getFileName();
    }
}
