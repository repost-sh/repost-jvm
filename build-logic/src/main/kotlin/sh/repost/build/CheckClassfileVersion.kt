package sh.repost.build

import java.util.zip.ZipFile
import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault

@DisableCachingByDefault(because = "Verification reads the reproducible archive produced in the same build")
abstract class CheckClassfileVersion : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val archiveFile: RegularFileProperty

    @get:Input
    abstract val expectedJavaRelease: Property<Int>

    @get:Input
    abstract val implementedArtifact: Property<Boolean>

    @get:Input
    abstract val expectedClassEntries: ListProperty<String>

    @get:Input
    abstract val expectedAutomaticModuleName: Property<String>

    @get:Input
    abstract val forbiddenEntryPrefixes: ListProperty<String>

    @get:Input
    abstract val forbiddenText: ListProperty<String>

    @get:Input
    abstract val allowedTextPrefixes: ListProperty<String>

    @TaskAction
    fun verify() {
        val entries = ZipFile(archiveFile.get().asFile).use { zip ->
            zip.entries().asSequence()
                .filter { !it.isDirectory }
                .associate { entry ->
                    entry.name to zip.getInputStream(entry).use { it.readBytes() }
                }
        }
        val failures = JvmJarPolicy.violations(
            entries = entries,
            expectedJavaRelease = expectedJavaRelease.get(),
            implementedArtifact = implementedArtifact.get(),
            expectedClassEntries = expectedClassEntries.get().toSet(),
            expectedAutomaticModuleName = expectedAutomaticModuleName.orNull?.takeIf(String::isNotEmpty),
            forbiddenEntryPrefixes = forbiddenEntryPrefixes.get().toSet(),
            forbiddenText = forbiddenText.get().toSet(),
            allowedTextPrefixes = allowedTextPrefixes.get().toSet(),
        )
        check(failures.isEmpty()) { failures.joinToString(prefix = "Classfile baseline mismatch:\n", separator = "\n") }
    }
}
