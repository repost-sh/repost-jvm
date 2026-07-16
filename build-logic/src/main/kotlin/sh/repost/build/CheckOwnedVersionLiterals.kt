package sh.repost.build

import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault
import java.io.File

@DisableCachingByDefault(because = "The task validates repository-owned configuration files without outputs")
abstract class CheckOwnedVersionLiterals : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val compatibilityMatrix: RegularFileProperty

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val candidates: ConfigurableFileCollection

    @get:Internal
    abstract val repositoryRoot: Property<File>

    @TaskAction
    fun verify() {
        val root = repositoryRoot.get()
        val contents = candidates.files.associate { file ->
            file.relativeTo(root).invariantSeparatorsPath to file.readText()
        }
        val matrix = compatibilityMatrix.get().asFile.readText()
        val violations = JvmCompatibilityMatrixPolicy.violations(matrix) +
            OwnedVersionLiteralPolicy.violations(matrix, contents)
        check(violations.isEmpty()) { violations.joinToString(separator = "\n") }
    }
}
