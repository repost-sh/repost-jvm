package sh.repost.maven;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.apache.maven.model.Resource;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.repository.RemoteRepository;
import sh.repost.buildplugin.internal.BuildPluginEngine;
import sh.repost.buildplugin.internal.BuildPluginRegistry;
import sh.repost.buildplugin.internal.NativeEngineTrust;

/** Shared Maven configuration and execution path for Repost generation goals. */
abstract class AbstractRepostMojo extends AbstractMojo {
    @Component
    private RepositorySystem repositorySystem;

    @Parameter(defaultValue = "${repositorySystemSession}", readonly = true, required = true)
    private RepositorySystemSession repositorySession;

    @Parameter(defaultValue = "${project.remoteProjectRepositories}", readonly = true, required = true)
    private List<RemoteRepository> remoteRepositories;

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;

    @Parameter(property = "repost.schemaFile", defaultValue = "${project.basedir}/repost/schema.repost")
    private File schemaFile;

    @Parameter(property = "repost.generators")
    private List<String> generators;

    @Parameter(property = "repost.environmentInputs")
    private Map<String, String> environmentInputs;

    @Parameter(property = "repost.engineVersion", defaultValue = "0.9.0")
    private String engineVersion;

    @Parameter(property = "repost.executionTimeout", defaultValue = "PT5M")
    private String executionTimeout;

    @Parameter(property = "repost.schemaMode", defaultValue = "GENERATE")
    private String schemaMode;

    @Parameter(property = "repost.integration", defaultValue = "AUTO")
    private String integration;

    @Parameter(property = "repost.checkAgainst")
    private File checkAgainst;

    @Parameter
    private Map<String, String> sourceOutputDirectory;

    @Parameter
    private Map<String, String> resourceOutputDirectory;

    private Path engineExecutable;
    private EngineResolver engineResolver;
    private EngineInvoker engineInvoker;
    private MavenCompileGraph compileGraph;

    @Override
    public final void execute() throws MojoExecutionException {
        try {
            MavenProject configuredProject = requiredProject();
            validateJavaTarget(configuredProject);
            MavenCompileGraph graph = compileGraph == null
                ? MavenCompileGraph.from(configuredProject)
                : compileGraph;
            String resolvedIntegration = graph.resolveIntegration(integration);
            String mode = schemaMode();
            Set<String> dependencyLanguages = BuildPluginRegistry.dependencyLanguages(graph.artifactPaths());
            if ("AGGREGATE_ONLY".equals(mode)) {
                graph.validateRuntime(dependencyLanguages);
                Path buildDirectory = buildDirectory(configuredProject, configuredProject.getBasedir().toPath());
                if (!isCheck()) {
                    MavenOutputOwnership.validateAggregateRoots(
                        aggregateSource(buildDirectory),
                        aggregateResource(buildDirectory),
                        project.getGroupId(),
                        project.getArtifactId()
                    );
                    String fingerprint = MavenIncrementalState.aggregateFingerprint(
                        mode, resolvedIntegration, graph.artifactPaths());
                    if (MavenIncrementalState.canReuse(
                        buildDirectory.resolve("repost-state/generate"),
                        fingerprint,
                        Collections.emptyList(),
                        aggregateSource(buildDirectory),
                        aggregateResource(buildDirectory)
                    )) {
                        registerAggregateRoots(buildDirectory);
                        return;
                    }
                }
                aggregateRegistries(
                    Collections.emptyList(),
                    graph.artifactPaths(),
                    graph.artifactLabels(),
                    buildDirectory,
                    true,
                    resolvedIntegration
                );
                if (!isCheck()) {
                    MavenOutputOwnership.markAggregateRoots(
                        aggregateSource(buildDirectory),
                        aggregateResource(buildDirectory),
                        project.getGroupId(),
                        project.getArtifactId()
                    );
                    registerAggregateRoots(buildDirectory);
                    MavenIncrementalState.store(
                        buildDirectory.resolve("repost-state/generate"),
                        MavenIncrementalState.aggregateFingerprint(
                            mode, resolvedIntegration, graph.artifactPaths()),
                        Collections.emptyList(),
                        aggregateSource(buildDirectory),
                        aggregateResource(buildDirectory)
                    );
                }
                return;
            }
            validateKotlinBaseline(configuredProject);
            BuildPluginEngine.Request request = createRequest();
            Set<String> languages = new java.util.LinkedHashSet<>();
            for (BuildPluginEngine.Generator generator : request.getGenerators()) {
                languages.add(language(generator.getName()));
            }
            graph.validateRuntime(languages);
            Files.createDirectories(request.getRequestDirectory());
            Path buildDirectory = buildDirectory(project, project.getBasedir().toPath());
            if (!isCheck()) {
                MavenOutputOwnership.validateRoots(
                    buildDirectory,
                    request.getGenerators(),
                    project.getGroupId(),
                    project.getArtifactId()
                );
                MavenOutputOwnership.validateAggregateRoots(
                    aggregateSource(buildDirectory),
                    aggregateResource(buildDirectory),
                    project.getGroupId(),
                    project.getArtifactId()
                );
                if (engineInvoker == null) {
                    String fingerprint = MavenIncrementalState.fingerprint(
                        request,
                        mode,
                        resolvedIntegration,
                        graph.artifactPaths()
                    );
                    if (MavenIncrementalState.canReuse(
                        request.getRequestDirectory().getParent(),
                        fingerprint,
                        request.getGenerators(),
                        aggregateSource(buildDirectory),
                        aggregateResource(buildDirectory)
                    )) {
                        registerGeneratedRoots(request.getGenerators());
                        registerAggregateRoots(buildDirectory);
                        return;
                    }
                }
            }
            if (engineInvoker != null) {
                engineInvoker.execute(request);
            } else if (isCheck()) {
                BuildPluginEngine.executeDeterminismCheck(request);
            } else {
                BuildPluginEngine.execute(request);
            }
            aggregateRegistries(
                request.getGenerators(),
                graph.artifactPaths(),
                graph.artifactLabels(),
                buildDirectory,
                false,
                resolvedIntegration
            );
            if (isCheck() && checkAgainst != null) {
                MavenCheckAgainst.compare(
                    buildDirectory.resolve(".repost-sdk-check"),
                    checkAgainst.toPath()
                );
            }
            if (!isCheck()) {
                MavenOutputOwnership.pruneStaleDefaultRoots(buildDirectory, request.getGenerators());
                MavenOutputOwnership.markRoots(
                    buildDirectory,
                    request.getGenerators(),
                    project.getGroupId(),
                    project.getArtifactId()
                );
                MavenOutputOwnership.markAggregateRoots(
                    aggregateSource(buildDirectory),
                    aggregateResource(buildDirectory),
                    project.getGroupId(),
                    project.getArtifactId()
                );
                registerGeneratedRoots(request.getGenerators());
                registerAggregateRoots(buildDirectory);
                if (engineInvoker == null) {
                    MavenIncrementalState.store(
                        request.getRequestDirectory().getParent(),
                        MavenIncrementalState.fingerprint(
                            request,
                            mode,
                            resolvedIntegration,
                            graph.artifactPaths()
                        ),
                        request.getGenerators(),
                        aggregateSource(buildDirectory),
                        aggregateResource(buildDirectory)
                    );
                }
            }
        } catch (IOException exception) {
            throw new MojoExecutionException("Repost local generation state could not be created");
        } catch (BuildPluginEngine.EngineException | IllegalArgumentException exception) {
            throw new MojoExecutionException(exception.getMessage());
        } catch (
            MavenNativeEngineResolver.ResolutionException | NativeEngineTrust.TrustException exception
        ) {
            throw new MojoExecutionException(exception.getMessage());
        }
    }

    private BuildPluginEngine.Request createRequest() throws IOException, MojoExecutionException {
        MavenProject configuredProject = requiredProject();
        Path baseDirectory = configuredProject.getBasedir().toPath().toAbsolutePath().normalize();
        Path buildDirectory = buildDirectory(configuredProject, baseDirectory);
        Path stateDirectory = buildDirectory.resolve("repost-state").resolve(isCheck() ? "check" : "generate");
        Path schema = schemaFile == null
            ? baseDirectory.resolve("repost/schema.repost")
            : schemaFile.toPath();
        Path canonicalSchema;
        try {
            canonicalSchema = schema.toRealPath();
        } catch (IOException exception) {
            throw new MojoExecutionException("Repost schema file could not be resolved");
        }

        List<String> selected = generators == null
            ? Arrays.asList("javaSdk", "kotlinSdk")
            : new ArrayList<>(generators);
        Collections.sort(selected);
        validateOverrideKeys(selected, sourceOutputDirectory, "sourceOutputDirectory");
        validateOverrideKeys(selected, resourceOutputDirectory, "resourceOutputDirectory");
        List<BuildPluginEngine.Generator> requests = new ArrayList<>(selected.size());
        for (String generator : selected) {
            String language = language(generator);
            String generatorId = generatorId(generator);
            Path defaultSource = isCheck()
                ? buildDirectory.resolve(".repost-sdk-check/sources").resolve(generatorId).resolve(language)
                : buildDirectory.resolve("generated-sources/repost").resolve(generatorId).resolve(language);
            Path defaultResource = isCheck()
                ? buildDirectory.resolve(".repost-sdk-check/resources").resolve(generatorId)
                : buildDirectory.resolve("generated-resources/repost").resolve(generatorId);
            Path source = isCheck()
                ? defaultSource
                : configuredOutput(baseDirectory, sourceOutputDirectory, generator, defaultSource);
            Path resource = isCheck()
                ? defaultResource
                : configuredOutput(baseDirectory, resourceOutputDirectory, generator, defaultResource);
            Path control = stateDirectory.resolve("control").resolve(generator);
            requests.add(new BuildPluginEngine.Generator(
                generator,
                source.toString(),
                resource.toString(),
                control.resolve("source").toString(),
                control.resolve("resource").toString()
            ));
        }

        Path executable = engineExecutable;
        if (executable == null) {
            executable = resolveEngine(engineVersion == null ? "0.9.0" : engineVersion);
        }
        Map<String, String> environment = environmentInputs == null
            ? Collections.emptyMap()
            : new LinkedHashMap<>(environmentInputs);
        Duration timeout;
        try {
            timeout = Duration.parse(executionTimeout == null ? "PT5M" : executionTimeout);
            if (timeout.isZero() || timeout.isNegative()) {
                throw new IllegalArgumentException("timeout must be positive");
            }
        } catch (RuntimeException exception) {
            throw new MojoExecutionException("Repost executionTimeout must be a positive ISO-8601 duration");
        }
        return new BuildPluginEngine.Request(
            executable,
            "1.0.0",
            engineVersion == null ? "0.9.0" : engineVersion,
            "1.0.0",
            canonicalSchema.toString(),
            requests,
            environment,
            BuildPluginEngine.BuildIdentity.maven(
                configuredProject.getGroupId(),
                configuredProject.getArtifactId()
            ),
            isCheck(),
            stateDirectory.resolve("requests"),
            timeout
        );
    }

    private void registerGeneratedRoots(List<BuildPluginEngine.Generator> generated) {
        for (BuildPluginEngine.Generator generator : generated) {
            project.addCompileSourceRoot(generator.getSourceOutputDirectory());
            Resource resource = new Resource();
            resource.setDirectory(generator.getResourceOutputDirectory());
            project.addResource(resource);
        }
    }

    private void aggregateRegistries(
        List<BuildPluginEngine.Generator> generators,
        Set<Path> dependencyArtifacts,
        Map<Path, String> dependencyArtifactLabels,
        Path buildDirectory,
        boolean aggregateOnly,
        String resolvedIntegration
    ) {
        List<Path> localRoots = new ArrayList<>();
        for (BuildPluginEngine.Generator generator : generators) {
            localRoots.add(Path.of(generator.getResourceOutputDirectory()));
        }
        Path source = isCheck()
            ? buildDirectory.resolve(".repost-sdk-check/sources/repost-registry")
            : aggregateSource(buildDirectory);
        Path resource = isCheck()
            ? buildDirectory.resolve(".repost-sdk-check/resources/repost-registry")
            : aggregateResource(buildDirectory);
        BuildPluginRegistry.aggregate(new BuildPluginRegistry.Request(
            localRoots,
            dependencyArtifacts,
            dependencyArtifactLabels,
            source,
            resource,
            project.getGroupId(),
            project.getArtifactId(),
            "",
            aggregateOnly,
            resolvedIntegration
        ));
    }

    private String schemaMode() throws MojoExecutionException {
        String mode = schemaMode == null
            ? "GENERATE"
            : schemaMode.trim().toUpperCase(java.util.Locale.ROOT);
        if (!"GENERATE".equals(mode) && !"AGGREGATE_ONLY".equals(mode)) {
            throw new MojoExecutionException("Repost schemaMode must be GENERATE or AGGREGATE_ONLY");
        }
        return mode;
    }

    private void registerAggregateRoots(Path buildDirectory) {
        project.addCompileSourceRoot(aggregateSource(buildDirectory).toString());
        Resource resource = new Resource();
        resource.setDirectory(aggregateResource(buildDirectory).toString());
        project.addResource(resource);
    }

    private static Path aggregateSource(Path buildDirectory) {
        return buildDirectory.resolve("generated-sources/repost-registry");
    }

    private static Path aggregateResource(Path buildDirectory) {
        return buildDirectory.resolve("generated-resources/repost-registry");
    }

    private MavenProject requiredProject() throws MojoExecutionException {
        if (project == null || project.getBasedir() == null
            || project.getGroupId() == null || project.getArtifactId() == null) {
            throw new MojoExecutionException("Repost generation requires one resolved Maven project");
        }
        return project;
    }

    private static void validateJavaTarget(MavenProject project) throws MojoExecutionException {
        String target = effectiveProperty(
            project,
            "maven.compiler.release",
            effectiveProperty(project, "maven.compiler.target", null)
        );
        if (target == null) {
            return;
        }
        String feature = target.trim().startsWith("1.") ? target.trim().substring(2) : target.trim();
        try {
            if (Integer.parseInt(feature) < 11) {
                throw new MojoExecutionException(
                    "Repost generated Java requires Java 11 or newer; "
                        + "set <maven.compiler.release>11</maven.compiler.release>"
                );
            }
        } catch (NumberFormatException ignored) {
            // Let Maven's compiler configuration report malformed versions.
        }
    }

    private void validateKotlinBaseline(MavenProject project) throws MojoExecutionException {
        if (generators != null && !generators.contains("kotlinSdk")) {
            return;
        }
        for (String property : Arrays.asList(
            "kotlin.version",
            "kotlin.compiler.languageVersion",
            "kotlin.compiler.apiVersion"
        )) {
            String version = effectiveProperty(project, property, null);
            if (version != null && beforeKotlin21(version)) {
                throw new MojoExecutionException(
                    "Repost generated Kotlin requires Kotlin 2.1 or newer; set "
                        + "<kotlin.version>2.1.21</kotlin.version>, "
                        + "<kotlin.compiler.languageVersion>2.1</kotlin.compiler.languageVersion>, and "
                        + "<kotlin.compiler.apiVersion>2.1</kotlin.compiler.apiVersion>"
                );
            }
        }
    }

    private static boolean beforeKotlin21(String version) {
        String[] parts = version.trim().split("[.-]", 3);
        try {
            int major = Integer.parseInt(parts[0]);
            int minor = parts.length > 1 ? Integer.parseInt(parts[1]) : 0;
            return major < 2 || major == 2 && minor < 1;
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    private static String effectiveProperty(MavenProject project, String name, String fallback) {
        if (project.getProjectBuildingRequest() != null) {
            String userValue = project.getProjectBuildingRequest().getUserProperties().getProperty(name);
            if (userValue != null) {
                return userValue;
            }
        }
        return project.getProperties().getProperty(name, fallback);
    }

    private static Path buildDirectory(MavenProject project, Path baseDirectory) {
        if (project.getBuild() == null || project.getBuild().getDirectory() == null) {
            return baseDirectory.resolve("target");
        }
        return Path.of(project.getBuild().getDirectory()).toAbsolutePath().normalize();
    }

    private static String language(String generator) {
        if ("javaSdk".equals(generator)) {
            return "java";
        }
        if ("kotlinSdk".equals(generator)) {
            return "kotlin";
        }
        throw new IllegalArgumentException("unsupported Repost JVM generator: " + generator);
    }

    private static String generatorId(String generator) {
        if ("javaSdk".equals(generator)) {
            return "cfc20961be395d84";
        }
        if ("kotlinSdk".equals(generator)) {
            return "c756cf657644d412";
        }
        throw new IllegalArgumentException("unsupported Repost JVM generator: " + generator);
    }

    private static Path configuredOutput(
        Path baseDirectory,
        Map<String, String> configured,
        String generator,
        Path fallback
    ) {
        if (configured == null || !configured.containsKey(generator)) {
            return fallback;
        }
        String value = configured.get(generator);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Repost output override for " + generator + " is empty");
        }
        Path path = Path.of(value);
        Path result = (path.isAbsolute() ? path : baseDirectory.resolve(path)).toAbsolutePath().normalize();
        if (result.startsWith(baseDirectory.resolve("src/main").toAbsolutePath().normalize())) {
            throw new IllegalArgumentException(
                "Repost plugin outputs cannot use a shared checked-in src/main root; use checkAgainst"
            );
        }
        return result;
    }

    private static void validateOverrideKeys(
        List<String> selected,
        Map<String, String> overrides,
        String name
    ) {
        if (overrides != null && !selected.containsAll(overrides.keySet())) {
            throw new IllegalArgumentException("Repost " + name + " contains an unselected generator");
        }
    }

    private Path resolveEngine(String version) throws MojoExecutionException {
        if (engineResolver != null) {
            return engineResolver.resolve(version);
        }
        if (repositorySystem == null || repositorySession == null || remoteRepositories == null) {
            throw new MojoExecutionException("Repost native engine Maven repository session is unavailable");
        }
        MavenRepositoryArtifactSource source = new MavenRepositoryArtifactSource(
            repositorySystem,
            repositorySession,
            remoteRepositories
        );
        return MavenNativeEngineResolver.resolve(
            System.getProperty("os.name", ""),
            System.getProperty("os.arch", ""),
            version,
            source,
            NativeEngineTrust.load(getClass().getClassLoader()),
            source.cacheRoot()
        );
    }

    abstract boolean isCheck();

    final void setProject(MavenProject value) {
        project = Objects.requireNonNull(value, "project");
    }

    final void setEngineExecutable(Path value) {
        engineExecutable = Objects.requireNonNull(value, "engineExecutable");
    }

    final void setEngineResolver(EngineResolver value) {
        engineResolver = Objects.requireNonNull(value, "engineResolver");
    }

    final void setEngineInvoker(EngineInvoker value) {
        engineInvoker = Objects.requireNonNull(value, "engineInvoker");
    }

    final void setSchemaMode(String value) {
        schemaMode = Objects.requireNonNull(value, "schemaMode");
    }

    final void setIntegration(String value) {
        integration = Objects.requireNonNull(value, "integration");
    }

    final void setCheckAgainst(Path value) {
        checkAgainst = Objects.requireNonNull(value, "checkAgainst").toFile();
    }

    final void setEnvironmentInputs(Map<String, String> value) {
        environmentInputs = new LinkedHashMap<>(Objects.requireNonNull(value, "environmentInputs"));
    }

    final void setOutputDirectories(Map<String, String> sources, Map<String, String> resources) {
        sourceOutputDirectory = new LinkedHashMap<>(Objects.requireNonNull(sources, "sources"));
        resourceOutputDirectory = new LinkedHashMap<>(Objects.requireNonNull(resources, "resources"));
    }

    final void setGenerators(List<String> value) {
        generators = new ArrayList<>(Objects.requireNonNull(value, "generators"));
    }

    final void setCompileArtifacts(Map<String, Path> resolved, Set<String> declared) {
        compileGraph = MavenCompileGraph.testing(resolved, declared);
    }

    @FunctionalInterface
    interface EngineInvoker {
        void execute(BuildPluginEngine.Request request);
    }

    @FunctionalInterface
    interface EngineResolver {
        Path resolve(String engineVersion);
    }
}
