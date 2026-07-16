package sh.repost.build

import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import org.gradle.work.DisableCachingByDefault
import javax.inject.Inject

@DisableCachingByDefault(because = "The compatibility result depends on JApiCmp process behavior")
abstract class RunJapicmp : DefaultTask() {
    @get:Classpath
    abstract val toolClasspath: ConfigurableFileCollection

    @get:InputFile
    @get:Optional
    abstract val baselineArchive: RegularFileProperty

    @get:InputFile
    abstract val currentArchive: RegularFileProperty

    @get:OutputFile
    abstract val reportFile: RegularFileProperty

    @get:Inject
    abstract val execOperations: ExecOperations

    @TaskAction
    fun compare() {
        val baseline = baselineArchive.orNull?.asFile
        check(baseline != null && baseline.isFile) {
            "Set -PrepostCompatibilityBaselineFile=/absolute/path/to/repost-client-baseline.jar"
        }
        execOperations.javaexec {
            classpath(toolClasspath)
            mainClass.set("japicmp.JApiCmp")
            args(
                "--old", baseline.absolutePath,
                "--new", currentArchive.get().asFile.absolutePath,
                "--only-modified",
                "--error-on-binary-incompatibility",
                "--error-on-source-incompatibility",
                "--html-file", reportFile.get().asFile.absolutePath,
            )
        }.rethrowFailure()
    }
}
