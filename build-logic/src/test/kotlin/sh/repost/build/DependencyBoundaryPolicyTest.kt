package sh.repost.build

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DependencyBoundaryPolicyTest {
    @Test
    fun `allows the Java fixture to consume the public test client`() {
        assertTrue(
            DependencyBoundaryPolicy.violations(
                listOf(ProjectDependencyEdge("fixture-java", "repost-client-test", "testImplementation")),
            ).isEmpty(),
        )
    }

    @Test
    fun `allows published modules to consume the test client only from test configurations`() {
        assertTrue(
            DependencyBoundaryPolicy.violations(
                listOf(
                    ProjectDependencyEdge(
                        "repost-client-micrometer",
                        "repost-client-test",
                        "testImplementation",
                    ),
                ),
            ).isEmpty(),
        )
        assertEquals(
            listOf(
                "Forbidden JVM dependency path: :repost-client-micrometer -> :repost-client-test " +
                    "via implementation; allowed targets are [repost-client]",
            ),
            DependencyBoundaryPolicy.violations(
                listOf(
                    ProjectDependencyEdge(
                        "repost-client-micrometer",
                        "repost-client-test",
                        "implementation",
                    ),
                ),
            ),
        )
    }

    @Test
    fun `allows inward dependencies`() {
        val violations = DependencyBoundaryPolicy.violations(
            listOf(
                ProjectDependencyEdge("repost-client-kotlin", "repost-client", "api"),
                ProjectDependencyEdge("repost-client-spring-boot-starter", "repost-client-micrometer", "implementation"),
                ProjectDependencyEdge("repost-client-spring-boot-starter", "repost-client-opentelemetry", "compileOnly"),
                ProjectDependencyEdge("repost-client-spring-boot-starter", "repost-client-test", "boot41TestRuntimeClasspath"),
                ProjectDependencyEdge("repost-client-cdi", "repost-client-opentelemetry", "compileOnly"),
            ),
        )
        assertTrue(violations.isEmpty())
    }

    @Test
    fun `allows a module to test its compiled artifact on another supported runtime`() {
        assertTrue(
            DependencyBoundaryPolicy.violations(
                listOf(
                    ProjectDependencyEdge(
                        "repost-client-spring-boot-starter",
                        "repost-client-spring-boot-starter",
                        "boot41TestRuntimeClasspath",
                    ),
                ),
            ).isEmpty(),
        )
    }

    @Test
    fun `rejects outward core dependency with the exact graph path`() {
        assertEquals(
            listOf("Forbidden JVM dependency path: :repost-client -> :repost-client-kotlin via implementation; allowed targets are []"),
            DependencyBoundaryPolicy.violations(
                listOf(ProjectDependencyEdge("repost-client", "repost-client-kotlin", "implementation")),
            ),
        )
    }

    @Test
    fun `rejects engine project dependency even from core`() {
        val violation = DependencyBoundaryPolicy.violations(
            listOf(ProjectDependencyEdge("repost-client", "repost-http-engine", "runtimeElements")),
        ).single()
        assertEquals(
            "Forbidden JVM dependency path: :repost-client -> :repost-http-engine via runtimeElements; " +
                "the engine may enter only the verified repost-client relocation/archive path",
            violation,
        )
    }
}
