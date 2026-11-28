package sh.repost.build

import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import javax.tools.ToolProvider

class GenerateSdkVersionSourceTest {
    @TempDir
    lateinit var temporary: Path

    @Test
    fun `generates an SdkVersion class whose VALUE is the configured version`() {
        val project = ProjectBuilder.builder().withProjectDir(temporary.resolve("project").toFile()).build()
        val task = project.tasks.register("generateSdkVersionSource", GenerateSdkVersionSource::class.java).get()
        task.packageName.set("sh.example.internal")
        task.sdkVersion.set("9.8.7")
        task.outputDirectory.set(temporary.resolve("generated").toFile())

        task.generate()

        val classes = Files.createDirectories(temporary.resolve("classes"))
        val source = temporary.resolve("generated/sh/example/internal/SdkVersion.java")
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, "-d", classes.toString(), source.toString()))
        URLClassLoader(arrayOf(classes.toUri().toURL()), null).use { loader ->
            assertEquals("9.8.7", loader.loadClass("sh.example.internal.SdkVersion").getField("VALUE").get(null))
        }
    }
}
