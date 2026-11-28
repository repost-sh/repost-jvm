package sh.repost.build

import java.io.File
import java.io.StringReader
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Properties
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.SetProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault
import org.w3c.dom.Element
import org.xml.sax.InputSource

@DisableCachingByDefault(because = "The task validates repository-owned release inputs")
abstract class CheckJvmSupplyChainInputs : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val versionCatalog: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val wrapperProperties: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val verificationMetadata: RegularFileProperty

    // The lockfiles, a tree rooted at the checkout.
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val lockFiles: ConfigurableFileCollection

    @TaskAction
    fun verify() {
        val failures = JvmSupplyChainPolicy.inputViolations(
            versionCatalog.get().asFile.readText(),
            wrapperProperties.get().asFile.readText(),
            verificationMetadata.get().asFile.readText(),
            lockFiles.textByRelativePath(),
        )
        check(failures.isEmpty()) { failures.joinToString("\n") }
    }
}

// Whether the working tree's lockfiles change what the published artifacts are
// built from, against the lockfiles of a base checkout (the refresh bot's HEAD).
@DisableCachingByDefault(because = "Comparing lockfiles is cheaper than a cache lookup")
abstract class DeclareJvmLockfileRelease : DefaultTask() {
    // The working tree's lockfiles and the base's, each a tree rooted at its checkout.
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val lockFiles: ConfigurableFileCollection

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val baseLockFiles: ConfigurableFileCollection

    // Each published module's lockfile, relative to the checkout.
    @get:Input
    abstract val publishedLockFiles: SetProperty<String>

    @get:OutputFile
    abstract val decision: RegularFileProperty

    @TaskAction
    fun declare() {
        val release = JvmLockfileReleasePolicy.decide(
            base = baseLockFiles.textByRelativePath(),
            current = lockFiles.textByRelativePath(),
            publishedLockFiles = publishedLockFiles.get(),
        )
        decision.get().asFile.writeText(release.render())
    }
}

// Each file's text by its path in its tree, for a collection of rooted file trees.
internal fun FileCollection.textByRelativePath(): Map<String, String> {
    val contents = mutableMapOf<String, String>()
    asFileTree.visit {
        if (!isDirectory) contents[relativePath.pathString] = file.readText()
    }
    return contents
}

@DisableCachingByDefault(because = "The task validates and hashes materialized publication outputs")
abstract class VerifyJvmPublishedContents : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val artifacts: ConfigurableFileCollection

    @get:Internal
    abstract val workspaceDirectory: DirectoryProperty

    @get:OutputFile
    abstract val provenanceSubjects: RegularFileProperty

    @TaskAction
    fun verify() {
        val root = workspaceDirectory.get().asFile
        val failures = artifacts.files.sortedBy { it.relativeTo(root).invariantSeparatorsPath }.flatMap { artifact ->
            val path = artifact.relativeTo(root).invariantSeparatorsPath
            when (artifact.extension.lowercase()) {
                "jar", "zip" -> ZipFile(artifact).use { archive ->
                    archive.entries().asSequence().filterNot { it.isDirectory }.flatMap { entry ->
                        val bytes = archive.getInputStream(entry).use { it.readBytes() }
                        JvmSupplyChainPolicy.contentViolations(mapOf(entry.name to bytes)).asSequence()
                            .map { "$path: $it" }
                    }.toList()
                }
                else -> JvmSupplyChainPolicy.contentViolations(mapOf(path to artifact.readBytes()))
                    .map { "$path: $it" }
            }
        }
        check(failures.isEmpty()) { failures.joinToString("\n") }

        val report = provenanceSubjects.get().asFile
        report.parentFile.mkdirs()
        report.writeText(ArtifactHashManifest.render(root, artifacts.files))
    }
}

@DisableCachingByDefault(because = "The task compares independently built release manifests")
abstract class VerifyReproducibleArchives : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val artifacts: ConfigurableFileCollection

    @get:InputFile
    @get:Optional
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val referenceManifest: RegularFileProperty

    @get:Input
    @get:Optional
    abstract val sourceDateEpoch: Property<String>

    @get:Internal
    abstract val workspaceDirectory: DirectoryProperty

    @TaskAction
    fun verify() {
        check(sourceDateEpoch.orNull?.matches(Regex("[0-9]+")) == true) {
            "SOURCE_DATE_EPOCH must be set to fixed epoch seconds for reproducibility verification"
        }
        val reference = referenceManifest.orNull?.asFile
            ?: error(
                "Set -PrepostReproducibilityReferenceManifest to the provenance-subjects.json " +
                    "from the first clean build",
            )
        val current = ArtifactHashManifest.render(workspaceDirectory.get().asFile, artifacts.files)
        check(reference.readText() == current) {
            "Release archives differ from first clean build manifest ${reference.absolutePath}"
        }
    }
}

internal object JvmSupplyChainPolicy {
    fun inputViolations(
        versionCatalog: String,
        wrapperProperties: String,
        verificationMetadata: String,
        locks: Map<String, String>,
    ): List<String> {
        val failures = mutableListOf<String>()
        var section = ""
        versionCatalog.lineSequence().forEachIndexed { index, raw ->
            val line = raw.substringBefore('#').trim()
            if (line.startsWith('[') && line.endsWith(']')) {
                section = line
            } else if (section == "[versions]" && '=' in line) {
                val value = line.substringAfter('=').trim().removeSurrounding("\"")
                if (isDynamic(value)) {
                    failures += "Dynamic release version pin $value at gradle/libs.versions.toml:${index + 1}"
                }
            }
        }

        val wrapper = Properties().apply { load(StringReader(wrapperProperties)) }
        val distributionUrl = wrapper.getProperty("distributionUrl").orEmpty()
        if (!Regex("https://services\\.gradle\\.org/distributions/gradle-[0-9]+(?:\\.[0-9]+)+-bin\\.zip")
                .matches(distributionUrl)
        ) {
            failures += "Gradle wrapper must resolve one exact binary distribution from services.gradle.org"
        }
        if (!wrapper.getProperty("distributionSha256Sum").orEmpty().matches(SHA256)) {
            failures += "Gradle wrapper distributionSha256Sum must be one SHA-256 checksum"
        }
        if (wrapper.getProperty("validateDistributionUrl") != "true") {
            failures += "Gradle wrapper validateDistributionUrl must be true"
        }

        failures += verificationViolations(verificationMetadata)
        locks.toSortedMap().forEach { (path, contents) ->
            JvmLockfile.entries(contents).forEach { (index, entry) ->
                val version = entry.coordinate.substringAfterLast(':', missingDelimiterValue = "")
                if (version.isEmpty() || isDynamic(version)) {
                    failures += "Dynamic or missing dependency lock ${entry.coordinate} at $path:${index + 1}"
                }
            }
        }
        return failures.distinct().sorted()
    }

    fun contentViolations(entries: Map<String, ByteArray>): List<String> = entries.flatMap { (name, bytes) ->
        val failures = mutableListOf<String>()
        val normalized = name.replace('\\', '/')
        if (
            normalized.startsWith('/') ||
            DRIVE_ABSOLUTE.containsMatchIn(normalized) ||
            normalized.split('/').any { it == ".." }
        ) {
            failures += "unsafe archive path $name"
        }
        if (TEST_FIXTURE.matches(normalized.substringAfterLast('/'))) {
            failures += "test fixture leaked into publication at $name"
        }
        val text = String(bytes, StandardCharsets.ISO_8859_1)
        if (ABSOLUTE_PATH.containsMatchIn(text)) failures += "$name contains an absolute build path"
        if (SECRET_MARKERS.any(text::contains)) failures += "$name contains a sentinel secret"
        failures
    }.distinct().sorted()

    private fun verificationViolations(xml: String): List<String> {
        val document = try {
            DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = true
                setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
                setFeature("http://xml.org/sax/features/external-general-entities", false)
                setFeature("http://xml.org/sax/features/external-parameter-entities", false)
                setAttribute("http://javax.xml.XMLConstants/property/accessExternalDTD", "")
                setAttribute("http://javax.xml.XMLConstants/property/accessExternalSchema", "")
            }.newDocumentBuilder().parse(InputSource(StringReader(xml)))
        } catch (failure: Exception) {
            return listOf("Dependency verification metadata is not safe, well-formed XML: ${failure.message}")
        }
        val failures = mutableListOf<String>()
        val verifyMetadata = document.getElementsByTagNameNS("*", "verify-metadata")
        if (verifyMetadata.length != 1 || verifyMetadata.item(0).textContent.trim() != "true") {
            failures += "Dependency verification must enable verify-metadata"
        }
        val artifacts = document.getElementsByTagNameNS("*", "artifact")
        for (index in 0 until artifacts.length) {
            val artifact = artifacts.item(index) as Element
            val hashes = artifact.getElementsByTagNameNS("*", "sha256")
            val valid = (0 until hashes.length).any { hashIndex ->
                (hashes.item(hashIndex) as Element).getAttribute("value").matches(SHA256)
            }
            if (!valid) failures += "Dependency artifact ${artifact.getAttribute("name")} has no SHA-256 checksum"
        }
        return failures
    }

    private fun isDynamic(version: String): Boolean {
        val lower = version.lowercase()
        return '+' in version || '[' in version || '(' in version ||
            lower.startsWith("latest") || lower == "release" || lower.endsWith("snapshot")
    }

    private val SHA256 = Regex("[0-9a-f]{64}")
    private val DRIVE_ABSOLUTE = Regex("^[A-Za-z]:/")
    private val ABSOLUTE_PATH = Regex(
        "(?:^|[\\s\"'=])(?:file:)?/(?:Users|home|private/tmp)/|" +
            "(?:^|[\\s\"'=])[A-Za-z]:\\\\(?:Users|home|private|tmp)\\\\",
    )
    private val TEST_FIXTURE = Regex(".*(?:Test|Tests|TestCase)\\.(?:class|java|kt)")
    private val SECRET_MARKERS = listOf(
        "sentinel-secret",
        "payload-secret",
        "remote-secret",
        "proxy-secret",
    )
}

// A Gradle lockfile line: one locked module, `group:name:version=configuration,...`.
internal data class JvmLockEntry(val coordinate: String, val configurations: Set<String>)

internal object JvmLockfile {
    // The locked modules of a lockfile, indexed by their line; comments and the
    // `empty=` line lock none.
    fun entries(contents: String): List<IndexedValue<JvmLockEntry>> =
        contents.lineSequence().withIndex().mapNotNull { (index, raw) ->
            val line = raw.substringBefore('#').trim()
            if (line.isEmpty() || line.startsWith("empty=")) {
                null
            } else {
                IndexedValue(
                    index,
                    JvmLockEntry(line.substringBefore('='), line.substringAfter('=', "").split(',').toSet() - ""),
                )
            }
        }.toList()
}

// The coordinates of the changed lock entries that ship; the release is a patch when there is one.
internal data class JvmLockfileRelease(val coordinates: List<String>) {
    fun render(): String {
        val release = if (coordinates.isEmpty()) "empty" else "patch"
        val entries = coordinates.joinToString(",\n") { "    \"${it.jsonEscape()}\"" }
        return "{\n  \"release\": \"$release\",\n  \"coordinates\": [\n$entries\n  ]\n}\n"
    }
}

internal object JvmLockfileReleasePolicy {
    // A published module's lockfile ships the coordinates it locks on a shipped
    // configuration, so an entry that moves only on a test or certification
    // classpath changes nothing that ships. A buildscript or settings lock
    // ships every entry, the plugins that build the artifacts. Conservative:
    // the root buildscript lock also holds check-only plugins
    // (kotlin-abi-validator, japicmp), whose updates release the SDK too.
    fun decide(
        base: Map<String, String>,
        current: Map<String, String>,
        publishedLockFiles: Set<String>,
    ): JvmLockfileRelease = JvmLockfileRelease(
        (base.keys + current.keys).flatMap { path ->
            val ships: (String) -> Boolean = when {
                path.substringAfterLast('/') in BUILD_LOCKS -> { _ -> true }
                path in publishedLockFiles -> JvmModuleCatalog.shippedConfigurations::contains
                else -> { _ -> false }
            }
            changed(shippedCoordinates(base[path], ships), shippedCoordinates(current[path], ships))
        }.distinct().sorted(),
    )

    // The coordinates a lockfile locks on a configuration that ships.
    private fun shippedCoordinates(contents: String?, ships: (String) -> Boolean): Set<String> =
        JvmLockfile.entries(contents.orEmpty())
            .filter { (_, entry) -> entry.configurations.any(ships) }
            .mapTo(mutableSetOf()) { (_, entry) -> entry.coordinate }

    private fun changed(before: Set<String>, after: Set<String>): Set<String> = (before - after) + (after - before)

    private val BUILD_LOCKS = setOf("buildscript-gradle.lockfile", "settings-gradle.lockfile")
}

internal fun String.jsonEscape(): String = replace("\\", "\\\\").replace("\"", "\\\"")

internal object ArtifactHashManifest {
    fun render(root: File, files: Collection<File>): String {
        val subjects = files.sortedBy { it.relativeTo(root).invariantSeparatorsPath }.joinToString(",\n") { file ->
            val path = file.relativeTo(root).invariantSeparatorsPath.jsonEscape()
            "    {\"name\":\"$path\",\"sha256\":\"${sha256(file)}\"}"
        }
        return "{\n  \"subjects\": [\n$subjects\n  ]\n}\n"
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
