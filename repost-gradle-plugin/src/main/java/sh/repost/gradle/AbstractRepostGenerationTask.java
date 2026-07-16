package sh.repost.gradle;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.MapProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.LocalState;
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.OutputDirectories;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;
import sh.repost.buildplugin.internal.BuildPluginEngine;
import sh.repost.buildplugin.internal.BuildPluginRegistry;
import sh.repost.buildplugin.internal.NativeEngineTrust;

/** Shared normalized inputs, deterministic outputs, and local state for Repost generation tasks. */
public abstract class AbstractRepostGenerationTask extends DefaultTask implements GradleOwnedOutputs {
    /** Creates the shared task model. */
    protected AbstractRepostGenerationTask() {
    }

    /** Returns the optional local schema; aggregate-only mode omits it.
     * @return schema file
     */
    @Optional
    @InputFile
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract RegularFileProperty getSchemaFile();

    /** Returns the configured schema mode.
     * @return schema mode
     */
    @Input
    public abstract Property<RepostSchemaMode> getSchemaMode();

    /** Returns selected generator names in stable order.
     * @return generator names
     */
    @Input
    public abstract ListProperty<String> getGeneratorNames();

    /** Returns explicit generator environment inputs.
     * @return environment inputs
     */
    @Input
    public abstract MapProperty<String, String> getEnvironmentInputs();

    /** Returns the configured framework integration mode.
     * @return integration mode
     */
    @Input
    public abstract Property<RepostIntegration> getIntegration();

    /** Returns the plugin protocol version.
     * @return protocol version
     */
    @Input
    public abstract Property<Integer> getProtocolVersion();

    /** Returns the plugin implementation version.
     * @return plugin version
     */
    @Input
    public abstract Property<String> getPluginVersion();

    /** Returns the selected native engine version.
     * @return engine version
     */
    @Input
    public abstract Property<String> getEngineVersion();

    /** Returns the required runtime family version.
     * @return runtime version
     */
    @Input
    public abstract Property<String> getRuntimeVersion();

    /** Returns the required descriptor version.
     * @return descriptor version
     */
    @Input
    public abstract Property<Integer> getDescriptorVersion();

    /** Returns whether Gradle dependency resolution is offline.
     * @return offline mode
     */
    @Input
    public abstract Property<Boolean> getOffline();

    /** Returns the exact execution timeout.
     * @return execution timeout
     */
    @Input
    public abstract Property<Duration> getExecutionTimeout();

    /** Returns the stable Gradle build group.
     * @return build group
     */
    @Input
    public abstract Property<String> getBuildGroup();

    /** Returns the stable Gradle root-project name.
     * @return root-project name
     */
    @Input
    public abstract Property<String> getBuildRootProjectName();

    /** Returns the canonical Gradle project path.
     * @return project path
     */
    @Input
    public abstract Property<String> getBuildProjectPath();

    /** Returns the generation or check mode sent to the engine.
     * @return check mode
     */
    @Input
    public abstract Property<String> getCheckMode();

    /** Returns the content-fingerprinted native executable.
     * @return native executable
     */
    @InputFile
    @PathSensitive(PathSensitivity.NONE)
    @Optional
    public abstract RegularFileProperty getEngineExecutable();

    /** Returns the exact classifier archive plus checksum manifest/signature resolved by Gradle.
     * @return resolved native engine artifacts
     */
    @InputFiles
    @PathSensitive(PathSensitivity.NONE)
    public abstract ConfigurableFileCollection getEngineArtifacts();

    /** Returns resolved compile artifacts whose exact registry resources are task inputs.
     * @return resolved compile artifacts
     */
    @InputFiles
    @PathSensitive(PathSensitivity.NONE)
    public abstract ConfigurableFileCollection getDependencyRegistryArtifacts();

    /** Returns stable dependency component identities used in diagnostics and cache keys.
     * @return dependency component identities
     */
    @Input
    public abstract ListProperty<String> getDependencyArtifactIdentities();

    /** Returns resolved artifact-path-to-component labels consumed only by the task action.
     * @return artifact diagnostic labels by absolute path
     */
    @Internal
    public abstract MapProperty<String, String> getDependencyArtifactLabels();

    /** Returns declared supported Repost adapter coordinates without resolving framework classes.
     * @return declared adapter coordinates
     */
    @Input
    public abstract ListProperty<String> getDeclaredAdapterCoordinates();

    /** Returns every dedicated generated-source output.
     * @return source output directories
     */
    @OutputDirectories
    public abstract ConfigurableFileCollection getSourceOutputDirectories();

    /** Returns every dedicated generated-resource output.
     * @return resource output directories
     */
    @OutputDirectories
    public abstract ConfigurableFileCollection getResourceOutputDirectories();

    /** Returns generator-to-source-output mapping consumed only by the task action.
     * @return source output paths by generator
     */
    @Internal
    public abstract MapProperty<String, String> getSourceOutputPathsByGenerator();

    /** Returns generator-to-resource-output mapping consumed only by the task action.
     * @return resource output paths by generator
     */
    @Internal
    public abstract MapProperty<String, String> getResourceOutputPathsByGenerator();

    /** Returns the project directory used to reject shared source-tree outputs.
     * @return project directory
     */
    @Internal
    public abstract DirectoryProperty getProjectDirectory();

    /** Returns the fixed application/module registry source root.
     * @return registry source root
     */
    @OutputDirectory
    public abstract DirectoryProperty getAggregateSourceOutputDirectory();

    /** Returns the fixed application/module registry resource root.
     * @return registry resource root
     */
    @OutputDirectory
    public abstract DirectoryProperty getAggregateResourceOutputDirectory();

    /** Returns journals, locks, and request files excluded from task outputs and cache archives.
     * @return local state directory
     */
    @LocalState
    public abstract DirectoryProperty getLocalStateDirectory();

    /** Returns the shared verified native-engine cache outside generated outputs.
     * @return native engine cache directory
     */
    @LocalState
    public abstract DirectoryProperty getEngineCacheDirectory();

    /** Executes one closed request through the shared verified engine path. */
    @TaskAction
    public final void executeGeneration() {
        try {
            GradleOutputOwnership.validate(this);
            RepostIntegration integration = resolveIntegration();
            if (getSchemaMode().get() == RepostSchemaMode.GENERATE) {
                BuildPluginEngine.Request request = createEngineRequest(resolveEngineExecutable());
                Files.createDirectories(request.getRequestDirectory());
                if ("CHECK".equals(request.getCheckMode())) {
                    BuildPluginEngine.executeDeterminismCheck(request);
                } else {
                    BuildPluginEngine.execute(request);
                }
            }
            GradleOutputOwnership.mark(this);
            aggregateRegistries(integration);
            GradleOutputOwnership.mark(this);
        } catch (IOException exception) {
            throw new GradleException("Repost local generation state could not be created");
        } catch (BuildPluginEngine.EngineException | IllegalArgumentException exception) {
            throw new GradleException(exception.getMessage());
        } catch (GradleNativeEngineResolver.ResolutionException | NativeEngineTrust.TrustException exception) {
            throw new GradleException(exception.getMessage());
        }
    }

    final RepostIntegration resolveIntegration() {
        Map<String, RepostIntegration> supported = Map.of(
            "sh.repost:repost-client-spring-boot-starter", RepostIntegration.SPRING_BOOT,
            "sh.repost:repost-client-cdi", RepostIntegration.CDI
        );
        Set<RepostIntegration> declared = new LinkedHashSet<>();
        for (String coordinate : getDeclaredAdapterCoordinates().get()) {
            RepostIntegration integration = supported.get(coordinate);
            if (integration != null) {
                declared.add(integration);
            }
        }
        RepostIntegration requested = getIntegration().get();
        if (requested == RepostIntegration.NONE) {
            return requested;
        }
        if (requested == RepostIntegration.AUTO) {
            if (declared.isEmpty()) {
                return RepostIntegration.NONE;
            }
            if (declared.size() == 1) {
                return declared.iterator().next();
            }
            throw new GradleException(
                "multiple declared Repost adapters require integration NONE, SPRING_BOOT, or CDI"
            );
        }
        if (!declared.contains(requested)) {
            throw new GradleException(
                "integration " + requested + " requires its matching declared Repost adapter dependency"
            );
        }
        return requested;
    }

    private void aggregateRegistries(RepostIntegration integration) {
        List<Path> localRoots = new ArrayList<>();
        if (getSchemaMode().get() == RepostSchemaMode.GENERATE) {
            getResourceOutputPathsByGenerator().get().values().forEach(value ->
                localRoots.add(Path.of(value).toAbsolutePath().normalize())
            );
        }
        Set<Path> artifacts = new LinkedHashSet<>();
        getDependencyRegistryArtifacts().getFiles().forEach(file -> artifacts.add(file.toPath()));
        Map<Path, String> artifactLabels = new java.util.LinkedHashMap<>();
        getDependencyArtifactLabels().get().forEach((path, label) ->
            artifactLabels.put(Path.of(path).toAbsolutePath().normalize(), label)
        );
        BuildPluginRegistry.aggregate(new BuildPluginRegistry.Request(
            localRoots,
            artifacts,
            artifactLabels,
            getAggregateSourceOutputDirectory().get().getAsFile().toPath(),
            getAggregateResourceOutputDirectory().get().getAsFile().toPath(),
            getBuildGroup().get(),
            getBuildRootProjectName().get(),
            getBuildProjectPath().get(),
            getSchemaMode().get() == RepostSchemaMode.AGGREGATE_ONLY,
            integration.name()
        ));
    }

    final BuildPluginEngine.Request createEngineRequest() {
        if (!getEngineExecutable().isPresent()) {
            throw new GradleException("Repost native engine executable has not been prepared");
        }
        return createEngineRequest(getEngineExecutable().get().getAsFile().toPath());
    }

    private BuildPluginEngine.Request createEngineRequest(Path executable) {
        if (getSchemaMode().get() != RepostSchemaMode.GENERATE) {
            throw new GradleException("aggregate-only registry generation is not configured");
        }
        if (getProtocolVersion().get() != 1) {
            throw new GradleException("Repost generation protocol must equal 1");
        }
        if (getDescriptorVersion().get() != 2) {
            throw new GradleException("Repost descriptor version must equal 2");
        }
        String checkMode = getCheckMode().get();
        if (!"GENERATE".equals(checkMode) && !"CHECK".equals(checkMode)) {
            throw new GradleException("Repost check mode must be GENERATE or CHECK");
        }

        List<String> generatorNames = getGeneratorNames().get();
        Map<String, String> sourceOutputs = getSourceOutputPathsByGenerator().get();
        Map<String, String> resourceOutputs = getResourceOutputPathsByGenerator().get();
        if (!sourceOutputs.keySet().equals(resourceOutputs.keySet())
            || generatorNames.size() != sourceOutputs.size()
            || !sourceOutputs.keySet().equals(new java.util.LinkedHashSet<>(generatorNames))) {
            throw new GradleException("Repost generator names and dedicated output roots do not match");
        }

        Path localState = getLocalStateDirectory().get().getAsFile().toPath()
            .toAbsolutePath().normalize();
        List<BuildPluginEngine.Generator> generators = new ArrayList<>(generatorNames.size());
        for (String generatorName : generatorNames) {
            Path control = localState.resolve("control").resolve(generatorName);
            generators.add(new BuildPluginEngine.Generator(
                generatorName,
                absolute(sourceOutputs.get(generatorName)),
                absolute(resourceOutputs.get(generatorName)),
                control.resolve("source").toString(),
                control.resolve("resource").toString()
            ));
        }
        return new BuildPluginEngine.Request(
            executable,
            getPluginVersion().get(),
            getEngineVersion().get(),
            getRuntimeVersion().get(),
            getSchemaFile().get().getAsFile().toPath().toAbsolutePath().normalize().toString(),
            generators,
            getEnvironmentInputs().get(),
            BuildPluginEngine.BuildIdentity.gradle(
                getBuildGroup().get(),
                getBuildRootProjectName().get(),
                getBuildProjectPath().get()
            ),
            "CHECK".equals(checkMode),
            localState.resolve("requests"),
            getExecutionTimeout().get()
        );
    }

    private Path resolveEngineExecutable() {
        if (getEngineExecutable().isPresent()) {
            return getEngineExecutable().get().getAsFile().toPath();
        }
        List<Path> artifacts = new ArrayList<>();
        getEngineArtifacts().getFiles().forEach(file -> artifacts.add(file.toPath()));
        return GradleNativeEngineResolver.resolve(
            System.getProperty("os.name", ""),
            System.getProperty("os.arch", ""),
            getEngineVersion().get(),
            artifacts,
            NativeEngineTrust.load(getClass().getClassLoader()),
            getEngineCacheDirectory().get().getAsFile().toPath(),
            getOffline().get()
        );
    }

    private static String absolute(String path) {
        return Path.of(path).toAbsolutePath().normalize().toString();
    }
}
