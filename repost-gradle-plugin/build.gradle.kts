import org.gradle.api.tasks.Delete
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.named
import org.gradle.kotlin.dsl.register
import org.gradle.plugin.compatibility.compatibility

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

val testPluginRepository = rootProject.layout.buildDirectory.dir("repositories/test-plugin")
publishing {
    repositories {
        maven {
            name = "testPlugin"
            url = uri(testPluginRepository)
        }
    }
}

val cleanTestPluginRepository = tasks.register<Delete>("cleanTestPluginRepository") {
    delete(testPluginRepository)
}
tasks.matching { task -> task.name == "publishAllPublicationsToTestPluginRepository" }
    .configureEach { dependsOn(cleanTestPluginRepository) }

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
val distributionTest = tasks.register<Test>("distributionTest") {
    group = "verification"
    description = "Validates and consumes the complete plugin publication from an isolated repository."
    dependsOn("publishAllPublicationsToTestPluginRepository")
    testClassesDirs = testSourceSet.get().output.classesDirs
    classpath = testSourceSet.get().runtimeClasspath
    filter.includeTestsMatching("sh.repost.gradle.GradlePluginDistributionTest")
    systemProperty("repost.testPluginRepository", testPluginRepository.get().asFile.absolutePath)
    systemProperty("repost.testPluginVersion", project.version.toString())
}

if (version.toString() == "1.0.0") {
    tasks.named("check") {
        dependsOn(distributionTest)
    }
}
