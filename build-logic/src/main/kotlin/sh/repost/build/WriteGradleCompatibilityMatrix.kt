package sh.repost.build

import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

@CacheableTask
abstract class WriteGradleCompatibilityMatrix : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val compatibilityMatrix: RegularFileProperty

    @get:OutputFile
    abstract val githubOutput: RegularFileProperty

    @TaskAction
    fun write() {
        val selection = JvmCompatibilityMatrixPolicy.gradleExecutionMatrix(
            compatibilityMatrix.get().asFile.readText(),
        )
        githubOutput.get().asFile.apply {
            parentFile.mkdirs()
            writeText(selection.githubOutputs() + "\n")
        }
    }
}
