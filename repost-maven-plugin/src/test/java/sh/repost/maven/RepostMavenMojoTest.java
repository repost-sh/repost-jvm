package sh.repost.maven;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.maven.model.Build;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.Model;
import org.apache.maven.project.DefaultProjectBuildingRequest;
import org.apache.maven.project.MavenProject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sh.repost.buildplugin.internal.BuildPluginEngine;

final class RepostMavenMojoTest {
    @TempDir
    Path temporary;

    @Test
    void generateRejectsJavaEightBeforeLaunchingTheEngine() throws Exception {
        MavenProject project = project();
        project.getProperties().setProperty("maven.compiler.release", "8");
        RepostGenerateMojo mojo = new RepostGenerateMojo();
        mojo.setProject(project);

        org.apache.maven.plugin.MojoExecutionException failure = assertThrows(
            org.apache.maven.plugin.MojoExecutionException.class,
            mojo::execute
        );

        assertEquals(
            "Repost generated Java requires Java 11 or newer; "
                + "set <maven.compiler.release>11</maven.compiler.release>",
            failure.getMessage()
        );
        assertFalse(Files.exists(project.getBasedir().toPath().resolve("target")));
    }

    @Test
    void kotlinGenerationRejectsEffectiveCompilerBaselineBelowTwoOne() throws Exception {
        MavenProject project = project();
        java.util.Properties userProperties = new java.util.Properties();
        userProperties.setProperty("kotlin.version", "2.0.21");
        userProperties.setProperty("kotlin.compiler.languageVersion", "2.0");
        userProperties.setProperty("kotlin.compiler.apiVersion", "2.0");
        project.setProjectBuildingRequest(
            new DefaultProjectBuildingRequest().setUserProperties(userProperties)
        );
        RepostGenerateMojo mojo = new RepostGenerateMojo();
        mojo.setProject(project);

        org.apache.maven.plugin.MojoExecutionException failure = assertThrows(
            org.apache.maven.plugin.MojoExecutionException.class,
            mojo::execute
        );

        assertTrue(failure.getMessage().contains("Kotlin 2.1"));
        assertTrue(failure.getMessage().contains(
            "<kotlin.compiler.languageVersion>2.1</kotlin.compiler.languageVersion>"
        ));
        assertFalse(Files.exists(project.getBasedir().toPath().resolve("target")));
    }

    @Test
    void generateUsesTheSharedRequestAndRegistersOnlyGeneratedRootsAfterSuccess() throws Exception {
        MavenProject project = project();
        int dependencyCount = project.getDependencies().size();
        RepostGenerateMojo mojo = new RepostGenerateMojo();
        AtomicReference<BuildPluginEngine.Request> captured = new AtomicReference<>();
        mojo.setProject(project);
        mojo.setEngineExecutable(Files.createFile(temporary.resolve("engine")));
        mojo.setEngineInvoker(captured::set);

        mojo.execute();

        BuildPluginEngine.Request request = captured.get();
        assertEquals("MAVEN", request.getBuildKind());
        assertEquals("com.acme", request.getBuildGroup());
        assertEquals("orders", request.getBuildName());
        assertEquals("GENERATE", request.getCheckMode());
        assertEquals(java.util.Arrays.asList("javaSdk", "kotlinSdk"), request.getGeneratorNames());
        assertEquals(3, project.getCompileSourceRoots().size());
        assertTrue(project.getCompileSourceRoots().get(0).endsWith(
            "target/generated-sources/repost/cfc20961be395d84/java"));
        assertTrue(project.getCompileSourceRoots().get(2).endsWith("target/generated-sources/repost-registry"));
        assertEquals(3, project.getResources().size());
        assertTrue(project.getResources().get(0).getDirectory().endsWith(
            "target/generated-resources/repost/cfc20961be395d84"));
        assertTrue(project.getResources().get(2).getDirectory().endsWith("target/generated-resources/repost-registry"));
        assertEquals(dependencyCount, project.getDependencies().size());
        assertTrue(Files.isRegularFile(project.getBasedir().toPath().resolve(
            "target/generated-sources/repost/.repost-output-root.json")));
        assertTrue(Files.isRegularFile(project.getBasedir().toPath().resolve(
            "target/generated-resources/repost/.repost-output-root.json")));
        assertFalse(project.getResources().stream().anyMatch(resource ->
            resource.getDirectory().contains("repost-state")));
    }

    @Test
    void generateMarksAndRegistersPerGeneratorOutputOverrides() throws Exception {
        MavenProject project = project();
        RepostGenerateMojo mojo = new RepostGenerateMojo();
        Path customSource = project.getBasedir().toPath().resolve("generated/custom-java");
        Path customResource = project.getBasedir().toPath().resolve("generated/custom-java-resources");
        mojo.setProject(project);
        mojo.setOutputDirectories(
            Map.of("javaSdk", customSource.toString()),
            Map.of("javaSdk", customResource.toString())
        );
        mojo.setEngineExecutable(Files.createFile(temporary.resolve("override-engine")));
        mojo.setEngineInvoker(request -> { });

        mojo.execute();

        assertEquals(customSource.toString(), project.getCompileSourceRoots().get(0));
        assertEquals(customResource.toString(), project.getResources().get(0).getDirectory());
        assertTrue(Files.isRegularFile(customSource.resolve(".repost-output-root.json")));
        assertTrue(Files.isRegularFile(customResource.resolve(".repost-output-root.json")));
        String defaultSourcesMarker = Files.readString(project.getBasedir().toPath().resolve(
            "target/generated-sources/repost/.repost-output-root.json"));
        assertFalse(defaultSourcesMarker.contains("cfc20961be395d84"));
        assertTrue(defaultSourcesMarker.contains("c756cf657644d412"));
    }

    @Test
    void generatePrunesPreviouslyManagedGeneratorChildrenAndUpdatesTheMarker() throws Exception {
        MavenProject project = project();
        RepostGenerateMojo first = new RepostGenerateMojo();
        first.setProject(project);
        first.setEngineExecutable(Files.createFile(temporary.resolve("first-managed-engine")));
        first.setEngineInvoker(request -> {
            try {
                for (BuildPluginEngine.Generator generator : request.getGenerators()) {
                    Path source = Path.of(generator.getSourceOutputDirectory());
                    Path resource = Path.of(generator.getResourceOutputDirectory());
                    Files.createDirectories(source);
                    Files.createDirectories(resource);
                    Files.writeString(source.resolve("old.txt"), "old\n");
                    Files.writeString(resource.resolve("old.txt"), "old\n");
                }
            } catch (java.io.IOException exception) {
                throw new AssertionError(exception);
            }
        });
        first.execute();
        Path staleSource = project.getBasedir().toPath().resolve(
            "target/generated-sources/repost/c756cf657644d412");
        Path staleResource = project.getBasedir().toPath().resolve(
            "target/generated-resources/repost/c756cf657644d412");
        assertTrue(Files.exists(staleSource));
        assertTrue(Files.exists(staleResource));
        RepostGenerateMojo second = new RepostGenerateMojo();
        second.setProject(project);
        second.setGenerators(List.of("javaSdk"));
        second.setEngineExecutable(Files.createFile(temporary.resolve("second-managed-engine")));
        second.setEngineInvoker(request -> { });

        second.execute();

        assertFalse(Files.exists(staleSource));
        assertFalse(Files.exists(staleResource));
        String marker = Files.readString(project.getBasedir().toPath().resolve(
            "target/generated-sources/repost/.repost-output-root.json"));
        assertTrue(marker.contains("cfc20961be395d84"));
        assertFalse(marker.contains("c756cf657644d412"));
    }

    @Test
    void checkUsesIsolatedRootsAndNeverRegistersThemOnTheProject() throws Exception {
        MavenProject project = project();
        RepostCheckMojo mojo = new RepostCheckMojo();
        AtomicReference<BuildPluginEngine.Request> captured = new AtomicReference<>();
        mojo.setProject(project);
        mojo.setEngineExecutable(Files.createFile(temporary.resolve("check-engine")));
        mojo.setEngineInvoker(captured::set);

        mojo.execute();

        BuildPluginEngine.Request request = captured.get();
        assertEquals("CHECK", request.getCheckMode());
        assertTrue(request.getGenerators().get(0).getSourceOutputDirectory()
            .endsWith("target/.repost-sdk-check/sources/cfc20961be395d84/java"));
        assertTrue(project.getCompileSourceRoots().isEmpty());
        assertTrue(project.getResources().isEmpty());
    }

    @Test
    void checkAgainstReportsSortedChangesWithoutMutatingTheCheckedInTree() throws Exception {
        MavenProject project = project();
        Path checkedIn = Files.createDirectories(temporary.resolve("checked-in"));
        Files.writeString(checkedIn.resolve(".repost-output-root.json"),
            "{\n  \"formatVersion\": 1\n}\n");
        Path expectedFile = checkedIn.resolve("sources/cfc20961be395d84/java/Client.java");
        Files.createDirectories(expectedFile.getParent());
        Files.writeString(expectedFile, "old\n");
        Path deletedFile = checkedIn.resolve("deleted.txt");
        Files.writeString(deletedFile, "old\n");
        java.nio.file.attribute.FileTime markerTime = Files.getLastModifiedTime(
            checkedIn.resolve(".repost-output-root.json"));
        RepostCheckMojo mojo = new RepostCheckMojo();
        mojo.setProject(project);
        mojo.setCheckAgainst(checkedIn);
        mojo.setEngineExecutable(Files.createFile(temporary.resolve("compare-engine")));
        mojo.setEngineInvoker(request -> {
            try {
                Path actual = Path.of(request.getGenerators().get(0).getSourceOutputDirectory())
                    .resolve("Client.java");
                Files.createDirectories(actual.getParent());
                Files.writeString(actual, "new\n");
                Path added = Path.of(request.getGenerators().get(1).getResourceOutputDirectory())
                    .resolve("added.txt");
                Files.createDirectories(added.getParent());
                Files.writeString(added, "new\n");
            } catch (java.io.IOException exception) {
                throw new AssertionError(exception);
            }
        });

        org.apache.maven.plugin.MojoExecutionException failure = assertThrows(
            org.apache.maven.plugin.MojoExecutionException.class,
            mojo::execute
        );

        assertTrue(failure.getMessage().contains(
            "added: [resources/c756cf657644d412/added.txt]"));
        assertTrue(failure.getMessage().contains(
            "changed: [sources/cfc20961be395d84/java/Client.java]"));
        assertTrue(failure.getMessage().contains("deleted: [deleted.txt]"));
        assertEquals("old\n", Files.readString(expectedFile));
        assertEquals(markerTime, Files.getLastModifiedTime(
            checkedIn.resolve(".repost-output-root.json")));
    }

    @Test
    void generateResolvesThePinnedEngineBeforeCreatingTheSharedRequest() throws Exception {
        MavenProject project = project();
        RepostGenerateMojo mojo = new RepostGenerateMojo();
        Path engine = Files.createFile(temporary.resolve("resolved-engine"));
        AtomicReference<String> requestedVersion = new AtomicReference<>();
        AtomicReference<BuildPluginEngine.Request> captured = new AtomicReference<>();
        mojo.setProject(project);
        mojo.setEngineResolver(version -> {
            requestedVersion.set(version);
            return engine;
        });
        mojo.setEngineInvoker(captured::set);

        mojo.execute();

        assertEquals("0.9.0", requestedVersion.get());
        assertEquals("GENERATE", captured.get().getCheckMode());
    }

    @Test
    void aggregateOnlyValidatesRuntimeAndEmitsAnApplicationRegistryWithoutLaunchingTheEngine()
        throws Exception {
        MavenProject project = project();
        RepostGenerateMojo mojo = new RepostGenerateMojo();
        Path clients = registryJar(temporary.resolve("clients.jar"));
        Map<String, Path> artifacts = new LinkedHashMap<>();
        artifacts.put("sh.repost:repost-client:1.0.0", emptyJar(temporary.resolve("core.jar")));
        artifacts.put("com.acme:orders-clients:1.0.0", clients);
        mojo.setProject(project);
        mojo.setSchemaMode("AGGREGATE_ONLY");
        mojo.setCompileArtifacts(artifacts, Set.of("sh.repost:repost-client"));
        mojo.setEngineInvoker(request -> {
            throw new AssertionError("aggregate-only mode must not launch the engine");
        });

        mojo.execute();

        assertEquals(1, project.getCompileSourceRoots().size());
        assertEquals(1, project.getResources().size());
        Path sourceRoot = Path.of(project.getCompileSourceRoots().get(0));
        Path registrySource;
        try (java.util.stream.Stream<Path> files = Files.walk(sourceRoot)) {
            registrySource = files.filter(path ->
                path.getFileName().toString().equals("RepostGeneratedClientRegistry.java"))
                .findFirst().orElseThrow();
        }
        assertTrue(Files.readString(registrySource)
            .contains("com.acme.orders.OrdersClientFactory.INSTANCE"));
        Path resourceRoot = Path.of(project.getResources().get(0).getDirectory());
        String registry;
        try (java.util.stream.Stream<Path> files = Files.walk(resourceRoot)) {
            Path manifest = files.filter(path -> path.getFileName().toString().equals("registry.json"))
                .findFirst().orElseThrow();
            registry = Files.readString(manifest);
        }
        assertTrue(registry.contains("\"registryType\": \"application\""));
        java.nio.file.attribute.FileTime stableTime = java.nio.file.attribute.FileTime.fromMillis(123456789L);
        Files.setLastModifiedTime(registrySource, stableTime);
        RepostGenerateMojo second = new RepostGenerateMojo();
        second.setProject(project);
        second.setSchemaMode("AGGREGATE_ONLY");
        second.setCompileArtifacts(artifacts, Set.of("sh.repost:repost-client"));

        second.execute();

        assertEquals(stableTime, Files.getLastModifiedTime(registrySource));
    }

    @Test
    void aggregateOnlyRejectsMismatchedRuntimeBeforeWritingAnything() throws Exception {
        MavenProject project = project();
        RepostGenerateMojo mojo = new RepostGenerateMojo();
        Map<String, Path> artifacts = new LinkedHashMap<>();
        artifacts.put("sh.repost:repost-client:0.9.0", emptyJar(temporary.resolve("old-core.jar")));
        artifacts.put("com.acme:orders-clients:1.0.0", registryJar(temporary.resolve("old-clients.jar")));
        mojo.setProject(project);
        mojo.setSchemaMode("AGGREGATE_ONLY");
        mojo.setCompileArtifacts(artifacts, Set.of("sh.repost:repost-client"));

        org.apache.maven.plugin.MojoExecutionException failure = assertThrows(
            org.apache.maven.plugin.MojoExecutionException.class,
            mojo::execute
        );

        assertTrue(failure.getMessage().contains("must resolve to exactly 1.0.0"));
        assertTrue(failure.getMessage().contains("<artifactId>repost-bom</artifactId>"));
        assertFalse(Files.exists(project.getBasedir().toPath().resolve("target")));
    }

    @Test
    void aggregateOnlyKotlinAcceptsExactKotlinAndTransitiveCoreRuntime() throws Exception {
        MavenProject project = project();
        RepostGenerateMojo mojo = new RepostGenerateMojo();
        Map<String, Path> artifacts = new LinkedHashMap<>();
        artifacts.put("sh.repost:repost-client:1.0.0", emptyJar(temporary.resolve("kotlin-core.jar")));
        artifacts.put("sh.repost:repost-client-kotlin:1.0.0",
            emptyJar(temporary.resolve("kotlin-runtime.jar")));
        artifacts.put("com.acme:kotlin-clients:1.0.0",
            registryJar(temporary.resolve("kotlin-clients.jar"), "kotlin"));
        mojo.setProject(project);
        mojo.setSchemaMode("AGGREGATE_ONLY");
        mojo.setCompileArtifacts(artifacts, Set.of("sh.repost:repost-client-kotlin"));

        mojo.execute();

        assertEquals(1, project.getCompileSourceRoots().size());
        assertEquals(1, project.getResources().size());
    }

    @Test
    void aggregateOnlyWithoutSchemaFlattensTwoLibrariesAndRendersSelectedSpringGlue()
        throws Exception {
        MavenProject project = project();
        Files.delete(project.getBasedir().toPath().resolve("repost/schema.repost"));
        Path orders = registryJar(
            temporary.resolve("orders-registry.jar"),
            "1111111111111111", "0123456789abcdef", "com.acme.orders", "OrdersClient"
        );
        Path billing = registryJar(
            temporary.resolve("billing-registry.jar"),
            "2222222222222222", "fedcba9876543210", "com.acme.billing", "BillingClient"
        );
        Map<String, Path> artifacts = new LinkedHashMap<>();
        artifacts.put("sh.repost:repost-client:1.0.0", emptyJar(temporary.resolve("spring-core.jar")));
        artifacts.put("sh.repost:repost-client-spring-boot-starter:1.0.0",
            emptyJar(temporary.resolve("spring-adapter.jar")));
        artifacts.put("com.acme:orders-clients:1.0.0", orders);
        artifacts.put("com.acme:billing-clients:1.0.0", billing);
        RepostGenerateMojo mojo = new RepostGenerateMojo();
        mojo.setProject(project);
        mojo.setSchemaMode("AGGREGATE_ONLY");
        mojo.setCompileArtifacts(artifacts, Set.of("sh.repost:repost-client-spring-boot-starter"));

        mojo.execute();

        Path sourceRoot = Path.of(project.getCompileSourceRoots().get(0));
        String sources;
        try (java.util.stream.Stream<Path> files = Files.walk(sourceRoot)) {
            StringBuilder joined = new StringBuilder();
            for (Path file : files.filter(Files::isRegularFile)
                .collect(java.util.stream.Collectors.toList())) {
                joined.append(Files.readString(file));
            }
            sources = joined.toString();
        }
        assertTrue(sources.contains("com.acme.orders.OrdersClientFactory.INSTANCE"));
        assertTrue(sources.contains("com.acme.billing.BillingClientFactory.INSTANCE"));
        assertTrue(sources.contains("@AutoConfiguration"));
        Path imports = Path.of(project.getResources().get(0).getDirectory()).resolve(
            "META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports");
        assertTrue(Files.isRegularFile(imports));
    }

    @Test
    void explicitIntegrationRequiresItsDeclaredAdapterBeforeWritingAnything() throws Exception {
        MavenProject project = project();
        RepostGenerateMojo mojo = new RepostGenerateMojo();
        mojo.setProject(project);
        mojo.setIntegration("SPRING_BOOT");
        mojo.setEngineExecutable(Files.createFile(temporary.resolve("unused-engine")));

        org.apache.maven.plugin.MojoExecutionException failure = assertThrows(
            org.apache.maven.plugin.MojoExecutionException.class,
            mojo::execute
        );

        assertTrue(failure.getMessage().contains("matching declared Repost adapter"));
        assertFalse(Files.exists(project.getBasedir().toPath().resolve("target")));
    }

    @Test
    void autoIntegrationRejectsMultipleDeclaredAdaptersBeforeWritingAnything() throws Exception {
        MavenProject project = project();
        project.getModel().addDependency(dependency("repost-client-cdi"));
        project.getModel().addDependency(dependency("repost-client-spring-boot-starter"));
        RepostGenerateMojo mojo = new RepostGenerateMojo();
        mojo.setProject(project);
        mojo.setEngineExecutable(Files.createFile(temporary.resolve("ambiguous-engine")));

        org.apache.maven.plugin.MojoExecutionException failure = assertThrows(
            org.apache.maven.plugin.MojoExecutionException.class,
            mojo::execute
        );

        assertTrue(failure.getMessage().contains("multiple declared Repost adapters"));
        assertFalse(Files.exists(project.getBasedir().toPath().resolve("target")));
    }

    private static Path registryJar(Path path) throws Exception {
        return registryJar(path, "java");
    }

    private static Path registryJar(Path path, String language) throws Exception {
        return registryJar(
            path, "1111111111111111", "0123456789abcdef",
            "com.acme.orders", "OrdersClient", language
        );
    }

    private static Path registryJar(
        Path path,
        String registryId,
        String generatorId,
        String packageName,
        String clientName
    ) throws Exception {
        return registryJar(path, registryId, generatorId, packageName, clientName, "java");
    }

    private static Path registryJar(
        Path path,
        String registryId,
        String generatorId,
        String packageName,
        String clientName,
        String language
    ) throws Exception {
        String registry = "{\n" +
            "  \"formatVersion\": 1,\n" +
            "  \"registryId\": \"" + registryId + "\",\n" +
            "  \"registryType\": \"module\",\n" +
            "  \"clients\": [\n" +
            "    {\n" +
            "      \"formatVersion\": 1,\n" +
            "      \"generatorId\": \"" + generatorId + "\",\n" +
            "      \"language\": \"" + language + "\",\n" +
            "      \"packageName\": \"" + packageName + "\",\n" +
            "      \"clientType\": \"" + packageName + "." + clientName + "\",\n" +
            "      \"factoryType\": \"" + packageName + "." + clientName + "Factory\",\n" +
            "      \"schemaHash\": \"sha256:" + "a".repeat(64) + "\",\n" +
            "      \"descriptorVersion\": 2,\n" +
            "      \"runtimeVersion\": \"1.0.0\"\n" +
            "    }\n" +
            "  ]\n" +
            "}\n";
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(path))) {
            output.putNextEntry(new JarEntry(
                "META-INF/repost/registries/v1/" + registryId + "/registry.json"));
            output.write(registry.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            output.closeEntry();
        }
        return path;
    }

    private static Path emptyJar(Path path) throws Exception {
        try (JarOutputStream ignored = new JarOutputStream(Files.newOutputStream(path))) {
            // A resolved runtime artifact without registry resources.
        }
        return path;
    }

    private MavenProject project() throws Exception {
        Path directory = Files.createDirectories(temporary.resolve("project-" + System.nanoTime()));
        Path pom = Files.writeString(directory.resolve("pom.xml"), "<project/>");
        Files.createDirectories(directory.resolve("repost"));
        Files.writeString(directory.resolve("repost/schema.repost"), "model Empty {}\n");
        Model model = new Model();
        model.setGroupId("com.acme");
        model.setArtifactId("orders");
        model.setVersion("1.0.0");
        model.addDependency(dependency("repost-client"));
        model.addDependency(dependency("repost-client-kotlin"));
        Build build = new Build();
        build.setDirectory(directory.resolve("target").toString());
        model.setBuild(build);
        MavenProject project = new MavenProject(model);
        project.setFile(pom.toFile());
        return project;
    }

    private static Dependency dependency(String artifactId) {
        Dependency dependency = new Dependency();
        dependency.setGroupId("sh.repost");
        dependency.setArtifactId(artifactId);
        dependency.setVersion("1.0.0");
        return dependency;
    }
}
