package sh.repost.build

import java.nio.charset.StandardCharsets
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class JvmSupplyChainPolicyTest {
    @Test
    fun `rejects dynamic pins unverified artifacts and wrapper drift`() {
        val failures = JvmSupplyChainPolicy.inputViolations(
            versionCatalog = """
                [versions]
                stable = "1.2.3"
                dynamic = "2.+"
            """.trimIndent(),
            wrapperProperties = """
                distributionUrl=https\://services.gradle.org/distributions/gradle-8.12.1-bin.zip
                validateDistributionUrl=false
            """.trimIndent(),
            verificationMetadata = """
                <verification-metadata>
                  <configuration><verify-metadata>true</verify-metadata></configuration>
                  <components><component><artifact name="dependency.jar"/></component></components>
                </verification-metadata>
            """.trimIndent(),
            locks = mapOf("gradle.lockfile" to "example:dependency:latest.release=runtimeClasspath"),
        )

        assertTrue(failures.any { it.contains("2.+") })
        assertTrue(failures.any { it.contains("distributionSha256Sum") })
        assertTrue(failures.any { it.contains("validateDistributionUrl") })
        assertTrue(failures.any { it.contains("dependency.jar") })
        assertTrue(failures.any { it.contains("latest.release") })
    }

    @Test
    fun `accepts exact registry inputs with checksums`() {
        val failures = JvmSupplyChainPolicy.inputViolations(
            versionCatalog = "[versions]\nstable = \"1.2.3\"",
            wrapperProperties = """
                distributionUrl=https\://services.gradle.org/distributions/gradle-8.12.1-bin.zip
                distributionSha256Sum=${"a".repeat(64)}
                validateDistributionUrl=true
            """.trimIndent(),
            verificationMetadata = """
                <verification-metadata>
                  <configuration><verify-metadata>true</verify-metadata></configuration>
                  <components><component><artifact name="dependency.jar"><sha256 value="${"b".repeat(64)}"/></artifact></component></components>
                </verification-metadata>
            """.trimIndent(),
            locks = mapOf("gradle.lockfile" to "example:dependency:1.2.3=runtimeClasspath"),
        )

        assertEquals(emptyList<String>(), failures)
    }

    @Test
    fun `published contents reject traversal paths build paths and sentinel secrets`() {
        val failures = JvmSupplyChainPolicy.contentViolations(
            mapOf(
                "../escape" to byteArrayOf(),
                "C:\\escape" to byteArrayOf(),
                "example/LeakedTest.class" to byteArrayOf(),
                "safe.txt" to "/Users/agent/work sentinel-secret".toByteArray(StandardCharsets.UTF_8),
            ),
        )

        assertEquals(2, failures.count { it.contains("unsafe archive path") })
        assertTrue(failures.any { it.contains("test fixture") })
        assertTrue(failures.any { it.contains("absolute build path") })
        assertTrue(failures.any { it.contains("sentinel secret") })
    }
}
