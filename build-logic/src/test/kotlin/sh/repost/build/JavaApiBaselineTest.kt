package sh.repost.build

import java.io.PrintWriter
import java.nio.file.Files
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import java.util.spi.ToolProvider
import kotlin.io.path.writeText
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class JavaApiBaselineTest {
    @TempDir
    lateinit var temporary: java.nio.file.Path

    @Test
    fun `baseline contains public signatures and constants but excludes package private types`() {
        val source = temporary.resolve("src/example/PublicApi.java")
        val internalSource = temporary.resolve("src/example/internal/PublicInternal.java")
        Files.createDirectories(source.parent)
        Files.createDirectories(internalSource.parent)
        source.writeText(
            """
            package example;
            public final class PublicApi {
                public static final int FORMAT = 2;
                public String value(String input) { return input; }
            }
            final class InternalType { public void leak() { } }
            """.trimIndent(),
        )
        internalSource.writeText("package example.internal; public final class PublicInternal {}")
        val classes = Files.createDirectories(temporary.resolve("classes"))
        val javac = ToolProvider.findFirst("javac").orElseThrow()
        check(
            javac.run(
                PrintWriter(System.out),
                PrintWriter(System.err),
                "-d",
                classes.toString(),
                source.toString(),
                internalSource.toString(),
            ) == 0,
        )
        val archive = temporary.resolve("fixture.jar")
        JarOutputStream(Files.newOutputStream(archive)).use { jar ->
            Files.walk(classes).use { paths ->
                paths.filter(Files::isRegularFile).sorted().forEach { file ->
                    jar.putNextEntry(JarEntry(classes.relativize(file).toString().replace('\\', '/')))
                    Files.copy(file, jar)
                    jar.closeEntry()
                }
            }
        }

        val baseline = JavaApiBaselineRenderer.render(archive.toFile(), listOf("example.internal."))

        assertTrue(baseline.contains("public final class example.PublicApi"))
        assertTrue(baseline.contains("public static final int FORMAT = 2;"))
        assertTrue(baseline.contains("descriptor: (Ljava/lang/String;)Ljava/lang/String;"))
        assertFalse(baseline.contains("InternalType"))
        assertFalse(baseline.contains("PublicInternal"))
        assertFalse(baseline.contains(temporary.toString()))
    }
}
