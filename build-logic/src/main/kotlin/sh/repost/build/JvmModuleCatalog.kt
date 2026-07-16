package sh.repost.build

internal enum class JvmModuleKind {
    INTERNAL_ENGINE,
    JAVA_LIBRARY,
    KOTLIN_LIBRARY,
    JAVA_INTEGRATION,
    MAVEN_PLUGIN,
    GRADLE_PLUGIN,
    PLATFORM,
    NATIVE_DISTRIBUTION,
    JAVA_FIXTURE,
    KOTLIN_FIXTURE,
}

internal data class JvmModuleSpec(
    val name: String,
    val kind: JvmModuleKind,
    val javaRelease: Int?,
    val automaticModuleName: String? = null,
    val publishedCoordinate: String? = null,
    val allowedProjectDependencies: Set<String> = emptySet(),
    val gradlePluginId: String? = null,
    val implementedArtifact: Boolean = false,
)

internal object JvmModuleCatalog {
    const val GROUP = "sh.repost"

    val modules: Map<String, JvmModuleSpec> = listOf(
        JvmModuleSpec("repost-http-engine", JvmModuleKind.INTERNAL_ENGINE, 11),
        JvmModuleSpec(
            "repost-client",
            JvmModuleKind.JAVA_LIBRARY,
            11,
            "sh.repost.client",
            "$GROUP:repost-client",
            implementedArtifact = true,
        ),
        JvmModuleSpec(
            "repost-client-kotlin",
            JvmModuleKind.KOTLIN_LIBRARY,
            11,
            "sh.repost.client.kotlin",
            "$GROUP:repost-client-kotlin",
            setOf("repost-client"),
            implementedArtifact = true,
        ),
        JvmModuleSpec(
            "repost-client-test",
            JvmModuleKind.JAVA_LIBRARY,
            11,
            "sh.repost.client.test",
            "$GROUP:repost-client-test",
            setOf("repost-client"),
            implementedArtifact = true,
        ),
        JvmModuleSpec(
            "repost-client-micrometer",
            JvmModuleKind.JAVA_LIBRARY,
            11,
            "sh.repost.client.micrometer",
            "$GROUP:repost-client-micrometer",
            setOf("repost-client"),
            implementedArtifact = true,
        ),
        JvmModuleSpec(
            "repost-client-opentelemetry",
            JvmModuleKind.JAVA_LIBRARY,
            11,
            "sh.repost.client.opentelemetry",
            "$GROUP:repost-client-opentelemetry",
            setOf("repost-client"),
            implementedArtifact = true,
        ),
        JvmModuleSpec(
            "repost-client-spring-boot-starter",
            JvmModuleKind.JAVA_INTEGRATION,
            17,
            "sh.repost.client.spring.boot.starter",
            "$GROUP:repost-client-spring-boot-starter",
            setOf(
                "repost-client",
                "repost-client-micrometer",
                "repost-client-opentelemetry",
                "repost-client-test",
            ),
            implementedArtifact = true,
        ),
        JvmModuleSpec(
            "repost-client-cdi",
            JvmModuleKind.JAVA_INTEGRATION,
            17,
            "sh.repost.client.cdi",
            "$GROUP:repost-client-cdi",
            setOf("repost-client", "repost-client-micrometer", "repost-client-opentelemetry"),
            implementedArtifact = true,
        ),
        JvmModuleSpec("repost-bom", JvmModuleKind.PLATFORM, null, publishedCoordinate = "$GROUP:repost-bom", allowedProjectDependencies = publicLibraryNames()),
        JvmModuleSpec("repost-schema-engine", JvmModuleKind.NATIVE_DISTRIBUTION, null, publishedCoordinate = "$GROUP:repost-schema-engine"),
        JvmModuleSpec(
            "repost-maven-plugin",
            JvmModuleKind.MAVEN_PLUGIN,
            11,
            "sh.repost.maven.plugin",
            "$GROUP:repost-maven-plugin",
            implementedArtifact = true,
        ),
        JvmModuleSpec(
            "repost-gradle-plugin",
            JvmModuleKind.GRADLE_PLUGIN,
            11,
            "sh.repost.gradle.plugin",
            "$GROUP:repost-gradle-plugin",
            gradlePluginId = "sh.repost.sdk",
            implementedArtifact = true,
        ),
        JvmModuleSpec(
            "fixture-java",
            JvmModuleKind.JAVA_FIXTURE,
            11,
            allowedProjectDependencies = setOf("repost-client", "repost-client-test"),
        ),
        JvmModuleSpec(
            "fixture-kotlin",
            JvmModuleKind.KOTLIN_FIXTURE,
            11,
            allowedProjectDependencies = setOf("repost-client", "repost-client-kotlin", "repost-client-test"),
        ),
    ).associateBy(JvmModuleSpec::name)

    val publicLibraryModules: Set<String> = publicLibraryNames()

    val unimplementedArtifactModules: Set<String> = modules.values
        .filter { it.javaRelease != null && !it.implementedArtifact }
        .mapTo(sortedSetOf(), JvmModuleSpec::name)

    private fun publicLibraryNames(): Set<String> = setOf(
        "repost-client",
        "repost-client-kotlin",
        "repost-client-test",
        "repost-client-micrometer",
        "repost-client-opentelemetry",
        "repost-client-spring-boot-starter",
        "repost-client-cdi",
    )

    fun expectedArtifactIdentities(): List<JvmArtifactIdentity> = modules.values.map { spec ->
        JvmArtifactIdentity(
            projectName = spec.name,
            coordinate = spec.publishedCoordinate,
            archiveBaseName = spec.javaRelease?.let { spec.publishedCoordinate?.substringAfter(':') ?: spec.name },
            automaticModuleName = spec.automaticModuleName,
            gradlePluginIds = setOfNotNull(spec.gradlePluginId),
        )
    }
}
