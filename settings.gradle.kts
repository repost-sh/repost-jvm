pluginManagement {
    repositories {
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
    }
}

rootProject.name = "repost-jvm"

includeBuild("build-logic")

// Keep the approved mature-v1 product topology visible from the first commit. Deferred
// integration source may remain in the repository without entering the GA build graph.
include(
    "repost-http-engine",
    "repost-client",
    "repost-client-kotlin",
    "repost-client-test",
    "repost-client-micrometer",
    "repost-client-opentelemetry",
    "repost-client-spring-boot-starter",
    "repost-client-cdi",
    "repost-bom",
    "repost-schema-engine",
    "repost-maven-plugin",
    "repost-gradle-plugin",
    "fixture-java",
    "fixture-kotlin",
)
