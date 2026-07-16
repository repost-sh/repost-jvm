package sh.repost.build

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class JvmDependencyGraphPolicyTest {
    @Test
    fun `resolves every published JVM jar graph`() {
        assertEquals(
            setOf(
                "repost-client",
                "repost-client-kotlin",
                "repost-client-test",
                "repost-client-micrometer",
                "repost-client-opentelemetry",
                "repost-client-spring-boot-starter",
                "repost-client-cdi",
                "repost-maven-plugin",
                "repost-gradle-plugin",
            ),
            JvmDependencyGraphPolicy.resolvedGraphModules,
        )
    }

    @Test
    fun `accepts the exact core declarations and resolved runtime graph`() {
        val declarations = listOf(
            JvmDependencyDeclaration("repost-client", "api", "org.jspecify:jspecify"),
            JvmDependencyDeclaration("repost-client", "implementation", "com.fasterxml.jackson.core:jackson-core"),
        )
        val resolved = listOf(
            JvmResolvedDependencyPath(
                "repost-client",
                "runtimeClasspath",
                listOf(":repost-client", "com.fasterxml.jackson.core:jackson-core:2.21.2"),
            ),
            JvmResolvedDependencyPath(
                "repost-client",
                "runtimeClasspath",
                listOf(
                    ":repost-client",
                    "com.fasterxml.jackson.core:jackson-core:2.21.2",
                    "com.fasterxml.jackson:jackson-bom:2.21.2",
                ),
            ),
            JvmResolvedDependencyPath(
                "repost-client",
                "runtimeClasspath",
                listOf(":repost-client", "org.jspecify:jspecify:1.0.0"),
            ),
        )

        assertTrue(JvmDependencyGraphPolicy.violations(declarations, resolved).isEmpty())
    }

    @Test
    fun `accepts the test client declaration and exact inherited core graph`() {
        val declarations = listOf(
            JvmDependencyDeclaration("repost-client-test", "api", "sh.repost:repost-client:0.1.0"),
        )
        val resolved = listOf(
            JvmResolvedDependencyPath(
                "repost-client-test",
                "compileClasspath",
                listOf(":repost-client-test", ":repost-client", "org.jspecify:jspecify:1.0.0"),
            ),
            JvmResolvedDependencyPath(
                "repost-client-test",
                "runtimeClasspath",
                listOf(
                    ":repost-client-test",
                    ":repost-client",
                    "com.fasterxml.jackson.core:jackson-core:2.21.2",
                ),
            ),
            JvmResolvedDependencyPath(
                "repost-client-test",
                "runtimeClasspath",
                listOf(
                    ":repost-client-test",
                    ":repost-client",
                    "com.fasterxml.jackson.core:jackson-core:2.21.2",
                    "com.fasterxml.jackson:jackson-bom:2.21.2",
                ),
            ),
            JvmResolvedDependencyPath(
                "repost-client-test",
                "runtimeClasspath",
                listOf(":repost-client-test", ":repost-client", "org.jspecify:jspecify:1.0.0"),
            ),
        )

        assertTrue(JvmDependencyGraphPolicy.violations(declarations, resolved).isEmpty())
    }

    @Test
    fun `accepts the optional observability declarations`() {
        val declarations = listOf(
            JvmDependencyDeclaration("repost-client-micrometer", "api", "sh.repost:repost-client:1.0.0"),
            JvmDependencyDeclaration("repost-client-micrometer", "api", "io.micrometer:micrometer-core:1.16.5"),
            JvmDependencyDeclaration(
                "repost-client-opentelemetry",
                "api",
                "io.opentelemetry:opentelemetry-api:1.62.0",
            ),
        )

        assertTrue(JvmDependencyGraphPolicy.violations(declarations, emptyList()).isEmpty())
    }

    @Test
    fun `accepts optional CDI observability bridges without runtime leakage`() {
        val declarations = listOf(
            JvmDependencyDeclaration(
                "repost-client-cdi",
                "compileOnly",
                "sh.repost:repost-client-micrometer:1.0.0",
            ),
            JvmDependencyDeclaration(
                "repost-client-cdi",
                "compileOnly",
                "sh.repost:repost-client-opentelemetry:1.0.0",
            ),
        )
        val resolved = listOf(
            "io.micrometer:micrometer-core:1.16.5",
            "io.opentelemetry:opentelemetry-api:1.62.0",
        ).map { component ->
            JvmResolvedDependencyPath(
                "repost-client-cdi",
                "compileClasspath",
                listOf(":repost-client-cdi", component),
            )
        }

        assertTrue(JvmDependencyGraphPolicy.violations(declarations, resolved).isEmpty())
    }

    @Test
    fun `accepts the Spring starter compile only framework and core runtime graphs`() {
        val declarations = listOf(
            JvmDependencyDeclaration(
                "repost-client-spring-boot-starter",
                "api",
                "sh.repost:repost-client:1.0.0",
            ),
            JvmDependencyDeclaration(
                "repost-client-spring-boot-starter",
                "compileOnly",
                "org.springframework.boot:spring-boot-autoconfigure:4.0.7",
            ),
            JvmDependencyDeclaration(
                "repost-client-spring-boot-starter",
                "compileOnly",
                "sh.repost:repost-client-opentelemetry:1.0.0",
            ),
            JvmDependencyDeclaration(
                "repost-client-spring-boot-starter",
                "annotationProcessor",
                "org.springframework.boot:spring-boot-configuration-processor:4.0.7",
            ),
            JvmDependencyDeclaration(
                "repost-client-spring-boot-starter",
                "boot41TestRuntimeClasspath",
                "org.springframework.boot:spring-boot-autoconfigure:4.1.0",
            ),
        )
        val compileComponents = setOf(
            "com.fasterxml.jackson.core:jackson-annotations:2.21",
            "commons-logging:commons-logging:1.3.5",
            "io.micrometer:micrometer-commons:1.16.6",
            "io.micrometer:micrometer-core:1.16.6",
            "io.micrometer:micrometer-observation:1.16.6",
            "io.opentelemetry:opentelemetry-api:1.62.0",
            "io.opentelemetry:opentelemetry-common:1.62.0",
            "io.opentelemetry:opentelemetry-context:1.62.0",
            "org.jspecify:jspecify:1.0.0",
            "org.springframework.boot:spring-boot:4.0.7",
            "org.springframework.boot:spring-boot-autoconfigure:4.0.7",
            "org.springframework.boot:spring-boot-health:4.0.7",
            "org.springframework:spring-aop:7.0.8",
            "org.springframework:spring-beans:7.0.8",
            "org.springframework:spring-context:7.0.8",
            "org.springframework:spring-core:7.0.8",
            "org.springframework:spring-expression:7.0.8",
        )
        val runtimeComponents = setOf(
            "com.fasterxml.jackson.core:jackson-core:2.21.2",
            "com.fasterxml.jackson:jackson-bom:2.21.2",
            "org.jspecify:jspecify:1.0.0",
        )
        val resolved = compileComponents.map { component ->
            JvmResolvedDependencyPath(
                "repost-client-spring-boot-starter",
                "compileClasspath",
                listOf(":repost-client-spring-boot-starter", component),
            )
        } + runtimeComponents.map { component ->
            JvmResolvedDependencyPath(
                "repost-client-spring-boot-starter",
                "runtimeClasspath",
                listOf(":repost-client-spring-boot-starter", component),
            )
        }

        assertTrue(JvmDependencyGraphPolicy.violations(declarations, resolved).isEmpty())
    }

    @Test
    fun `accepts the Java fixture declarations for public snippet verification`() {
        val declarations = listOf(
            JvmDependencyDeclaration("fixture-java", "implementation", "sh.repost:repost-client:1.0.0"),
            JvmDependencyDeclaration("fixture-java", "testImplementation", "sh.repost:repost-client-test:1.0.0"),
            JvmDependencyDeclaration("fixture-java", "testImplementation", "org.junit:junit-bom:5.13.4"),
            JvmDependencyDeclaration("fixture-java", "testImplementation", "org.junit.jupiter:junit-jupiter"),
            JvmDependencyDeclaration(
                "fixture-java",
                "testRuntimeOnly",
                "org.junit.platform:junit-platform-launcher",
            ),
        )

        assertTrue(JvmDependencyGraphPolicy.violations(declarations, emptyList()).isEmpty())
    }

    @Test
    fun `accepts only the compile time Kotlin Gradle APIs required for source set wiring`() {
        val declarations = listOf(
            JvmDependencyDeclaration(
                "repost-gradle-plugin",
                "compileOnly",
                "org.jetbrains.kotlin:kotlin-gradle-plugin:2.1.21",
            ),
            JvmDependencyDeclaration(
                "repost-gradle-plugin",
                "compileOnly",
                "org.jetbrains.kotlin:kotlin-gradle-plugin-api:2.1.21",
            ),
            JvmDependencyDeclaration(
                "repost-gradle-plugin",
                "compileOnly",
                "org.jetbrains.kotlin:kotlin-tooling-core:2.1.21",
            ),
            JvmDependencyDeclaration(
                "repost-gradle-plugin",
                "testImplementation",
                "org.jetbrains.kotlin:kotlin-gradle-plugin:2.1.21",
            ),
        )
        val resolved = declarations.filter { it.configuration == "compileOnly" }.map { declaration ->
            JvmResolvedDependencyPath(
                "repost-gradle-plugin",
                "compileClasspath",
                listOf(":repost-gradle-plugin", declaration.target),
            )
        }

        assertTrue(JvmDependencyGraphPolicy.violations(declarations, resolved).isEmpty())
    }

    @Test
    fun `rejects a forbidden transitive dependency with its exact path`() {
        val violation = JvmDependencyGraphPolicy.violations(
            declarations = emptyList(),
            resolvedPaths = listOf(
                JvmResolvedDependencyPath(
                    "repost-client",
                    "runtimeClasspath",
                    listOf(
                        ":repost-client",
                        "com.fasterxml.jackson.core:jackson-core:2.21.2",
                        "org.apache.httpcomponents.client5:httpclient5:5.6.2",
                    ),
                ),
            ),
        ).single()

        assertEquals(
            "Forbidden resolved JVM dependency path for :repost-client:runtimeClasspath: " +
                ":repost-client -> com.fasterxml.jackson.core:jackson-core:2.21.2 -> " +
                "org.apache.httpcomponents.client5:httpclient5:5.6.2",
            violation,
        )
    }

    @Test
    fun `rejects Jackson Core below the supported range with its exact path`() {
        val violation = JvmDependencyGraphPolicy.violations(
            declarations = emptyList(),
            resolvedPaths = listOf(
                JvmResolvedDependencyPath(
                    "repost-client",
                    "runtimeClasspath",
                    listOf(":repost-client", "com.fasterxml.jackson.core:jackson-core:2.17.3"),
                ),
            ),
        ).single()

        assertEquals(
            "Unsupported Jackson Core version 2.17.3 in :repost-client:runtimeClasspath; " +
                "supported range is >=2.18.0 and <3.0.0: " +
                ":repost-client -> com.fasterxml.jackson.core:jackson-core:2.17.3",
            violation,
        )
    }

    @Test
    fun `rejects Jackson Core 3 and malformed versions`() {
        val violations = JvmDependencyGraphPolicy.violations(
            declarations = emptyList(),
            resolvedPaths = listOf(
                JvmResolvedDependencyPath(
                    "repost-client",
                    "compileClasspath",
                    listOf(":repost-client", "com.fasterxml.jackson.core:jackson-core:3.0.0"),
                ),
                JvmResolvedDependencyPath(
                    "repost-client",
                    "runtimeClasspath",
                    listOf(":repost-client", "com.fasterxml.jackson.core:jackson-core:latest.release"),
                ),
            ),
        )

        assertEquals(2, violations.size)
        assertTrue(violations.all { it.startsWith("Unsupported Jackson Core version ") })
    }

    @Test
    fun `accepts the Jackson Core range boundaries`() {
        val paths = listOf("2.18.0", "2.18.1", "2.99.0").map { version ->
            JvmResolvedDependencyPath(
                "repost-client",
                "runtimeClasspath",
                listOf(":repost-client", "com.fasterxml.jackson.core:jackson-core:$version"),
            )
        }

        assertTrue(JvmDependencyGraphPolicy.violations(emptyList(), paths).isEmpty())
    }

    @Test
    fun `rejects allowed coordinates declared through an unknown configuration`() {
        val violation = JvmDependencyGraphPolicy.violations(
            declarations = listOf(
                JvmDependencyDeclaration("repost-client", "shadowRuntime", "org.jspecify:jspecify"),
            ),
            resolvedPaths = emptyList(),
        ).single()

        assertEquals(
            "Forbidden JVM dependency declaration :repost-client:shadowRuntime -> org.jspecify:jspecify; " +
                "configuration is not part of the module policy",
            violation,
        )
    }

    @Test
    fun `requires Kotlin to depend on the same-version core through api`() {
        assertEquals(
            listOf(
                "Kotlin core dependency must be :repost-client at family version 1.0.0 through api; " +
                    "found implementation -> sh.repost:repost-client:0.9.0",
            ),
            JvmDependencyGraphPolicy.kotlinCoreViolations(
                familyVersion = "1.0.0",
                declarations = listOf(
                    JvmDependencyDeclaration(
                        "repost-client-kotlin",
                        "implementation",
                        "sh.repost:repost-client:0.9.0",
                    ),
                ),
            ),
        )
    }

    @Test
    fun `requires the BOM constraints to converge on the public module catalog`() {
        assertEquals(
            listOf(
                "BOM is missing constraint sh.repost:repost-client-kotlin",
                "BOM exposes unexpected constraint sh.repost:repost-http-engine",
            ),
            JvmDependencyGraphPolicy.bomConstraintViolations(
                expected = setOf(
                    "sh.repost:repost-client",
                    "sh.repost:repost-client-kotlin",
                ),
                actual = setOf(
                    "sh.repost:repost-client",
                    "sh.repost:repost-http-engine",
                ),
            ),
        )
    }
}
