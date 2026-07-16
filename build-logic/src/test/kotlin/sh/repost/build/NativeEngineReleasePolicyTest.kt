package sh.repost.build

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.io.TempDir
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64

class NativeEngineReleasePolicyTest {
    @TempDir
    lateinit var temporary: Path

    @Test
    fun `manifest is canonical complete and records exact target metadata`() {
        assertEquals(
            listOf(
                NativeEngineTarget(
                    "linux-x86_64",
                    "linux",
                    "x86_64",
                    "x86_64-unknown-linux-musl",
                    "static-musl",
                ),
                NativeEngineTarget(
                    "linux-aarch64",
                    "linux",
                    "aarch64",
                    "aarch64-unknown-linux-musl",
                    "static-musl",
                ),
                NativeEngineTarget(
                    "macos-x86_64",
                    "macos",
                    "x86_64",
                    "x86_64-apple-darwin",
                    "system",
                ),
                NativeEngineTarget(
                    "macos-aarch64",
                    "macos",
                    "aarch64",
                    "aarch64-apple-darwin",
                    "system",
                ),
                NativeEngineTarget(
                    "windows-x86_64",
                    "windows",
                    "x86_64",
                    "x86_64-pc-windows-msvc",
                    "msvc",
                ),
            ),
            NativeEngineReleasePolicy.targets,
        )
        val inputs = temporary.resolve("inputs")
        val archives = temporary.resolve("archives")
        Files.createDirectories(archives)
        NativeEngineReleasePolicy.targets.forEach { target ->
            val targetDirectory = inputs.resolve(target.classifier)
            Files.createDirectories(targetDirectory)
            Files.writeString(
                targetDirectory.resolve(target.inputExecutableName),
                "executable:${target.classifier}",
                StandardCharsets.UTF_8,
            )
            Files.writeString(
                archives.resolve("repost-schema-engine-0.9.0-${target.classifier}.zip"),
                "archive:${target.classifier}",
                StandardCharsets.UTF_8,
            )
        }

        val manifest = NativeEngineReleasePolicy.manifest("0.9.0", inputs, archives)

        assertTrue(manifest.endsWith("\n"))
        assertEquals(5, Regex("\\\"classifier\\\"").findAll(manifest).count())
        assertTrue(manifest.startsWith("{\"formatVersion\":1,\"engineVersion\":\"0.9.0\",\"archives\":["))
        assertTrue(manifest.contains("\"rustTarget\":\"x86_64-unknown-linux-musl\""))
        assertTrue(manifest.contains("\"libcStrategy\":\"static-musl\""))
        assertTrue(manifest.contains(Regex("\"archiveSha256\":\"sha256:[0-9a-f]{64}\"")))
    }

    @Test
    fun `manifest refuses an incomplete classifier family`() {
        val inputs = temporary.resolve("inputs")
        val archives = temporary.resolve("archives")
        Files.createDirectories(inputs)
        Files.createDirectories(archives)

        val failure = assertThrows(IllegalArgumentException::class.java) {
            NativeEngineReleasePolicy.manifest("0.9.0", inputs, archives)
        }

        assertTrue(failure.message!!.contains("linux-x86_64"))
    }

    @Test
    fun `PKCS8 RSA signatures verify with the matching public key`() {
        val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val pem = "-----BEGIN PRIVATE KEY-----\n" +
            Base64.getMimeEncoder(64, "\n".toByteArray(StandardCharsets.US_ASCII))
                .encodeToString(pair.private.encoded) +
            "\n-----END PRIVATE KEY-----\n"
        val contents = "canonical manifest\n".toByteArray(StandardCharsets.UTF_8)

        val signature = NativeEngineReleasePolicy.sign(contents, NativeEngineReleasePolicy.privateKey(pem))

        val verifier = Signature.getInstance("SHA256withRSA")
        verifier.initVerify(pair.public)
        verifier.update(contents)
        assertTrue(verifier.verify(signature))
    }
}
