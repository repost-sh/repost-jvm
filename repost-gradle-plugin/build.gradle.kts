import org.gradle.api.publish.maven.tasks.PublishToMavenRepository
import org.gradle.api.tasks.Delete
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.named
import org.gradle.kotlin.dsl.register
import org.gradle.kotlin.dsl.withType
import org.gradle.plugin.compatibility.compatibility

// Locked like the root build script's classpath; see the comment there.
buildscript {
    configurations.classpath {
        resolutionStrategy.activateDependencyLocking()
    }
}

plugins {
    alias(libs.plugins.gradle.plugin.publish)
}

gradlePlugin {
    website.set("https://repost.sh")
    vcsUrl.set("https://github.com/repost-sh/repost")
    plugins.named("repostSdk") {
        tags.set(listOf("code-generation", "java", "kotlin", "sdk"))
        compatibility {
            features {
                configurationCache = true
            }
        }
    }
}

val testPluginDirectory = rootProject.layout.buildDirectory.dir("repositories/test-plugin")
val testPluginRepository = publishing.repositories.maven {
    name = "testPlugin"
    url = uri(testPluginDirectory)
}

val cleanTestPluginRepository = tasks.register<Delete>("cleanTestPluginRepository") {
    delete(testPluginDirectory)
}
// Each publication's write, not only their aggregate, follows the clean: with
// the configuration cache they run in parallel. maven-publish sets a task's
// repository after this action runs, so the dependency reads it when the task
// graph is built.
tasks.withType<PublishToMavenRepository>().configureEach {
    dependsOn(provider { if (repository.name == testPluginRepository.name) listOf(cleanTestPluginRepository) else emptyList() })
}

tasks.named<Test>("test") {
    filter.excludeTestsMatching("sh.repost.gradle.GradlePluginDistributionTest")
    systemProperty(
        "repost.entrypointParityManifest",
        rootProject.layout.projectDirectory.file(
            "../../repost-rs/repost-schema/cli/tests/fixtures/jvm_entrypoint_parity/expected-tree-hashes.json",
        ).asFile.absolutePath,
    )
}

val testSourceSet = extensions.getByType<SourceSetContainer>().named("test")
tasks.register<Test>("distributionTest") {
    group = "verification"
    description = "Validates and consumes the complete plugin publication from an isolated repository."
    dependsOn("publishAllPublicationsToTestPluginRepository")
    testClassesDirs = testSourceSet.get().output.classesDirs
    classpath = testSourceSet.get().runtimeClasspath
    filter.includeTestsMatching("sh.repost.gradle.GradlePluginDistributionTest")
    // Without the maven-metadata files: each publish stamps them with its time.
    inputs.files(fileTree(testPluginDirectory) { exclude("**/maven-metadata*") })
        .withPropertyName("testPluginRepository")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    systemProperty("repost.testPluginRepository", testPluginDirectory.get().asFile.absolutePath)
}
