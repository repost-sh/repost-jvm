package sh.repost.build

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ArtifactIdentityPolicyTest {
    private val expected = JvmArtifactIdentity(
        projectName = "repost-gradle-plugin",
        coordinate = "sh.repost:repost-gradle-plugin",
        archiveBaseName = "repost-gradle-plugin",
        automaticModuleName = "sh.repost.gradle.plugin",
        gradlePluginIds = setOf("sh.repost.sdk"),
    )

    @Test
    fun `accepts the exact configured artifact identity`() {
        assertTrue(ArtifactIdentityPolicy.violations(listOf(expected), listOf(expected)).isEmpty())
    }

    @Test
    fun `rejects artifact module and plugin identity drift`() {
        val actual = expected.copy(
            coordinate = "sh.repost:renamed-plugin",
            archiveBaseName = "renamed-plugin",
            automaticModuleName = "sh.repost.renamed",
            gradlePluginIds = setOf("sh.repost.renamed"),
        )

        assertEquals(
            listOf(
                "JVM artifact identity mismatch for :repost-gradle-plugin: " +
                    "expected sh.repost:repost-gradle-plugin|repost-gradle-plugin|sh.repost.gradle.plugin|sh.repost.sdk, " +
                    "actual sh.repost:renamed-plugin|renamed-plugin|sh.repost.renamed|sh.repost.renamed",
            ),
            ArtifactIdentityPolicy.violations(listOf(expected), listOf(actual)),
        )
    }
}
