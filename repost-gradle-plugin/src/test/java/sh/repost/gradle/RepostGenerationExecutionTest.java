package sh.repost.gradle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Base64;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.gradle.api.Project;
import org.gradle.api.GradleException;
import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.gradle.testkit.runner.TaskOutcome;
import org.gradle.testfixtures.ProjectBuilder;
import org.gradle.testfixtures.internal.ProjectBuilderImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sh.repost.buildplugin.internal.BuildPluginEngine;

final class RepostGenerationExecutionTest {
    @TempDir
    Path temporary;

    @Test
    void mapsTheLazyGradleTaskModelToOneClosedSharedEngineRequest() {
        File projectDirectory = temporary.resolve("orders-app").toFile();
        Project project = ProjectBuilder.builder()
            .withName("orders")
            .withProjectDir(projectDirectory)
            .build();
        project.setGroup("com.acme");
        project.getPlugins().apply(RepostSdkPlugin.class);

        RepostGenerateTask task = (RepostGenerateTask) project.getTasks().getByName("repostGenerate");
        task.getEngineExecutable().set(project.getLayout().getProjectDirectory().file("engine"));

        BuildPluginEngine.Request request = task.createEngineRequest();

        assertEquals("1.0.0", request.getPluginVersion());
        assertEquals("0.9.0", request.getEngineVersion());
        assertEquals("1.0.0", request.getRuntimeVersion());
        assertEquals(
            project.file("repost/schema.repost").toPath().toAbsolutePath().normalize().toString(),
            request.getSchemaPath()
        );
        assertEquals(Arrays.asList("javaSdk", "kotlinSdk"), request.getGeneratorNames());
        assertEquals(
            project.file("build/generated/sources/repost/javaSdk/java")
                .toPath().toAbsolutePath().normalize().toString(),
            request.getGenerators().get(0).getSourceOutputDirectory()
        );
        assertEquals(
            project.file("build/.repost-sdk-state/generate/control/javaSdk/source")
                .toPath().toAbsolutePath().normalize().toString(),
            request.getGenerators().get(0).getSourceControlDirectory()
        );
        assertEquals("GRADLE", request.getBuildKind());
        assertEquals("com.acme", request.getBuildGroup());
        assertEquals("orders", request.getBuildName());
        assertEquals(":", request.getBuildProjectPath());
        assertEquals("GENERATE", request.getCheckMode());
    }

    @Test
    void mapsCheckToIsolatedOutputsAndRejectsProtocolSkewBeforeLaunch() {
        Project project = ProjectBuilder.builder()
            .withName("orders")
            .withProjectDir(temporary.resolve("check-app").toFile())
            .build();
        project.getPlugins().apply(RepostSdkPlugin.class);
        RepostGenerateCheckTask task =
            (RepostGenerateCheckTask) project.getTasks().getByName("repostGenerateCheck");
        task.getEngineExecutable().set(project.getLayout().getProjectDirectory().file("engine"));

        BuildPluginEngine.Request request = task.createEngineRequest();
        assertEquals("CHECK", request.getCheckMode());
        assertEquals(
            project.file("build/.repost-sdk-check/sources/javaSdk/java")
                .toPath().toAbsolutePath().normalize().toString(),
            request.getGenerators().get(0).getSourceOutputDirectory()
        );

        task.getProtocolVersion().set(2);
        GradleException failure = assertThrows(GradleException.class, task::createEngineRequest);
        assertEquals("Repost generation protocol must equal 1", failure.getMessage());
    }

    @Test
    void aggregatesOnlyExactDependencyRegistriesAndDetectsDeclaredAdaptersLazily() throws IOException {
        Project project = ProjectBuilder.builder()
            .withName("orders-app")
            .withProjectDir(temporary.resolve("aggregate-app").toFile())
            .build();
        project.getPlugins().apply("java");
        project.getPlugins().apply(RepostSdkPlugin.class);

        Path dependency = registryDirectory(temporary.resolve("orders-clients"));
        project.getDependencies().add("implementation", project.files(dependency.toFile()));
        project.getDependencies().add(
            "implementation",
            "sh.repost:repost-client-spring-boot-starter:1.0.0"
        );
        RepostSdkExtension extension = project.getExtensions().getByType(RepostSdkExtension.class);
        extension.getSchemaMode().set(RepostSchemaMode.AGGREGATE_ONLY);

        RepostGenerateTask task = (RepostGenerateTask) project.getTasks().getByName("repostGenerate");
        assertTrue(task.getEngineArtifacts().isEmpty());
        assertEquals(
            Set.of("sh.repost:repost-client-spring-boot-starter"),
            Set.copyOf(task.getDeclaredAdapterCoordinates().get())
        );
        assertEquals(RepostIntegration.SPRING_BOOT, task.resolveIntegration());
        extension.getIntegration().set(RepostIntegration.CDI);
        GradleException mismatch = assertThrows(GradleException.class, task::resolveIntegration);
        assertEquals(
            "integration CDI requires its matching declared Repost adapter dependency",
            mismatch.getMessage()
        );
        extension.getIntegration().set(RepostIntegration.SPRING_BOOT);
        assertEquals(RepostIntegration.SPRING_BOOT, task.resolveIntegration());

        Path sourceRoot = task.getAggregateSourceOutputDirectory().get().getAsFile().toPath();
        Path resourceRoot = task.getAggregateResourceOutputDirectory().get().getAsFile().toPath();
        try {
            task.executeGeneration();
        } finally {
            ProjectBuilderImpl.stop(project);
        }

        Path source;
        try (java.util.stream.Stream<Path> paths = Files.walk(sourceRoot)) {
            source = paths
                .filter(path -> path.getFileName().toString().equals("RepostGeneratedClientRegistry.java"))
                .findFirst()
                .orElseThrow();
        }
        Path resource;
        try (java.util.stream.Stream<Path> paths = Files.walk(resourceRoot)) {
            resource = paths
                .filter(path -> path.getFileName().toString().equals("registry.json"))
                .findFirst()
                .orElseThrow();
        }
        String registrySource = Files.readString(source);
        assertTrue(registrySource.contains("com.acme.orders.OrdersClientFactory.INSTANCE"));
        assertTrue(registrySource.contains("FACTORIES = createFactories()"));
        assertTrue(Files.readString(resource).contains("\"registryType\": \"application\""));
        try (java.util.stream.Stream<Path> paths = Files.walk(sourceRoot)) {
            assertTrue(paths.anyMatch(path ->
                path.getFileName().toString().equals("RepostGeneratedClientAutoConfiguration.java")
            ));
        }
        assertTrue(Files.isRegularFile(resourceRoot.resolve(
            "META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports"
        )));
    }

    @Test
    void relocatesCachedGeneratedSourcesAndResourcesWithoutEmbeddingHostMetadata() throws IOException {
        Path tempBase = Path.of(System.getenv().getOrDefault(
            "REPOST_TEST_TMPDIR",
            System.getProperty("java.io.tmpdir")
        ));
        Path relocationRoot = Files.createTempDirectory(tempBase, "repost-gradle-relocation-")
            .toAbsolutePath();
        try {
            Path first = relocationRoot.resolve("first-absolute-project");
            Path second = relocationRoot.resolve("second-absolute-project");
            Path gradleHome = relocationRoot.resolve("gradle-home");
            writeRelocationProject(first);
            writeRelocationProject(second);
            Path firstRegistry = registryJar(first.resolve("libs/clients.jar"));
            Files.copy(
                firstRegistry,
                second.resolve("libs/clients.jar"),
                StandardCopyOption.REPLACE_EXISTING
            );

            BuildResult initial = relocationBuild(first, gradleHome);
            BuildResult relocated = relocationBuild(second, gradleHome);

            assertEquals(TaskOutcome.SUCCESS, initial.task(":repostGenerate").getOutcome());
            assertEquals(TaskOutcome.FROM_CACHE, relocated.task(":repostGenerate").getOutcome());
            Map<String, String> firstTree = generatedTree(first);
            Map<String, String> secondTree = generatedTree(second);
            assertFalse(firstTree.isEmpty());
            assertEquals(firstTree, secondTree);

            String generated = generatedText(second);
            for (String forbidden : Arrays.asList(
                first.toString(),
                second.toString(),
                relocationRoot.toString(),
                System.getProperty("user.name", ""),
                tempBase.toAbsolutePath().toString()
            )) {
                if (!forbidden.isEmpty()) {
                    assertFalse(generated.contains(forbidden), () -> "generated output embeds " + forbidden);
                }
            }
            assertFalse(
                generated.matches("(?s).*\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}.*"),
                "generated output embeds a timestamp"
            );
        } finally {
            deleteTree(relocationRoot);
        }
    }

    private static void writeRelocationProject(Path project) throws IOException {
        Files.createDirectories(project.resolve("libs"));
        Files.writeString(
            project.resolve("settings.gradle"),
            "rootProject.name = 'relocation-fixture'\n",
            StandardCharsets.UTF_8
        );
        Files.writeString(
            project.resolve("build.gradle"),
            "plugins {\n" +
                "    id 'java'\n" +
                "    id 'sh.repost.sdk'\n" +
                "}\n" +
                "group = 'com.acme'\n" +
                "dependencies { implementation files('libs/clients.jar') }\n" +
                "repostSdk { schemaMode = sh.repost.gradle.RepostSchemaMode.AGGREGATE_ONLY }\n",
            StandardCharsets.UTF_8
        );
    }

    private static BuildResult relocationBuild(Path project, Path gradleHome) {
        return GradleTestVersions.apply(GradleRunner.create()
            .withProjectDir(project.toFile())
            .withPluginClasspath()
            .withEnvironment(relocationEnvironment(gradleHome))
            .withArguments("--build-cache", "--offline", "--stacktrace", "repostGenerate"))
            .build();
    }

    private static Map<String, String> relocationEnvironment(Path gradleHome) {
        Map<String, String> environment = new HashMap<>(System.getenv());
        environment.put("GRADLE_USER_HOME", gradleHome.toString());
        environment.put("JAVA_HOME", System.getProperty("java.home"));
        return environment;
    }

    private static Map<String, String> generatedTree(Path project) throws IOException {
        Path root = project.resolve("build/generated");
        Map<String, String> tree = new TreeMap<>();
        try (java.util.stream.Stream<Path> paths = Files.walk(root)) {
            paths.filter(Files::isRegularFile).forEach(path -> {
                try {
                    tree.put(
                        root.relativize(path).toString().replace('\\', '/'),
                        Base64.getEncoder().encodeToString(Files.readAllBytes(path))
                    );
                } catch (IOException exception) {
                    throw new java.io.UncheckedIOException(exception);
                }
            });
        } catch (java.io.UncheckedIOException exception) {
            throw exception.getCause();
        }
        return tree;
    }

    private static String generatedText(Path project) throws IOException {
        StringBuilder text = new StringBuilder();
        Path root = project.resolve("build/generated");
        try (java.util.stream.Stream<Path> paths = Files.walk(root)) {
            for (Path path : paths.filter(Files::isRegularFile).sorted()
                .collect(java.util.stream.Collectors.toList())) {
                text.append(Files.readString(path, StandardCharsets.UTF_8));
            }
        }
        return text.toString();
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (java.util.stream.Stream<Path> paths = Files.walk(root)) {
            for (Path path : paths.sorted(java.util.Comparator.reverseOrder())
                .collect(java.util.stream.Collectors.toList())) {
                Files.delete(path);
            }
        }
    }

    private static Path registryDirectory(Path path) throws IOException {
        String registry = registryJson();
        Path manifest = path.resolve("META-INF/repost/registries/v1/1111111111111111/registry.json");
        Files.createDirectories(manifest.getParent());
        Files.writeString(manifest, registry, StandardCharsets.UTF_8);
        Path unrelated = path.resolve("com/acme/orders/Unrelated.class");
        Files.createDirectories(unrelated.getParent());
        Files.write(unrelated, new byte[] {0, 1, 2});
        return path;
    }

    private static Path registryJar(Path path) throws IOException {
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(path))) {
            output.putNextEntry(new JarEntry(
                "META-INF/repost/registries/v1/1111111111111111/registry.json"
            ));
            output.write(registryJson().getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
            output.putNextEntry(new JarEntry("com/acme/orders/Unrelated.class"));
            output.write(new byte[] {0, 1, 2});
            output.closeEntry();
        }
        return path;
    }

    private static String registryJson() {
        return "{\n" +
            "  \"formatVersion\": 1,\n" +
            "  \"registryId\": \"1111111111111111\",\n" +
            "  \"registryType\": \"module\",\n" +
            "  \"clients\": [\n" +
            "    {\n" +
            "      \"formatVersion\": 1,\n" +
            "      \"generatorId\": \"0123456789abcdef\",\n" +
            "      \"language\": \"java\",\n" +
            "      \"packageName\": \"com.acme.orders\",\n" +
            "      \"clientType\": \"com.acme.orders.OrdersClient\",\n" +
            "      \"factoryType\": \"com.acme.orders.OrdersClientFactory\",\n" +
            "      \"schemaHash\": \"sha256:" + "a".repeat(64) + "\",\n" +
            "      \"descriptorVersion\": 2,\n" +
            "      \"runtimeVersion\": \"1.0.0\"\n" +
            "    }\n" +
            "  ]\n" +
            "}\n";
    }
}
