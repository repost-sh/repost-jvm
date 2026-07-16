package sh.repost.build

import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.util.spi.ToolProvider
import java.util.zip.ZipFile
import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault

@DisableCachingByDefault(because = "The checked-in API baseline is a deliberate source update")
abstract class DumpJavaApiBaseline : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val archiveFile: RegularFileProperty

    @get:OutputFile
    abstract val baselineFile: RegularFileProperty

    @get:Input
    abstract val excludedPackagePrefixes: ListProperty<String>

    @TaskAction
    fun dump() {
        val output = baselineFile.get().asFile
        output.parentFile.mkdirs()
        output.writeText(JavaApiBaselineRenderer.render(archiveFile.get().asFile, excludedPackagePrefixes.get()))
    }
}

@DisableCachingByDefault(because = "The bounded javap comparison is already deterministic")
abstract class CheckJavaApiBaseline : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val archiveFile: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val baselineFile: RegularFileProperty

    @get:Input
    abstract val excludedPackagePrefixes: ListProperty<String>

    @TaskAction
    fun check() {
        val expected = baselineFile.get().asFile.readText()
        val actual = JavaApiBaselineRenderer.render(archiveFile.get().asFile, excludedPackagePrefixes.get())
        check(actual == expected) {
            "Java public API changed for ${archiveFile.get().asFile.name}. " +
                "Review the API change, then run the module's apiDump task to accept the new baseline."
        }
    }
}

internal object JavaApiBaselineRenderer {
    fun render(archive: File, excludedPackagePrefixes: List<String> = emptyList()): String {
        val javap = ToolProvider.findFirst("javap").orElseThrow {
            IllegalStateException("JDK javap is required to generate Java API baselines")
        }
        val classes = ZipFile(archive).use { zip ->
            zip.entries().asSequence()
                .filter { !it.isDirectory && it.name.endsWith(".class") }
                .map { it.name }
                .filterNot { it.startsWith("META-INF/versions/") }
                .filterNot { it.endsWith("module-info.class") || it.endsWith("package-info.class") }
                .map { it.removeSuffix(".class").replace('/', '.') }
                .filterNot { className -> excludedPackagePrefixes.any(className::startsWith) }
                .sorted()
                .toList()
        }
        val declarations = classes.mapNotNull { className ->
            val stdout = StringWriter()
            val stderr = StringWriter()
            val exit = javap.run(
                PrintWriter(stdout),
                PrintWriter(stderr),
                "-public",
                "-s",
                "-constants",
                "-classpath",
                archive.absolutePath,
                className,
            )
            check(exit == 0) { "javap failed for $className: ${stderr.toString().trim()}" }
            val output = stdout.toString().replace("\r\n", "\n")
            if (!PUBLIC_DECLARATION.containsMatchIn(output)) {
                null
            } else {
                output.lineSequence()
                    .filterNot { it.startsWith("Compiled from ") }
                    .joinToString("\n")
                    .trim()
            }
        }
        return declarations.joinToString(separator = "\n\n", postfix = "\n")
    }

    private val PUBLIC_DECLARATION = Regex(
        pattern = "(?m)^public (?:[a-zA-Z]+ )*(?:class|interface|enum|record) ",
    )
}
