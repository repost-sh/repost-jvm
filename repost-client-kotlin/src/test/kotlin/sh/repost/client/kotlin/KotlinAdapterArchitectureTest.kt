package sh.repost.client.kotlin

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class KotlinAdapterArchitectureTest {
    @Test
    fun `production module defines only the approved thin adapter classes`() {
        val root = Path.of(RepostClientConfig::class.java.protectionDomain.codeSource.location.toURI())
        val packageRoot = root.resolve("sh/repost/client/kotlin")
        val classFiles = Files.walk(packageRoot).use { paths ->
            paths
                .filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".class") }
                .map { it.fileName.toString().removeSuffix(".class") }
                .sorted()
                .toList()
        }
        val topLevelClasses = classFiles.map { it.substringBefore('$') }.distinct()

        assertEquals(
            listOf("CompletionStageAwaitKt", "RepostClientConfig", "RepostDsl"),
            topLevelClasses,
        )
        assertTrue(
            classFiles.filter { '$' in it }.all { className ->
                className.startsWith("CompletionStageAwaitKt\$awaitRepost\$") ||
                    className.startsWith("CompletionStageAwaitKt\$sam\$")
            },
            "only compiler-generated await bridge classes may be nested: $classFiles",
        )
    }
}
