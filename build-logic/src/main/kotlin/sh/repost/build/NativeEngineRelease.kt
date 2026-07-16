package sh.repost.build

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64

data class NativeEngineTarget(
    val classifier: String,
    val os: String,
    val architecture: String,
    val rustTarget: String,
    val libcStrategy: String,
) {
    val inputExecutableName: String
        get() = if (os == "windows") "repost-schema.exe" else "repost-schema"

    fun releasedExecutableName(version: String): String =
        "repost-schema-engine-$version${if (os == "windows") ".exe" else ""}"
}

internal object NativeEngineReleasePolicy {
    val targets = listOf(
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
    )

    fun manifest(version: String, inputDirectory: Path, archiveDirectory: Path): String {
        require(version.matches(Regex("[0-9]+\\.[0-9]+\\.[0-9]+"))) {
            "native engine version must be a pinned semantic version"
        }
        val archives = targets.joinToString(",") { target ->
            val executable = inputDirectory.resolve(target.classifier).resolve(target.inputExecutableName)
            val archive = archiveDirectory.resolve("repost-schema-engine-$version-${target.classifier}.zip")
            require(Files.isRegularFile(executable)) {
                "missing native engine executable for ${target.classifier}: $executable"
            }
            require(Files.isRegularFile(archive)) {
                "missing native engine archive for ${target.classifier}: $archive"
            }
            "{" +
                "\"classifier\":\"${target.classifier}\"," +
                "\"os\":\"${target.os}\"," +
                "\"architecture\":\"${target.architecture}\"," +
                "\"rustTarget\":\"${target.rustTarget}\"," +
                "\"libcStrategy\":\"${target.libcStrategy}\"," +
                "\"executableSha256\":\"${sha256(executable)}\"," +
                "\"archiveSha256\":\"${sha256(archive)}\"}"
        }
        return "{\"formatVersion\":1,\"engineVersion\":\"$version\",\"archives\":[$archives]}\n"
    }

    fun sha256(path: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path).use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read > 0) digest.update(buffer, 0, read)
            }
        }
        return "sha256:" + digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }

    fun privateKey(pem: String): PrivateKey {
        val encoded = pem
            .replace("-----BEGIN PRIVATE KEY-----", "")
            .replace("-----END PRIVATE KEY-----", "")
            .replace(Regex("\\s"), "")
        require(encoded.isNotEmpty()) { "native engine signing key is empty" }
        val specification = PKCS8EncodedKeySpec(Base64.getDecoder().decode(encoded))
        for (algorithm in listOf("RSA", "EC")) {
            try {
                return KeyFactory.getInstance(algorithm).generatePrivate(specification)
            } catch (_: Exception) {
                // Try the other approved key family.
            }
        }
        throw IllegalArgumentException("native engine signing key is not a PKCS#8 RSA or EC private key")
    }

    fun sign(contents: ByteArray, key: PrivateKey): ByteArray {
        val algorithm = when (key.algorithm.uppercase()) {
            "RSA" -> "SHA256withRSA"
            "EC" -> "SHA256withECDSA"
            else -> throw IllegalArgumentException("native engine signing key must use RSA or EC")
        }
        return Signature.getInstance(algorithm).run {
            initSign(key)
            update(contents)
            sign()
        }
    }

    fun writeAtomically(output: Path, contents: ByteArray) {
        Files.createDirectories(output.parent)
        val temporary = output.resolveSibling(".${output.fileName}.tmp")
        Files.write(temporary, contents)
        try {
            Files.move(
                temporary,
                output,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temporary, output, StandardCopyOption.REPLACE_EXISTING)
        }
    }
}

abstract class GenerateNativeEngineManifest : DefaultTask() {
    @get:Input
    abstract val engineVersion: Property<String>

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val inputDirectory: DirectoryProperty

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val archiveDirectory: DirectoryProperty

    @get:OutputFile
    abstract val outputFile: RegularFileProperty

    @TaskAction
    fun generate() {
        val manifest = NativeEngineReleasePolicy.manifest(
            engineVersion.get(),
            inputDirectory.get().asFile.toPath(),
            archiveDirectory.get().asFile.toPath(),
        )
        NativeEngineReleasePolicy.writeAtomically(
            outputFile.get().asFile.toPath(),
            manifest.toByteArray(StandardCharsets.UTF_8),
        )
    }
}

@DisableCachingByDefault(because = "the private signing key is an external release credential")
abstract class SignNativeEngineManifest : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val manifestFile: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val privateKeyFile: RegularFileProperty

    @get:OutputFile
    abstract val signatureFile: RegularFileProperty

    @TaskAction
    fun sign() {
        val manifest = Files.readAllBytes(manifestFile.get().asFile.toPath())
        val key = NativeEngineReleasePolicy.privateKey(
            Files.readString(privateKeyFile.get().asFile.toPath(), StandardCharsets.US_ASCII),
        )
        NativeEngineReleasePolicy.writeAtomically(
            signatureFile.get().asFile.toPath(),
            NativeEngineReleasePolicy.sign(manifest, key),
        )
    }
}
