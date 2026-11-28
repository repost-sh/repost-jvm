pluginManagement {
    repositories { gradlePluginPortal() }
}

dependencyResolutionManagement {
    repositories {
        maven {
            url = uri(providers.gradleProperty("stagedRepository").get())
            content { includeGroup("sh.repost") }
        }
        mavenCentral { content { excludeGroup("sh.repost") } }
    }
}

rootProject.name = "repost-spring-certification"
