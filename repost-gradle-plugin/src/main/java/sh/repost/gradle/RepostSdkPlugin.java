package sh.repost.gradle;

import java.io.File;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.gradle.api.GradleException;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.artifacts.ArtifactCollection;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.attributes.LibraryElements;
import org.gradle.api.plugins.JavaPluginExtension;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.SourceSet;
import org.gradle.api.tasks.TaskProvider;
import org.gradle.util.GradleVersion;
import sh.repost.buildplugin.internal.NativeEngineArtifacts;

/** Public Gradle plugin entry point. */
public final class RepostSdkPlugin implements Plugin<Project> {
    /** Creates the stateless plugin entry point. */
    public RepostSdkPlugin() {
    }

    @Override
    public void apply(Project project) {
        RepostSdkExtension extension = project.getExtensions().create(
            "repostSdk",
            RepostSdkExtension.class
        );
        Configuration nativeEngineArtifacts = nativeEngineArtifacts(project, extension);
        TaskProvider<RepostGenerateTask> generate = project.getTasks().register(
            "repostGenerate",
            RepostGenerateTask.class,
            task -> configureTask(
                project, extension, nativeEngineArtifacts, task, "GENERATE", "generate")
        );
        TaskProvider<RepostValidateOutputOwnershipTask> validateOutputOwnership = project.getTasks().register(
            "repostValidateOutputOwnership",
            RepostValidateOutputOwnershipTask.class,
            task -> configureOutputOwnershipTask(project, extension, task)
        );
        TaskProvider<RepostVerifyGeneratedOutputsTask> verify = project.getTasks().register(
            "repostVerifyGeneratedOutputs",
            RepostVerifyGeneratedOutputsTask.class,
            task -> configureVerificationTask(project, extension, task)
        );
        TaskProvider<RepostGenerateCheckTask> check = project.getTasks().register(
            "repostGenerateCheck",
            RepostGenerateCheckTask.class,
            task -> {
                configureTask(project, extension, nativeEngineArtifacts, task, "CHECK", "check");
                task.getCheckAgainst().set(extension.getCheckAgainst());
            }
        );
        generate.configure(task -> {
            task.setDescription("Generates Repost SDK source and resource trees.");
            task.dependsOn(validateOutputOwnership);
        });
        verify.configure(task -> {
            task.setDescription("Verifies generated Repost SDK markers and tree hashes.");
            task.dependsOn(generate);
        });
        check.configure(task -> task.setDescription("Checks deterministic Repost SDK generation without mutation."));
        project.getPluginManager().withPlugin("base", ignored -> {
            TaskProvider<?> clean = project.getTasks().named("clean");
            validateOutputOwnership.configure(task -> task.mustRunAfter(clean));
            generate.configure(task -> task.mustRunAfter(clean));
            check.configure(task -> task.mustRunAfter(clean));
        });
        project.getPluginManager().withPlugin(
            "java",
            ignored -> wireJavaLifecycle(project, extension, generate, verify, check)
        );
        project.getPluginManager().withPlugin(
            "org.jetbrains.kotlin.jvm",
            ignored -> {
                KotlinSourceSetWiring.wire(project, extension, verify);
                configureCompileInputs(project, generate, check);
                project.afterEvaluate(evaluated -> validateKotlinGradlePair(
                    kotlinPluginVersion(project),
                    project.getGradle().getGradleVersion(),
                    extension.getSchemaMode().get() == RepostSchemaMode.GENERATE
                        && extension.getGenerators().getByName("kotlinSdk").getEnabled().get()
                ));
            }
        );
    }

    static void validateKotlinGradlePair(
        String kotlinVersion,
        String gradleVersion,
        boolean kotlinGenerationEnabled
    ) {
        if (kotlinGenerationEnabled && kotlinVersion != null && kotlinVersion.startsWith("2.4.")
            && GradleVersion.version(gradleVersion).compareTo(GradleVersion.version("9.5")) < 0) {
            throw new GradleException(
                "Repost Kotlin 2.4 generation requires Gradle 9.5 or newer; "
                    + "run ./gradlew wrapper --gradle-version 9.5.0 or use Kotlin 2.1.21"
            );
        }
    }

    private static String kotlinPluginVersion(Project project) {
        Plugin<?> plugin = project.getPlugins().findPlugin("org.jetbrains.kotlin.jvm");
        return plugin == null ? null : plugin.getClass().getPackage().getImplementationVersion();
    }

    private static void wireJavaLifecycle(
        Project project,
        RepostSdkExtension extension,
        TaskProvider<RepostGenerateTask> generate,
        TaskProvider<RepostVerifyGeneratedOutputsTask> verify,
        TaskProvider<RepostGenerateCheckTask> check
    ) {
        SourceSet main = project.getExtensions().getByType(JavaPluginExtension.class)
            .getSourceSets().getByName(SourceSet.MAIN_SOURCE_SET_NAME);
        main.getJava().srcDir(
            extension.getGenerators().named("javaSdk")
                .flatMap(RepostGeneratorSpec::getSourceOutputDirectory)
        );
        main.getJava().srcDir(generate.flatMap(RepostGenerateTask::getAggregateSourceOutputDirectory));
        main.getResources().srcDir(
            extension.getGenerators().named("javaSdk")
                .flatMap(RepostGeneratorSpec::getResourceOutputDirectory)
        );
        main.getResources().srcDir(
            extension.getGenerators().named("kotlinSdk")
                .flatMap(RepostGeneratorSpec::getResourceOutputDirectory)
        );
        main.getResources().srcDir(generate.flatMap(RepostGenerateTask::getAggregateResourceOutputDirectory));
        main.getResources().exclude(".repost-output-root.json", "**/.repost-output-root.json");
        configureCompileInputs(project, generate, check);
        project.getTasks().named(main.getCompileJavaTaskName()).configure(task -> task.dependsOn(verify));
        project.getTasks().named(main.getProcessResourcesTaskName()).configure(task -> task.dependsOn(verify));
        project.getTasks().named("check").configure(task -> task.dependsOn(check));
    }

    private static void configureVerificationTask(
        Project project,
        RepostSdkExtension extension,
        RepostVerifyGeneratedOutputsTask task
    ) {
        task.setGroup("repost");
        task.getGeneratorNames().set(project.provider(() ->
            extension.getSchemaMode().get() == RepostSchemaMode.GENERATE
                ? enabledGeneratorNames(extension)
                : Collections.emptyList()
        ));
        task.getSourceOutputDirectories().from(generatorOutputFiles(project, extension, true, false));
        task.getResourceOutputDirectories().from(generatorOutputFiles(project, extension, false, false));
        task.getSourceOutputPathsByGenerator().set(generatorOutputPaths(project, extension, true, false));
        task.getResourceOutputPathsByGenerator().set(generatorOutputPaths(project, extension, false, false));
    }

    private static void configureTask(
        Project project,
        RepostSdkExtension extension,
        Configuration nativeEngineArtifacts,
        AbstractRepostGenerationTask task,
        String checkMode,
        String stateName
    ) {
        task.setGroup("repost");
        task.getSchemaFile().set(project.provider(() ->
            extension.getSchemaMode().get() == RepostSchemaMode.GENERATE
                ? extension.getSchemaFile().get()
                : null
        ));
        task.getSchemaMode().set(extension.getSchemaMode());
        task.getGeneratorNames().set(project.provider(() ->
            extension.getSchemaMode().get() == RepostSchemaMode.GENERATE
                ? enabledGeneratorNames(extension)
                : Collections.emptyList()
        ));
        task.getEnvironmentInputs().set(extension.getEnvironmentInputs());
        task.getIntegration().set(extension.getIntegration());
        task.getProtocolVersion().convention(1);
        task.getPluginVersion().convention("1.0.0");
        task.getEngineVersion().set(extension.getEngineVersion());
        task.getRuntimeVersion().convention("1.0.0");
        task.getDescriptorVersion().convention(2);
        task.getOffline().set(project.provider(() -> project.getGradle().getStartParameter().isOffline()));
        task.getExecutionTimeout().set(extension.getExecutionTimeout());
        task.getEngineArtifacts().from(project.provider(() ->
            extension.getSchemaMode().get() == RepostSchemaMode.GENERATE
                ? nativeEngineArtifacts.getIncoming().artifactView(view -> view.lenient(true)).getFiles()
                : Collections.emptyList()
        ));
        task.getDependencyArtifactIdentities().convention(Collections.emptyList());
        task.getDependencyArtifactLabels().convention(Collections.emptyMap());
        task.getDeclaredAdapterCoordinates().convention(Collections.emptyList());
        task.getEngineCacheDirectory().convention(project.getLayout().dir(project.provider(() ->
            new File(project.getGradle().getGradleUserHomeDir(), "caches/repost/native-engine")
        )));
        task.getBuildGroup().set(project.provider(() -> project.getGroup().toString()));
        task.getBuildRootProjectName().set(project.provider(() -> project.getRootProject().getName()));
        task.getBuildProjectPath().set(project.provider(project::getPath));
        task.getCheckMode().convention(checkMode);
        boolean isolatedCheck = "CHECK".equals(checkMode);
        task.getSourceOutputDirectories().from(generatorOutputFiles(project, extension, true, isolatedCheck));
        task.getResourceOutputDirectories().from(generatorOutputFiles(project, extension, false, isolatedCheck));
        task.getSourceOutputPathsByGenerator().set(
            generatorOutputPaths(project, extension, true, isolatedCheck)
        );
        task.getResourceOutputPathsByGenerator().set(
            generatorOutputPaths(project, extension, false, isolatedCheck)
        );
        task.getProjectDirectory().set(project.getLayout().getProjectDirectory());
        task.getAggregateSourceOutputDirectory().convention(
            project.getLayout().getBuildDirectory().dir(
                isolatedCheck
                    ? ".repost-sdk-check/sources/repostRegistry/main/java"
                    : "generated/sources/repostRegistry/main/java"
            )
        );
        task.getAggregateResourceOutputDirectory().convention(
            project.getLayout().getBuildDirectory().dir(
                isolatedCheck
                    ? ".repost-sdk-check/resources/repostRegistry/main"
                    : "generated/resources/repostRegistry/main"
            )
        );
        task.getLocalStateDirectory().convention(
            project.getLayout().getBuildDirectory().dir(".repost-sdk-state/" + stateName)
        );
    }

    private static void configureOutputOwnershipTask(
        Project project,
        RepostSdkExtension extension,
        RepostValidateOutputOwnershipTask task
    ) {
        task.setGroup("repost");
        task.setDescription("Validates that Repost generated output roots are dedicated and owned.");
        task.getSchemaMode().set(extension.getSchemaMode());
        task.getGeneratorNames().set(project.provider(() ->
            extension.getSchemaMode().get() == RepostSchemaMode.GENERATE
                ? enabledGeneratorNames(extension)
                : Collections.emptyList()
        ));
        task.getSourceOutputPathsByGenerator().set(generatorOutputPaths(project, extension, true, false));
        task.getResourceOutputPathsByGenerator().set(generatorOutputPaths(project, extension, false, false));
        task.getProjectDirectory().set(project.getLayout().getProjectDirectory());
        task.getAggregateSourceOutputDirectory().set(
            project.getLayout().getBuildDirectory().dir("generated/sources/repostRegistry/main/java")
        );
        task.getAggregateResourceOutputDirectory().set(
            project.getLayout().getBuildDirectory().dir("generated/resources/repostRegistry/main")
        );
        task.getBuildGroup().set(project.provider(() -> project.getGroup().toString()));
        task.getBuildRootProjectName().set(project.provider(() -> project.getRootProject().getName()));
        task.getBuildProjectPath().set(project.provider(project::getPath));
    }

    private static void configureCompileInputs(
        Project project,
        TaskProvider<RepostGenerateTask> generate,
        TaskProvider<RepostGenerateCheckTask> check
    ) {
        Configuration compileClasspath = project.getConfigurations().getByName("compileClasspath");
        Provider<List<String>> adapters = project.provider(() -> {
            TreeSet<String> coordinates = new TreeSet<>();
            compileClasspath.getAllDependencies().forEach(dependency -> {
                if (dependency.getGroup() == null) {
                    return;
                }
                String coordinate = dependency.getGroup() + ":" + dependency.getName();
                if (isSupportedAdapter(coordinate)) {
                    coordinates.add(coordinate);
                }
            });
            return List.copyOf(coordinates);
        });
        ArtifactCollection registryArtifacts = compileClasspath.getIncoming().artifactView(view -> {
            view.lenient(true);
            view.attributes(attributes -> attributes.attribute(
                LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE,
                project.getObjects().named(LibraryElements.class, LibraryElements.JAR)
            ));
        }).getArtifacts();
        Provider<List<String>> artifactIdentities = registryArtifacts.getResolvedArtifacts().map(artifacts ->
            artifacts.stream()
                .map(artifact -> artifact.getId().getComponentIdentifier().getDisplayName())
                .sorted()
                .collect(Collectors.toList())
        );
        Provider<Map<String, String>> artifactLabels = registryArtifacts.getResolvedArtifacts().map(artifacts -> {
            LinkedHashMap<String, String> labels = new LinkedHashMap<>();
            artifacts.stream()
                .sorted(java.util.Comparator.comparing(artifact ->
                    artifact.getFile().toPath().toAbsolutePath().normalize().toString()
                ))
                .forEach(artifact -> labels.put(
                    artifact.getFile().toPath().toAbsolutePath().normalize().toString(),
                    artifact.getId().getComponentIdentifier().getDisplayName()
                ));
            return labels;
        });
        generate.configure(task -> {
            task.getDeclaredAdapterCoordinates().set(adapters);
            task.getDependencyRegistryArtifacts().from(registryArtifacts.getArtifactFiles());
            task.getDependencyArtifactIdentities().set(artifactIdentities);
            task.getDependencyArtifactLabels().set(artifactLabels);
        });
        check.configure(task -> {
            task.getDeclaredAdapterCoordinates().set(adapters);
            task.getDependencyRegistryArtifacts().from(registryArtifacts.getArtifactFiles());
            task.getDependencyArtifactIdentities().set(artifactIdentities);
            task.getDependencyArtifactLabels().set(artifactLabels);
        });
    }

    private static boolean isSupportedAdapter(String coordinate) {
        return "sh.repost:repost-client-spring-boot-starter".equals(coordinate)
            || "sh.repost:repost-client-cdi".equals(coordinate);
    }

    private static Configuration nativeEngineArtifacts(
        Project project,
        RepostSdkExtension extension
    ) {
        Configuration configuration = project.getConfigurations().create("repostNativeEngine");
        configuration.setCanBeConsumed(false);
        configuration.setCanBeResolved(true);
        configuration.setTransitive(false);
        configuration.setVisible(false);
        Provider<String> classifier = project.getProviders().systemProperty("os.name")
            .orElse("")
            .zip(
                project.getProviders().systemProperty("os.arch").orElse(""),
                NativeEngineArtifacts::classifier
            );
        Provider<String> archive = extension.getEngineVersion().zip(
            classifier,
            (version, selected) ->
                "sh.repost:repost-schema-engine:" + version + ":" + selected + "@zip"
        );
        Provider<String> manifest = extension.getEngineVersion().map(version ->
            "sh.repost:repost-schema-engine:" + version + ":checksums@json"
        );
        Provider<String> signature = extension.getEngineVersion().map(version ->
            "sh.repost:repost-schema-engine:" + version + ":checksums@sig"
        );
        project.getDependencies().addProvider(configuration.getName(), archive);
        project.getDependencies().addProvider(configuration.getName(), manifest);
        project.getDependencies().addProvider(configuration.getName(), signature);
        return configuration;
    }

    private static Provider<List<File>> generatorOutputFiles(
        Project project,
        RepostSdkExtension extension,
        boolean source,
        boolean isolatedCheck
    ) {
        return project.provider(() -> {
            if (extension.getSchemaMode().get() != RepostSchemaMode.GENERATE) {
                return Collections.emptyList();
            }
            return enabledGenerators(extension).stream()
                .map(generator -> {
                    if (!isolatedCheck) {
                        return source
                            ? generator.getSourceOutputDirectory().getAsFile().get()
                            : generator.getResourceOutputDirectory().getAsFile().get();
                    }
                    String relative = source
                        ? ".repost-sdk-check/sources/" + generator.getName() + "/" + language(generator.getName())
                        : ".repost-sdk-check/resources/" + generator.getName();
                    return project.getLayout().getBuildDirectory().dir(relative).get().getAsFile();
                })
                .collect(Collectors.toList());
        });
    }

    private static Provider<Map<String, String>> generatorOutputPaths(
        Project project,
        RepostSdkExtension extension,
        boolean source,
        boolean isolatedCheck
    ) {
        return project.provider(() -> {
            if (extension.getSchemaMode().get() != RepostSchemaMode.GENERATE) {
                return Collections.emptyMap();
            }
            LinkedHashMap<String, String> outputs = new LinkedHashMap<>();
            enabledGenerators(extension).stream()
                .forEach(generator -> {
                    File output;
                    if (!isolatedCheck) {
                        output = source
                            ? generator.getSourceOutputDirectory().getAsFile().get()
                            : generator.getResourceOutputDirectory().getAsFile().get();
                    } else {
                        String relative = source
                            ? ".repost-sdk-check/sources/" + generator.getName() + "/" + language(generator.getName())
                            : ".repost-sdk-check/resources/" + generator.getName();
                        output = project.getLayout().getBuildDirectory().dir(relative).get().getAsFile();
                    }
                    outputs.put(
                        generator.getName(),
                        output.toPath().toAbsolutePath().normalize().toString()
                    );
                });
            return outputs;
        });
    }

    private static String language(String generatorName) {
        if ("javaSdk".equals(generatorName)) {
            return "java";
        }
        if ("kotlinSdk".equals(generatorName)) {
            return "kotlin";
        }
        throw new IllegalArgumentException("unsupported Repost JVM generator: " + generatorName);
    }

    private static List<RepostGeneratorSpec> enabledGenerators(RepostSdkExtension extension) {
        return extension.getGenerators().stream()
            .filter(generator -> generator.getEnabled().get())
            .sorted(java.util.Comparator.comparing(RepostGeneratorSpec::getName))
            .collect(Collectors.toList());
    }

    private static List<String> enabledGeneratorNames(RepostSdkExtension extension) {
        return enabledGenerators(extension).stream()
            .map(RepostGeneratorSpec::getName)
            .collect(Collectors.toList());
    }
}
