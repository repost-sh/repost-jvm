package sh.repost.build

import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import java.util.jar.Manifest

internal object JvmJarPolicy {
    fun violations(
        entries: Map<String, ByteArray>,
        expectedJavaRelease: Int,
        implementedArtifact: Boolean,
        expectedClassEntries: Set<String>,
        expectedAutomaticModuleName: String?,
        forbiddenEntryPrefixes: Set<String> = emptySet(),
        forbiddenText: Set<String> = emptySet(),
        allowedTextPrefixes: Set<String> = emptySet(),
    ): List<String> {
        val failures = mutableListOf<String>()
        val classEntries = entries.filterKeys { it.endsWith(".class") }
        if (implementedArtifact && classEntries.isEmpty()) {
            failures += "Implemented artifact contains no class files"
        }
        expectedClassEntries.sorted().filterNot(entries::containsKey).forEach { expectedClass ->
            failures += "Missing expected class $expectedClass"
        }

        if (expectedAutomaticModuleName != null) {
            val actualModuleName = entries["META-INF/MANIFEST.MF"]?.let { manifestBytes ->
                Manifest(ByteArrayInputStream(manifestBytes)).mainAttributes
                    .getValue("Automatic-Module-Name")
            }
            if (actualModuleName != expectedAutomaticModuleName) {
                failures += "Automatic-Module-Name is $actualModuleName, expected $expectedAutomaticModuleName"
            }
        }

        entries.keys.sorted().filter { entry ->
            forbiddenEntryPrefixes.any(entry::startsWith)
        }.forEach { entry -> failures += "Forbidden archive entry $entry" }

        val expectedMajor = expectedJavaRelease + 44
        classEntries.toSortedMap().forEach { (name, bytes) ->
            when {
                bytes.size < 8 -> failures += "$name: truncated class file"
                !bytes.copyOfRange(0, 4).contentEquals(CLASSFILE_MAGIC) ->
                    failures += "$name: invalid classfile magic"
                else -> {
                    val major = ((bytes[6].toInt() and 0xff) shl 8) or (bytes[7].toInt() and 0xff)
                    if (major != expectedMajor) {
                        failures += "$name: classfile $major, expected $expectedMajor (Java $expectedJavaRelease)"
                    }
                    val classText = String(bytes, StandardCharsets.ISO_8859_1)
                    forbiddenText.sorted().firstOrNull { token ->
                        containsUnprefixed(classText, token, allowedTextPrefixes)
                    }?.let { token ->
                        failures += "$name contains forbidden text $token"
                    }
                }
            }
        }
        return failures
    }

    private val CLASSFILE_MAGIC = byteArrayOf(0xca.toByte(), 0xfe.toByte(), 0xba.toByte(), 0xbe.toByte())

    private fun containsUnprefixed(text: String, token: String, allowedPrefixes: Set<String>): Boolean {
        var index = text.indexOf(token)
        while (index >= 0) {
            val allowed = allowedPrefixes.any { prefix ->
                index >= prefix.length && text.regionMatches(index - prefix.length, prefix, 0, prefix.length)
            }
            if (!allowed) return true
            index = text.indexOf(token, index + token.length)
        }
        return false
    }
}
