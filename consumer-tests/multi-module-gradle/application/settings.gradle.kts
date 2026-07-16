pluginManagement {
    repositories {
        maven { url = uri(providers.gradleProperty("repostRepository").get()) }
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven { url = uri(providers.gradleProperty("repostRepository").get()) }
        maven { url = uri(providers.gradleProperty("consumerRepository").get()) }
        mavenCentral()
    }
}
rootProject.name = "repost-gradle-aggregate-consumer"
include("schema-a", "app")
if (providers.gradleProperty("includeSchemaB").orElse("true").get().toBoolean()) include("schema-b")
