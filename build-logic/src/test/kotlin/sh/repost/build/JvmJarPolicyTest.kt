package sh.repost.build

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class JvmJarPolicyTest {
    @Test
    fun `implemented artifacts require their expected classes`() {
        val violations = JvmJarPolicy.violations(
            entries = emptyMap(),
            expectedJavaRelease = 11,
            implementedArtifact = true,
            expectedClassEntries = setOf("sh/repost/client/ClientOptions.class"),
            expectedAutomaticModuleName = "sh.repost.client",
        )

        assertEquals(
            listOf(
                "Implemented artifact contains no class files",
                "Missing expected class sh/repost/client/ClientOptions.class",
                "Automatic-Module-Name is null, expected sh.repost.client",
            ),
            violations,
        )
    }

    @Test
    fun `unimplemented future artifacts may remain empty but cannot fake a malformed class`() {
        assertTrue(
            JvmJarPolicy.violations(emptyMap(), 17, false, emptySet(), null).isEmpty(),
        )

        assertEquals(
            listOf("example/Future.class: invalid classfile magic"),
            JvmJarPolicy.violations(
                entries = mapOf("example/Future.class" to ByteArray(8).also { it[7] = 61 }),
                expectedJavaRelease = 17,
                implementedArtifact = false,
                expectedClassEntries = emptySet(),
                expectedAutomaticModuleName = null,
            ),
        )
    }

    @Test
    fun `core archive rejects original namespaces services and constant strings`() {
        val violations = JvmJarPolicy.violations(
            entries = mapOf(
                "META-INF/MANIFEST.MF" to manifest("sh.repost.client"),
                "META-INF/services/org.apache.hc.Provider" to byteArrayOf(),
                "sh/repost/client/ClientOptions.class" to classfile(55, "org.slf4j.Logger"),
            ),
            expectedJavaRelease = 11,
            implementedArtifact = true,
            expectedClassEntries = setOf("sh/repost/client/ClientOptions.class"),
            expectedAutomaticModuleName = "sh.repost.client",
            forbiddenEntryPrefixes = setOf("org/apache/hc/", "org/slf4j/", "META-INF/services/"),
            forbiddenText = setOf("org.apache.hc", "org/apache/hc", "org.slf4j", "org/slf4j"),
        )

        assertEquals(
            listOf(
                "Forbidden archive entry META-INF/services/org.apache.hc.Provider",
                "sh/repost/client/ClientOptions.class contains forbidden text org.slf4j",
            ),
            violations,
        )
    }

    @Test
    fun `core archive permits only the canonical relocated Apache namespace`() {
        val relocated = JvmJarPolicy.violations(
            entries = mapOf(
                "sh/repost/internal/apache/org/apache/hc/Client.class" to
                    classfile(55, "sh/repost/internal/apache/org/apache/hc/core5/Client"),
            ),
            expectedJavaRelease = 11,
            implementedArtifact = true,
            expectedClassEntries = emptySet(),
            expectedAutomaticModuleName = null,
            forbiddenText = setOf("org/apache/hc/"),
            allowedTextPrefixes = setOf("sh/repost/internal/apache/"),
        )
        assertTrue(relocated.isEmpty())

        val original = JvmJarPolicy.violations(
            entries = mapOf(
                "sh/repost/client/Leak.class" to classfile(55, "org/apache/hc/core5/Client"),
            ),
            expectedJavaRelease = 11,
            implementedArtifact = true,
            expectedClassEntries = emptySet(),
            expectedAutomaticModuleName = null,
            forbiddenText = setOf("org/apache/hc/"),
            allowedTextPrefixes = setOf("sh/repost/internal/apache/"),
        )
        assertEquals(
            listOf("sh/repost/client/Leak.class contains forbidden text org/apache/hc/"),
            original,
        )
    }

    private fun classfile(major: Int, trailingText: String): ByteArray {
        val header = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN)
            .putInt(0xCAFEBABE.toInt())
            .putShort(0)
            .putShort(major.toShort())
            .array()
        return header + trailingText.toByteArray(Charsets.ISO_8859_1)
    }

    private fun manifest(moduleName: String): ByteArray =
        "Manifest-Version: 1.0\r\nAutomatic-Module-Name: $moduleName\r\n\r\n".toByteArray()
}
