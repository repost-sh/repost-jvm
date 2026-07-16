package sh.repost.build

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class OwnedVersionLiteralPolicyTest {
    private val matrix = """
        gradle_wrapper = "8.12.1"
        language_api = "2.1"
        kotlin = "2.1.21"
        wildfly = "40.0.0.Final-ee11"
    """.trimIndent()

    @Test
    fun `accepts samples that consume injected matrix versions`() {
        assertTrue(
            OwnedVersionLiteralPolicy.violations(
                matrix,
                mapOf("samples/java/build.gradle.kts" to "version = providers.gradleProperty(\"repostVersion\")"),
            ).isEmpty(),
        )
    }

    @Test
    fun `rejects owned versions copied into workflows and sample builds`() {
        assertEquals(
            listOf(
                "Owned JVM version literal 2.1.21 is duplicated at samples/kotlin/build.gradle.kts:2; " +
                    "consume compatibility-matrix.toml instead",
                "Owned JVM version literal 8.12.1 is duplicated at .github/workflows/jvm.yml:1; " +
                    "consume compatibility-matrix.toml instead",
            ),
            OwnedVersionLiteralPolicy.violations(
                matrix,
                mapOf(
                    ".github/workflows/jvm.yml" to "gradle: 8.12.1",
                    "samples/kotlin/build.gradle.kts" to "plugins {\n    kotlin(\"jvm\") version \"2.1.21\"\n}",
                ),
            ),
        )
    }

    @Test
    fun `matches complete qualified versions without matching their language prefix`() {
        assertEquals(
            listOf(
                "Owned JVM version literal 40.0.0.Final-ee11 is duplicated at samples/cdi/pom.xml:1; " +
                    "consume compatibility-matrix.toml instead",
            ),
            OwnedVersionLiteralPolicy.violations(
                matrix,
                mapOf("samples/cdi/pom.xml" to "<version>40.0.0.Final-ee11</version>"),
            ),
        )
    }

    @Test
    fun `does not treat the runtime family version as a framework or tool literal`() {
        assertTrue(
            OwnedVersionLiteralPolicy.violations(
                """
                [family]
                runtime_version = "1.0.0"
                [frameworks.spring_boot]
                versions = ["4.0.7"]
                """.trimIndent(),
                mapOf("certification/build.gradle.kts" to "pluginVersion=1.0.0"),
            ).isEmpty(),
        )
    }
}
