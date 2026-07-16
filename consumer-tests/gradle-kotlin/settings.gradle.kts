pluginManagement {
    plugins {
        id("org.jetbrains.kotlin.jvm") version providers.gradleProperty("kotlinVersion").get()
    }
    repositories {
        maven { url = uri(providers.gradleProperty("repostRepository").get()) }
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven { url = uri(providers.gradleProperty("repostRepository").get()) }
        mavenCentral()
    }
}
rootProject.name = "repost-gradle-kotlin-consumer"
