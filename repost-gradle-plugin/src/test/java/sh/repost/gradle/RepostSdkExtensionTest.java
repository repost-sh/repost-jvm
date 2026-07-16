package sh.repost.gradle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.time.Duration;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import org.gradle.api.GradleException;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.DependencyArtifact;
import org.gradle.api.artifacts.ExternalModuleDependency;
import org.gradle.api.plugins.JavaPluginExtension;
import org.gradle.api.tasks.CacheableTask;
import org.gradle.api.tasks.SourceSet;
import org.gradle.testfixtures.ProjectBuilder;
import org.gradle.work.DisableCachingByDefault;
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension;
import org.jetbrains.kotlin.gradle.plugin.KotlinSourceSet;
import org.junit.jupiter.api.Test;

final class RepostSdkExtensionTest {
    @Test
    void rejectsOnlyTheUnsupportedKotlinTwoFourGradlePair() {
        GradleException failure = assertThrows(
            GradleException.class,
            () -> RepostSdkPlugin.validateKotlinGradlePair("2.4.0-release-1", "8.12.1", true)
        );

        assertTrue(failure.getMessage().contains("Gradle 9.5"));
        assertDoesNotThrow(() ->
            RepostSdkPlugin.validateKotlinGradlePair("2.4.0-release-1", "8.12.1", false)
        );
    }

    @Test
    void exposesLazyTypedDefaultsWithoutReadingTheSchema() {
        Project project = ProjectBuilder.builder().withProjectDir(new File("build/test-project")).build();

        project.getPlugins().apply(RepostSdkPlugin.class);

        RepostSdkExtension extension = project.getExtensions().getByType(RepostSdkExtension.class);
        assertEquals(RepostSchemaMode.GENERATE, extension.getSchemaMode().get());
        assertEquals(RepostIntegration.AUTO, extension.getIntegration().get());
        assertEquals("0.9.0", extension.getEngineVersion().get());
        assertEquals(Duration.ofMinutes(5), extension.getExecutionTimeout().get());
        assertEquals(project.file("repost/schema.repost"), extension.getSchemaFile().getAsFile().get());
        assertTrue(extension.getEnvironmentInputs().get().isEmpty());
        assertFalse(extension.getCheckAgainst().isPresent());

        assertEquals(
            project.file("build/generated/sources/repost/javaSdk/java"),
            extension.getGenerators().getByName("javaSdk").getSourceOutputDirectory().getAsFile().get()
        );
        assertTrue(extension.getGenerators().getByName("javaSdk").getEnabled().get());
        assertEquals(
            project.file("build/generated/resources/repost/javaSdk"),
            extension.getGenerators().getByName("javaSdk").getResourceOutputDirectory().getAsFile().get()
        );
        assertEquals(
            project.file("build/generated/sources/repost/kotlinSdk/kotlin"),
            extension.getGenerators().getByName("kotlinSdk").getSourceOutputDirectory().getAsFile().get()
        );
    }

    @Test
    void disablesOneGeneratorWithoutRemovingItsSourceSetProvider() {
        Project project = ProjectBuilder.builder().build();
        project.getPlugins().apply(RepostSdkPlugin.class);
        RepostSdkExtension extension = project.getExtensions().getByType(RepostSdkExtension.class);
        extension.getGenerators().getByName("javaSdk").getEnabled().set(false);

        RepostGenerateTask generate = (RepostGenerateTask) project.getTasks().getByName("repostGenerate");
        assertEquals(Arrays.asList("kotlinSdk"), generate.getGeneratorNames().get());
        assertEquals(
            Set.of(project.file("build/generated/sources/repost/kotlinSdk/kotlin")),
            generate.getSourceOutputDirectories().getFiles()
        );
    }

    @Test
    void keepsProviderBackedConfigurationUnevaluatedUntilConsumed() {
        Project project = ProjectBuilder.builder().build();
        project.getPlugins().apply(RepostSdkPlugin.class);
        RepostSdkExtension extension = project.getExtensions().getByType(RepostSdkExtension.class);
        AtomicBoolean queried = new AtomicBoolean();

        extension.getEngineVersion().set(project.provider(() -> {
            queried.set(true);
            return "0.9.1";
        }));

        assertFalse(queried.get());
        assertEquals("0.9.1", extension.getEngineVersion().get());
        assertTrue(queried.get());
    }

    @Test
    void exposesOneStableExtensionInstanceAndLazyCacheableGenerationTasks() {
        Project project = ProjectBuilder.builder().build();
        project.getPlugins().apply(RepostSdkPlugin.class);

        RepostSdkExtension byName = (RepostSdkExtension) project.getExtensions().getByName("repostSdk");
        assertSame(byName, project.getExtensions().getByType(RepostSdkExtension.class));
        RepostGenerateTask generate = (RepostGenerateTask) project.getTasks().getByName("repostGenerate");
        RepostVerifyGeneratedOutputsTask verify =
            (RepostVerifyGeneratedOutputsTask) project.getTasks().getByName("repostVerifyGeneratedOutputs");
        RepostGenerateCheckTask check =
            (RepostGenerateCheckTask) project.getTasks().getByName("repostGenerateCheck");
        assertTrue(generate.getClass().getSuperclass().isAnnotationPresent(CacheableTask.class));
        assertTrue(verify.getClass().isAnnotationPresent(DisableCachingByDefault.class));
        assertTrue(check.getClass().getSuperclass().isAnnotationPresent(CacheableTask.class));
        assertTrue(dependencies(project, "repostVerifyGeneratedOutputs").contains(generate));
        assertEquals(Arrays.asList("javaSdk", "kotlinSdk"), generate.getGeneratorNames().get());
        assertEquals(byName.getSchemaFile().getAsFile().get(), generate.getSchemaFile().getAsFile().get());
        assertEquals(byName.getEngineVersion().get(), generate.getEngineVersion().get());
        assertEquals(byName.getIntegration().get(), generate.getIntegration().get());
        assertEquals(byName.getEnvironmentInputs().get(), generate.getEnvironmentInputs().get());
        assertEquals("1.0.0", generate.getRuntimeVersion().get());
        assertEquals(1, generate.getProtocolVersion().get());
        assertEquals(2, generate.getDescriptorVersion().get());
        assertEquals(project.getGradle().getStartParameter().isOffline(), generate.getOffline().get());
        assertEquals(
            project.file("build/generated/sources/repostRegistry/main/java"),
            generate.getAggregateSourceOutputDirectory().getAsFile().get()
        );
        assertEquals(
            project.file("build/generated/resources/repostRegistry/main"),
            generate.getAggregateResourceOutputDirectory().getAsFile().get()
        );
        assertEquals(project.file("build/.repost-sdk-state/generate"), generate.getLocalStateDirectory().getAsFile().get());
        assertEquals(project.file("build/.repost-sdk-state/check"), check.getLocalStateDirectory().getAsFile().get());
        assertEquals(
            Set.of(
                project.file("build/generated/sources/repost/javaSdk/java"),
                project.file("build/generated/sources/repost/kotlinSdk/kotlin")
            ),
            new HashSet<>(generate.getSourceOutputDirectories().getFiles())
        );
        assertEquals(
            Set.of(
                project.file("build/.repost-sdk-check/sources/javaSdk/java"),
                project.file("build/.repost-sdk-check/sources/kotlinSdk/kotlin")
            ),
            new HashSet<>(check.getSourceOutputDirectories().getFiles())
        );
        assertEquals(
            project.file("build/.repost-sdk-check/sources/repostRegistry/main/java"),
            check.getAggregateSourceOutputDirectory().getAsFile().get()
        );
        assertFalse(check.getCheckAgainst().isPresent());
        assertFalse(generate.getEngineExecutable().isPresent());
    }

    @Test
    void declaresOnlyTheSelectedNativeEngineAndSignedManifestArtifacts() {
        Project project = ProjectBuilder.builder().build();
        project.getPlugins().apply(RepostSdkPlugin.class);

        Configuration configuration = project.getConfigurations().getByName("repostNativeEngine");
        assertTrue(configuration.isCanBeResolved());
        assertFalse(configuration.isCanBeConsumed());
        assertFalse(configuration.isTransitive());
        assertFalse(configuration.isVisible());

        Set<String> coordinates = new HashSet<>();
        for (org.gradle.api.artifacts.Dependency dependency : configuration.getAllDependencies()) {
            ExternalModuleDependency external = (ExternalModuleDependency) dependency;
            DependencyArtifact artifact = external.getArtifacts().iterator().next();
            coordinates.add(
                external.getGroup() + ":" + external.getName() + ":" + external.getVersion() + ":" +
                    artifact.getClassifier() + "@" + artifact.getExtension()
            );
        }
        assertEquals(
            Set.of(
                "sh.repost:repost-schema-engine:0.9.0:" +
                    sh.repost.buildplugin.internal.NativeEngineArtifacts.classifier(
                        System.getProperty("os.name"), System.getProperty("os.arch")
                    ) + "@zip",
                "sh.repost:repost-schema-engine:0.9.0:checksums@json",
                "sh.repost:repost-schema-engine:0.9.0:checksums@sig"
            ),
            coordinates
        );
    }

    @Test
    void wiresJavaSourcesResourcesAndLifecycleTasksWhenJavaIsAppliedFirst() {
        assertJavaWiring(true);
    }

    @Test
    void wiresJavaSourcesResourcesAndLifecycleTasksWhenRepostIsAppliedFirst() {
        assertJavaWiring(false);
    }

    @Test
    void wiresKotlinSourcesAndCompilationThroughTheKotlinSourceSetApi() {
        Project project = ProjectBuilder.builder().build();
        project.getPlugins().apply(RepostSdkPlugin.class);
        project.getPlugins().apply("org.jetbrains.kotlin.jvm");

        KotlinSourceSet main = project.getExtensions().getByType(KotlinJvmProjectExtension.class)
            .getSourceSets().getByName(SourceSet.MAIN_SOURCE_SET_NAME);
        assertTrue(main.getKotlin().getSrcDirs().contains(
            project.file("build/generated/sources/repost/kotlinSdk/kotlin")
        ));
        assertTrue(dependencies(project, "compileKotlin").contains(
            project.getTasks().getByName("repostVerifyGeneratedOutputs")
        ));
    }

    private static void assertJavaWiring(boolean javaFirst) {
        Project project = ProjectBuilder.builder().build();
        if (javaFirst) {
            project.getPlugins().apply("java");
        }
        project.getPlugins().apply(RepostSdkPlugin.class);
        if (!javaFirst) {
            project.getPlugins().apply("java");
        }

        SourceSet main = project.getExtensions().getByType(JavaPluginExtension.class)
            .getSourceSets().getByName(SourceSet.MAIN_SOURCE_SET_NAME);
        assertTrue(main.getJava().getSrcDirs().contains(
            project.file("build/generated/sources/repost/javaSdk/java")
        ));
        assertTrue(main.getJava().getSrcDirs().contains(
            project.file("build/generated/sources/repostRegistry/main/java")
        ));
        assertFalse(main.getJava().getSrcDirs().contains(
            project.file("build/generated/sources/repost/kotlinSdk/kotlin")
        ));
        assertTrue(main.getResources().getSrcDirs().contains(
            project.file("build/generated/resources/repost/javaSdk")
        ));
        assertTrue(main.getResources().getSrcDirs().contains(
            project.file("build/generated/resources/repost/kotlinSdk")
        ));
        assertTrue(main.getResources().getSrcDirs().contains(
            project.file("build/generated/resources/repostRegistry/main")
        ));

        Task generate = project.getTasks().getByName("repostGenerate");
        Task verify = project.getTasks().getByName("repostVerifyGeneratedOutputs");
        Task generateCheck = project.getTasks().getByName("repostGenerateCheck");
        assertTrue(dependencies(project, verify.getName()).contains(generate));
        assertTrue(dependencies(project, main.getCompileJavaTaskName()).contains(verify));
        assertTrue(dependencies(project, main.getProcessResourcesTaskName()).contains(verify));
        assertTrue(dependencies(project, "check").contains(generateCheck));
    }

    private static Set<? extends Task> dependencies(Project project, String taskName) {
        Task task = project.getTasks().getByName(taskName);
        return task.getTaskDependencies().getDependencies(task);
    }
}
