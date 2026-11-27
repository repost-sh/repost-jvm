package sh.repost.build

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class JvmCompatibilityMatrixPolicyTest {
    @Test
    fun `rejects deferred adapters and broad certification surfaces`() {
        val violations = JvmCompatibilityMatrixPolicy.violations(
            """
            [frameworks.quarkus]
            versions = ["3.37.2"]
            [frameworks.micronaut]
            versions = ["5.0.0"]
            [compatibility_smokes.helidon]
            version = "4.4.1"
            mode = "framework-neutral-core"
            [certified]
            ktor = ["3.5.1"]
            vertx = ["5.1.4"]
            tomcat = ["11.0.24"]
            aws_lambda_java = [21]
            """.trimIndent(),
        )

        assertEquals(
            listOf(
                "Deferred JVM matrix section [frameworks.micronaut] is outside the approved GA scope",
                "Deferred JVM matrix section [frameworks.quarkus] is outside the approved GA scope",
                "JVM matrix is missing [compatibility_smokes.micronaut]",
                "JVM matrix is missing [compatibility_smokes.quarkus]",
                "JVM native-image matrix must be scoped to framework-neutral-core",
                "Unapproved JVM compatibility smoke [compatibility_smokes.helidon] is outside the approved GA scope",
                "Unapproved certified JVM matrix key aws_lambda_java is outside the approved GA scope",
                "Unapproved certified JVM matrix key tomcat is outside the approved GA scope",
            ),
            violations,
        )
    }

    @Test
    fun `accepts the balanced framework and native smoke boundary`() {
        val violations = JvmCompatibilityMatrixPolicy.violations(
            """
            [frameworks.spring_boot]
            versions = ["4.0.7", "4.1.0"]
            [frameworks.jakarta]
            wildfly = ["40.0.0.Final-ee10"]
            [compatibility_smokes.quarkus]
            version = "3.37.2"
            mode = "framework-neutral-core"
            [compatibility_smokes.micronaut]
            version = "5.0.0"
            mode = "framework-neutral-core"
            [certified]
            ktor = ["3.5.1"]
            vertx = ["4.5.29", "5.1.4"]
            owner = "sdk-jvm-certification"
            support_status = "planned"
            eol_status = "unannounced"
            [native_image]
            graalvm = ["25.0.3"]
            scope = "framework-neutral-core"
            """.trimIndent(),
        )

        assertTrue(violations.isEmpty(), violations.joinToString("\n"))
    }

    @Test
    fun `rejects floating versions in approved framework slots`() {
        val violations = JvmCompatibilityMatrixPolicy.violations(
            """
            [compatibility_smokes.quarkus]
            version = "3.37.+"
            mode = "framework-neutral-core"
            [compatibility_smokes.micronaut]
            version = "5.0.0"
            mode = "framework-neutral-core"
            [certified]
            ktor = ["3.5.1"]
            vertx = ["5.1.4"]
            [native_image]
            graalvm = ["25.0.3"]
            scope = "framework-neutral-core"
            """.trimIndent(),
        )

        assertTrue(
            violations.contains("JVM compatibility matrix compatibility_smokes.quarkus.version has invalid version 3.37.+"),
            violations.joinToString("\n"),
        )
    }
}
