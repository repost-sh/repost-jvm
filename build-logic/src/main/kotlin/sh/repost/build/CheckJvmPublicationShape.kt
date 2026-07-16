package sh.repost.build

import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault
import java.util.zip.ZipFile

@DisableCachingByDefault(because = "The task validates generated publication outputs without producing an artifact")
abstract class CheckJvmPublicationShape : DefaultTask() {
    @get:Internal
    abstract val workspaceDirectory: DirectoryProperty

    @get:Input
    abstract val pomPaths: MapProperty<String, String>

    @get:Input
    abstract val metadata: MapProperty<String, String>

    @get:Input
    abstract val expectedPublications: ListProperty<String>

    @get:Input
    abstract val actualPublications: ListProperty<String>

    @get:Input
    abstract val expectedArtifacts: ListProperty<String>

    @get:Input
    abstract val actualArtifacts: ListProperty<String>

    @get:InputFile
    @get:Optional
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val kotlinJavadocJar: RegularFileProperty

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val kotlinSources: ConfigurableFileCollection

    @TaskAction
    fun verify() {
        val expectedMetadata = JvmPomMetadataProperty.values().associateWith { property ->
            metadata.get().getValue(property.gradleProperty)
        }
        val failures = pomPaths.get().toSortedMap().flatMap { (publication, relativePath) ->
            val pom = workspaceDirectory.file(relativePath).get().asFile
            when {
                !pom.isFile -> listOf("$publication did not generate POM $relativePath")
                else -> JvmPublicationPolicy.pomViolations(pom.readText(), expectedMetadata)
                    .map { "$publication: $it" }
            }
        }.toMutableList()

        failures += JvmPublicationPolicy.artifactInventoryViolations(
            expectedPublications.get().toSet(),
            actualPublications.get().toSet(),
            expectedArtifacts.get().map(JvmPublicationArtifact::decode),
            actualArtifacts.get().map(JvmPublicationArtifact::decode),
        )

        val sourcesExist = kotlinSources.files.isNotEmpty()
        if (sourcesExist) {
            val documentation = kotlinJavadocJar.orNull?.asFile
            if (documentation == null || !documentation.isFile) {
                failures += "Kotlin javadoc classifier was not materialized"
            } else {
                ZipFile(documentation).use { archive ->
                    val entries = archive.entries().asSequence().map { it.name }.toSet()
                    if ("index.html" !in entries || entries.none { it.endsWith(".html") && !it.startsWith("META-INF/") }) {
                        failures += "Kotlin javadoc classifier contains no Dokka HTML documentation"
                    }
                }
            }
        }

        check(failures.isEmpty()) { failures.joinToString("\n") }
    }
}
