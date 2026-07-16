package sh.repost.build

import java.io.PrintWriter
import java.io.StringWriter
import java.util.spi.ToolProvider
import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault

@DisableCachingByDefault(because = "JDK jdeps is the proof surface and is already bounded to one archive")
abstract class CheckPublicApiBoundary : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val archiveFile: RegularFileProperty

    @get:Input
    abstract val forbiddenPackages: ListProperty<String>

    @get:Input
    abstract val allowedPackagePrefixes: ListProperty<String>

    @TaskAction
    fun verify() {
        val jdeps = ToolProvider.findFirst("jdeps").orElseThrow {
            IllegalStateException("JDK 21 jdeps is required to verify public API boundaries")
        }
        val stdout = StringWriter()
        val stderr = StringWriter()
        val exit = jdeps.run(
            PrintWriter(stdout),
            PrintWriter(stderr),
            "--api-only",
            "--ignore-missing-deps",
            "--multi-release",
            "11",
            archiveFile.get().asFile.absolutePath,
        )
        check(exit == 0) { "jdeps failed for ${archiveFile.get().asFile.name}: ${stderr.toString().trim()}" }

        val report = stdout.toString()
        val violations = forbiddenPackages.get().filter { prefix ->
            containsUnprefixed(report, prefix, allowedPackagePrefixes.get()) ||
                containsUnprefixed(
                    report,
                    prefix.replace('.', '/'),
                    allowedPackagePrefixes.get().map { it.replace('.', '/') },
                )
        }
        check(violations.isEmpty()) {
            "Public API exposes forbidden packages ${violations.sorted()} in ${archiveFile.get().asFile.name}:\n$report"
        }
    }

    private fun containsUnprefixed(text: String, token: String, allowedPrefixes: List<String>): Boolean {
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
