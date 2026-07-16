pluginManagement {
    repositories { gradlePluginPortal() }
}

dependencyResolutionManagement {
    repositories {
        maven { url = uri(providers.gradleProperty("stagedRepository").get()) }
        mavenCentral()
    }
}

rootProject.name = "repost-spring-certification"
