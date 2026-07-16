package sh.repost.build

internal data class JvmDependencyDeclaration(
    val module: String,
    val configuration: String,
    val target: String,
)

internal data class JvmResolvedDependencyPath(
    val module: String,
    val configuration: String,
    val path: List<String>,
)

internal object JvmDependencyGraphPolicy {
    val resolvedGraphModules: Set<String> = JvmModuleCatalog.modules.values
        .filter { spec -> spec.publishedCoordinate != null && spec.javaRelease != null }
        .mapTo(sortedSetOf(), JvmModuleSpec::name)

    fun violations(
        declarations: Collection<JvmDependencyDeclaration>,
        resolvedPaths: Collection<JvmResolvedDependencyPath>,
    ): List<String> {
        val failures = mutableListOf<String>()
        declarations.forEach { declaration ->
            val modulePolicy = allowedDeclarations[declaration.module].orEmpty()
            val allowedTargets = modulePolicy[declaration.configuration]
            val targetKey = declaration.target.split(':').let { parts ->
                if (parts.size >= 3) parts.take(2).joinToString(":") else declaration.target
            }
            when {
                allowedTargets == null -> failures +=
                    "Forbidden JVM dependency declaration :${declaration.module}:${declaration.configuration} -> " +
                        "${declaration.target}; configuration is not part of the module policy"
                targetKey !in allowedTargets -> failures +=
                    "Forbidden JVM dependency declaration :${declaration.module}:${declaration.configuration} -> " +
                        "${declaration.target}; allowed targets are ${allowedTargets.sorted()}"
            }
        }
        resolvedPaths.forEach { resolvedPath ->
            resolvedPath.path.mapNotNull(::jacksonCoreVersion).forEach { version ->
                if (!isSupportedJacksonCoreVersion(version)) {
                    failures += "Unsupported Jackson Core version $version in " +
                        ":${resolvedPath.module}:${resolvedPath.configuration}; " +
                        "supported range is >=2.18.0 and <3.0.0: " +
                        resolvedPath.path.joinToString(" -> ")
                }
            }
            val allowed = allowedResolvedComponents[resolvedPath.module]
                ?.get(resolvedPath.configuration)
                .orEmpty()
            val selected = resolvedPath.path.lastOrNull().orEmpty()
            if (selected.startsWith(":")) return@forEach
            if (selected.substringBeforeLast(':') !in allowed) {
                failures += "Forbidden resolved JVM dependency path for " +
                    ":${resolvedPath.module}:${resolvedPath.configuration}: ${resolvedPath.path.joinToString(" -> ")}"
            }
        }
        return failures.distinct().sorted()
    }

    private fun jacksonCoreVersion(component: String): String? {
        val parts = component.split(':')
        return if (
            parts.size == 3 &&
            parts[0] == "com.fasterxml.jackson.core" &&
            parts[1] == "jackson-core"
        ) {
            parts[2]
        } else {
            null
        }
    }

    private fun isSupportedJacksonCoreVersion(version: String): Boolean {
        val release = version.substringBefore('-').substringBefore('+')
        val parts = release.split('.')
        if (parts.any { part -> part.toIntOrNull() == null }) return false
        val major = parts.getOrNull(0)?.toIntOrNull() ?: return false
        val minor = parts.getOrNull(1)?.toIntOrNull() ?: return false
        val patch = parts.getOrNull(2)?.toIntOrNull() ?: 0
        if (major != 2 || minor < 18) return false
        return minor > 18 || patch > 0 || version == release
    }

    fun kotlinCoreViolations(
        familyVersion: String,
        declarations: Collection<JvmDependencyDeclaration>,
    ): List<String> {
        val coreDeclarations = declarations.filter { declaration ->
            declaration.module == "repost-client-kotlin" &&
                declaration.target.substringBeforeLast(':') == "sh.repost:repost-client"
        }
        val expectedTarget = "sh.repost:repost-client:$familyVersion"
        if (coreDeclarations.size == 1 &&
            coreDeclarations.single().configuration == "api" &&
            coreDeclarations.single().target == expectedTarget
        ) {
            return emptyList()
        }
        val actual = coreDeclarations.joinToString { "${it.configuration} -> ${it.target}" }.ifEmpty { "none" }
        return listOf(
            "Kotlin core dependency must be :repost-client at family version $familyVersion through api; found $actual",
        )
    }

    fun bomConstraintViolations(expected: Set<String>, actual: Set<String>): List<String> =
        (expected - actual).sorted().map { "BOM is missing constraint $it" } +
            (actual - expected).sorted().map { "BOM exposes unexpected constraint $it" }

    private val allowedDeclarations: Map<String, Map<String, Set<String>>> = mapOf(
        "repost-client" to mapOf(
            "api" to setOf("org.jspecify:jspecify"),
            "implementation" to setOf("com.fasterxml.jackson.core:jackson-core"),
            "compileOnly" to emptySet(),
            "runtimeOnly" to emptySet(),
            "testImplementation" to setOf("org.junit:junit-bom", "org.junit.jupiter:junit-jupiter"),
            "testRuntimeOnly" to setOf("org.junit.platform:junit-platform-launcher"),
        ),
        "repost-client-kotlin" to mapOf(
            "api" to setOf(
                "sh.repost:repost-client",
                "org.jetbrains.kotlin:kotlin-bom",
                "org.jetbrains.kotlin:kotlin-stdlib",
            ),
            "implementation" to setOf("org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm"),
            "compileOnly" to emptySet(),
            "runtimeOnly" to emptySet(),
            "testImplementation" to setOf("org.junit:junit-bom", "org.junit.jupiter:junit-jupiter"),
            "testRuntimeOnly" to setOf("org.junit.platform:junit-platform-launcher"),
        ),
        "repost-client-test" to mapOf(
            "api" to setOf("sh.repost:repost-client"),
            "testImplementation" to setOf("org.junit:junit-bom", "org.junit.jupiter:junit-jupiter"),
            "testRuntimeOnly" to setOf("org.junit.platform:junit-platform-launcher"),
        ),
        "repost-client-micrometer" to mapOf(
            "api" to setOf("sh.repost:repost-client", "io.micrometer:micrometer-core"),
            "testImplementation" to setOf(
                "sh.repost:repost-client-test",
                "org.junit:junit-bom",
                "org.junit.jupiter:junit-jupiter",
            ),
            "testRuntimeOnly" to setOf("org.junit.platform:junit-platform-launcher"),
        ),
        "repost-client-opentelemetry" to mapOf(
            "api" to setOf("sh.repost:repost-client", "io.opentelemetry:opentelemetry-api"),
            "testImplementation" to setOf(
                "sh.repost:repost-client-test",
                "io.opentelemetry:opentelemetry-sdk",
                "io.opentelemetry:opentelemetry-sdk-testing",
                "org.junit:junit-bom",
                "org.junit.jupiter:junit-jupiter",
            ),
            "testRuntimeOnly" to setOf("org.junit.platform:junit-platform-launcher"),
        ),
        "repost-client-spring-boot-starter" to mapOf(
            "api" to setOf("sh.repost:repost-client"),
            "compileOnly" to setOf(
                "com.fasterxml.jackson.core:jackson-annotations",
                "io.micrometer:micrometer-core",
                "io.opentelemetry:opentelemetry-api",
                "org.springframework.boot:spring-boot-autoconfigure",
                "org.springframework.boot:spring-boot-health",
                "sh.repost:repost-client-micrometer",
                "sh.repost:repost-client-opentelemetry",
            ),
            "annotationProcessor" to setOf(
                "org.springframework.boot:spring-boot-configuration-processor",
            ),
            "testImplementation" to setOf(
                "com.fasterxml.jackson.core:jackson-annotations",
                "io.micrometer:micrometer-core",
                "io.opentelemetry:opentelemetry-api",
                "io.opentelemetry:opentelemetry-sdk",
                "io.opentelemetry:opentelemetry-sdk-testing",
                "org.assertj:assertj-core",
                "org.junit:junit-bom",
                "org.junit.jupiter:junit-jupiter",
                "org.springframework.boot:spring-boot-actuator",
                "org.springframework.boot:spring-boot-actuator-autoconfigure",
                "org.springframework.boot:spring-boot-autoconfigure",
                "org.springframework.boot:spring-boot-health",
                "org.springframework.boot:spring-boot-test",
                "org.yaml:snakeyaml",
                "sh.repost:repost-client-micrometer",
                "sh.repost:repost-client-opentelemetry",
                "sh.repost:repost-client-test",
            ),
            "testRuntimeOnly" to setOf("org.junit.platform:junit-platform-launcher"),
            "boot41TestRuntimeClasspath" to setOf(
                "sh.repost:repost-client-spring-boot-starter",
                "sh.repost:repost-client-micrometer",
                "sh.repost:repost-client-opentelemetry",
                "sh.repost:repost-client-test",
                "com.fasterxml.jackson.core:jackson-annotations",
                "io.micrometer:micrometer-core",
                "io.opentelemetry:opentelemetry-api",
                "io.opentelemetry:opentelemetry-sdk",
                "io.opentelemetry:opentelemetry-sdk-testing",
                "org.assertj:assertj-core",
                "org.junit:junit-bom",
                "org.junit.jupiter:junit-jupiter",
                "org.junit.platform:junit-platform-launcher",
                "org.springframework.boot:spring-boot-actuator",
                "org.springframework.boot:spring-boot-actuator-autoconfigure",
                "org.springframework.boot:spring-boot-autoconfigure",
                "org.springframework.boot:spring-boot-health",
                "org.springframework.boot:spring-boot-test",
                "org.yaml:snakeyaml",
            ),
        ),
        "repost-client-cdi" to mapOf(
            "api" to setOf("sh.repost:repost-client"),
            "compileOnly" to setOf(
                "jakarta.enterprise:jakarta.enterprise.cdi-api",
                "org.eclipse.microprofile.config:microprofile-config-api",
                "sh.repost:repost-client-micrometer",
                "sh.repost:repost-client-opentelemetry",
            ),
            "testImplementation" to setOf(
                "io.micrometer:micrometer-core",
                "io.opentelemetry:opentelemetry-api",
                "io.opentelemetry:opentelemetry-sdk",
                "io.opentelemetry:opentelemetry-sdk-testing",
                "jakarta.enterprise:jakarta.enterprise.cdi-api",
                "org.eclipse.microprofile.config:microprofile-config-api",
                "org.junit:junit-bom",
                "org.junit.jupiter:junit-jupiter",
                "sh.repost:repost-client-micrometer",
                "sh.repost:repost-client-opentelemetry",
            ),
            "testRuntimeOnly" to setOf("org.junit.platform:junit-platform-launcher"),
        ),
        "fixture-java" to mapOf(
            "implementation" to setOf("sh.repost:repost-client"),
            "testImplementation" to setOf(
                "sh.repost:repost-client-test",
                "org.junit:junit-bom",
                "org.junit.jupiter:junit-jupiter",
            ),
            "testRuntimeOnly" to setOf("org.junit.platform:junit-platform-launcher"),
        ),
        "fixture-kotlin" to mapOf(
            "implementation" to setOf(
                "sh.repost:repost-client-kotlin",
                "org.jetbrains.kotlin:kotlin-bom",
                "org.jetbrains.kotlin:kotlin-stdlib",
                "org.jspecify:jspecify",
            ),
            "testImplementation" to setOf(
                "sh.repost:repost-client-test",
                "org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm",
                "org.junit:junit-bom",
                "org.junit.jupiter:junit-jupiter",
            ),
            "testRuntimeOnly" to setOf("org.junit.platform:junit-platform-launcher"),
        ),
        "repost-gradle-plugin" to mapOf(
            "api" to emptySet(),
            "implementation" to emptySet(),
            "compileOnly" to setOf(
                "org.jetbrains.kotlin:kotlin-gradle-plugin",
                "org.jetbrains.kotlin:kotlin-gradle-plugin-api",
                "org.jetbrains.kotlin:kotlin-tooling-core",
            ),
            "runtimeOnly" to emptySet(),
            "testImplementation" to setOf(
                "org.jetbrains.kotlin:kotlin-gradle-plugin",
                "org.junit:junit-bom",
                "org.junit.jupiter:junit-jupiter",
            ),
            "testRuntimeOnly" to setOf("org.junit.platform:junit-platform-launcher"),
        ),
        "repost-maven-plugin" to mapOf(
            "api" to emptySet(),
            "implementation" to emptySet(),
            "compileOnly" to setOf(
                "org.apache.maven:maven-core",
                "org.apache.maven:maven-model",
                "org.apache.maven:maven-plugin-api",
                "org.apache.maven.resolver:maven-resolver-api",
                "org.apache.maven.plugin-tools:maven-plugin-annotations",
            ),
            "runtimeOnly" to emptySet(),
            "testImplementation" to setOf(
                "org.apache.maven:maven-core",
                "org.apache.maven:maven-model",
                "org.apache.maven:maven-plugin-api",
                "org.apache.maven.resolver:maven-resolver-api",
                "org.apache.maven.plugin-tools:maven-plugin-annotations",
                "org.junit:junit-bom",
                "org.junit.jupiter:junit-jupiter",
                "org.slf4j:slf4j-api",
            ),
            "testRuntimeOnly" to setOf("org.junit.platform:junit-platform-launcher"),
        ),
    )

    private val allowedResolvedComponents: Map<String, Map<String, Set<String>>> = mapOf(
        "repost-client" to mapOf(
            "compileClasspath" to setOf(
                "com.fasterxml.jackson.core:jackson-core",
                "com.fasterxml.jackson:jackson-bom",
                "org.jspecify:jspecify",
            ),
            "runtimeClasspath" to setOf(
                "com.fasterxml.jackson.core:jackson-core",
                "com.fasterxml.jackson:jackson-bom",
                "org.jspecify:jspecify",
            ),
        ),
        "repost-client-kotlin" to mapOf(
            "compileClasspath" to setOf(
                "org.jetbrains.kotlin:kotlin-bom",
                "org.jetbrains.kotlin:kotlin-stdlib",
                "org.jetbrains.kotlinx:kotlinx-coroutines-bom",
                "org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm",
                "org.jetbrains:annotations",
                "org.jspecify:jspecify",
            ),
            "runtimeClasspath" to setOf(
                "com.fasterxml.jackson.core:jackson-core",
                "com.fasterxml.jackson:jackson-bom",
                "org.jetbrains.kotlin:kotlin-bom",
                "org.jetbrains.kotlin:kotlin-stdlib",
                "org.jetbrains.kotlinx:kotlinx-coroutines-bom",
                "org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm",
                "org.jetbrains:annotations",
                "org.jspecify:jspecify",
            ),
        ),
        "repost-client-test" to mapOf(
            "compileClasspath" to setOf("org.jspecify:jspecify"),
            "runtimeClasspath" to setOf(
                "com.fasterxml.jackson.core:jackson-core",
                "com.fasterxml.jackson:jackson-bom",
                "org.jspecify:jspecify",
            ),
        ),
        "repost-client-micrometer" to mapOf(
            "compileClasspath" to setOf(
                "io.micrometer:micrometer-commons",
                "io.micrometer:micrometer-core",
                "io.micrometer:micrometer-observation",
                "org.jspecify:jspecify",
            ),
            "runtimeClasspath" to setOf(
                "com.fasterxml.jackson.core:jackson-core",
                "com.fasterxml.jackson:jackson-bom",
                "io.micrometer:micrometer-commons",
                "io.micrometer:micrometer-core",
                "io.micrometer:micrometer-observation",
                "org.hdrhistogram:HdrHistogram",
                "org.jspecify:jspecify",
                "org.latencyutils:LatencyUtils",
            ),
        ),
        "repost-client-opentelemetry" to mapOf(
            "compileClasspath" to setOf(
                "io.opentelemetry:opentelemetry-api",
                "io.opentelemetry:opentelemetry-common",
                "io.opentelemetry:opentelemetry-context",
                "org.jspecify:jspecify",
            ),
            "runtimeClasspath" to setOf(
                "com.fasterxml.jackson.core:jackson-core",
                "com.fasterxml.jackson:jackson-bom",
                "io.opentelemetry:opentelemetry-api",
                "io.opentelemetry:opentelemetry-common",
                "io.opentelemetry:opentelemetry-context",
                "org.jspecify:jspecify",
            ),
        ),
        "repost-client-spring-boot-starter" to mapOf(
            "compileClasspath" to setOf(
                "com.fasterxml.jackson.core:jackson-annotations",
                "commons-logging:commons-logging",
                "io.micrometer:micrometer-commons",
                "io.micrometer:micrometer-core",
                "io.micrometer:micrometer-observation",
                "io.opentelemetry:opentelemetry-api",
                "io.opentelemetry:opentelemetry-common",
                "io.opentelemetry:opentelemetry-context",
                "org.jspecify:jspecify",
                "org.springframework.boot:spring-boot",
                "org.springframework.boot:spring-boot-autoconfigure",
                "org.springframework.boot:spring-boot-health",
                "org.springframework:spring-aop",
                "org.springframework:spring-beans",
                "org.springframework:spring-context",
                "org.springframework:spring-core",
                "org.springframework:spring-expression",
            ),
            "runtimeClasspath" to setOf(
                "com.fasterxml.jackson.core:jackson-core",
                "com.fasterxml.jackson:jackson-bom",
                "org.jspecify:jspecify",
            ),
        ),
        "repost-client-cdi" to mapOf(
            "compileClasspath" to setOf(
                "io.micrometer:micrometer-commons",
                "io.micrometer:micrometer-core",
                "io.micrometer:micrometer-observation",
                "io.opentelemetry:opentelemetry-api",
                "io.opentelemetry:opentelemetry-common",
                "io.opentelemetry:opentelemetry-context",
                "jakarta.annotation:jakarta.annotation-api",
                "jakarta.el:jakarta.el-api",
                "jakarta.enterprise:jakarta.enterprise.cdi-api",
                "jakarta.enterprise:jakarta.enterprise.lang-model",
                "jakarta.inject:jakarta.inject-api",
                "jakarta.interceptor:jakarta.interceptor-api",
                "org.eclipse.microprofile.config:microprofile-config-api",
                "org.jspecify:jspecify",
            ),
            "runtimeClasspath" to setOf(
                "com.fasterxml.jackson.core:jackson-core",
                "com.fasterxml.jackson:jackson-bom",
                "org.jspecify:jspecify",
            ),
        ),
        "repost-maven-plugin" to mapOf(
            "compileClasspath" to setOf(
                "org.apache.maven:maven-core",
                "org.apache.maven:maven-model",
                "org.apache.maven:maven-plugin-api",
                "org.apache.maven.resolver:maven-resolver-api",
                "org.apache.maven.plugin-tools:maven-plugin-annotations",
            ),
            "runtimeClasspath" to emptySet(),
        ),
        "repost-gradle-plugin" to mapOf(
            "compileClasspath" to setOf(
                "org.jetbrains.kotlin:kotlin-gradle-plugin",
                "org.jetbrains.kotlin:kotlin-gradle-plugin-api",
                "org.jetbrains.kotlin:kotlin-tooling-core",
            ),
            "runtimeClasspath" to emptySet(),
        ),
    )
}
