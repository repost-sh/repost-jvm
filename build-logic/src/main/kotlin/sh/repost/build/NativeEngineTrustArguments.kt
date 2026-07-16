package sh.repost.build

import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.process.CommandLineArgumentProvider

abstract class NativeEngineTrustArguments : CommandLineArgumentProvider {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val signingKeysFile: RegularFileProperty

    @get:Classpath
    abstract val gradlePluginJar: RegularFileProperty

    @get:Classpath
    abstract val mavenPluginJar: RegularFileProperty

    override fun asArguments(): Iterable<String> = listOf(
        "-Drepost.nativeEngineSigningKeysFile=${signingKeysFile.get().asFile.absolutePath}",
        "-Drepost.gradlePluginJar=${gradlePluginJar.get().asFile.absolutePath}",
        "-Drepost.mavenPluginJar=${mavenPluginJar.get().asFile.absolutePath}",
    )
}
