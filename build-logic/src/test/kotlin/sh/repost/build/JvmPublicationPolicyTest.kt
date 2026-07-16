package sh.repost.build

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class JvmPublicationPolicyTest {
    @Test
    fun `Task 0 metadata blocks only POM generation and publication`() {
        assertEquals(
            mapOf(
                "assemble" to false,
                "compileJava" to false,
                "test" to false,
                "generatePomFileForMavenPublication" to true,
                "publishMavenPublicationToRepository" to true,
                "publish" to true,
            ),
            listOf(
                "assemble",
                "compileJava",
                "test",
                "generatePomFileForMavenPublication",
                "publishMavenPublicationToRepository",
                "publish",
            ).associateWith(JvmPublicationPolicy::requiresApprovedMetadata),
        )
    }

    @Test
    fun `reports every missing Task 0 metadata property in stable order`() {
        val values = JvmPomMetadataProperty.values().associateWith { property ->
            if (property == JvmPomMetadataProperty.NAME) "   " else null
        }

        assertEquals(
            JvmPomMetadataProperty.values().map(JvmPomMetadataProperty::gradleProperty),
            JvmPublicationPolicy.missingMetadataProperties(values),
        )
    }

    @Test
    fun `accepts a generated Maven POM containing all mandatory metadata`() {
        val metadata = approvedMetadata()
        val generatedPom = """
            <?xml version="1.0" encoding="UTF-8"?>
            <project xmlns="http://maven.apache.org/POM/4.0.0">
              <modelVersion>4.0.0</modelVersion>
              <groupId>sh.repost</groupId>
              <artifactId>repost-client</artifactId>
              <version>1.0.0</version>
              <name>${metadata.getValue(JvmPomMetadataProperty.NAME)}</name>
              <description>${metadata.getValue(JvmPomMetadataProperty.DESCRIPTION)}</description>
              <url>${metadata.getValue(JvmPomMetadataProperty.URL)}</url>
              <licenses><license>
                <name>${metadata.getValue(JvmPomMetadataProperty.LICENSE_NAME)}</name>
                <url>${metadata.getValue(JvmPomMetadataProperty.LICENSE_URL)}</url>
              </license></licenses>
              <developers><developer>
                <id>${metadata.getValue(JvmPomMetadataProperty.DEVELOPER_ID)}</id>
                <name>${metadata.getValue(JvmPomMetadataProperty.DEVELOPER_NAME)}</name>
              </developer></developers>
              <scm>
                <connection>${metadata.getValue(JvmPomMetadataProperty.SCM_CONNECTION)}</connection>
                <developerConnection>${metadata.getValue(JvmPomMetadataProperty.SCM_DEVELOPER_CONNECTION)}</developerConnection>
                <url>${metadata.getValue(JvmPomMetadataProperty.SCM_URL)}</url>
              </scm>
              <issueManagement>
                <system>${metadata.getValue(JvmPomMetadataProperty.ISSUE_MANAGEMENT_SYSTEM)}</system>
                <url>${metadata.getValue(JvmPomMetadataProperty.ISSUE_MANAGEMENT_URL)}</url>
              </issueManagement>
            </project>
        """.trimIndent()

        assertTrue(JvmPublicationPolicy.pomViolations(generatedPom, metadata).isEmpty())
    }

    @Test
    fun `rejects duplicate singleton and collection metadata entries`() {
        val metadata = approvedMetadata()
        val generatedPom = """
            <?xml version="1.0" encoding="UTF-8"?>
            <project xmlns="http://maven.apache.org/POM/4.0.0">
              <modelVersion>4.0.0</modelVersion>
              <name>${metadata.getValue(JvmPomMetadataProperty.NAME)}</name>
              <name>${metadata.getValue(JvmPomMetadataProperty.NAME)}</name>
              <description>${metadata.getValue(JvmPomMetadataProperty.DESCRIPTION)}</description>
              <description>${metadata.getValue(JvmPomMetadataProperty.DESCRIPTION)}</description>
              <url>${metadata.getValue(JvmPomMetadataProperty.URL)}</url>
              <url>${metadata.getValue(JvmPomMetadataProperty.URL)}</url>
              <licenses>
                <license>
                  <name>${metadata.getValue(JvmPomMetadataProperty.LICENSE_NAME)}</name>
                  <url>${metadata.getValue(JvmPomMetadataProperty.LICENSE_URL)}</url>
                </license>
                <license>
                  <name>${metadata.getValue(JvmPomMetadataProperty.LICENSE_NAME)}</name>
                  <url>${metadata.getValue(JvmPomMetadataProperty.LICENSE_URL)}</url>
                </license>
              </licenses>
              <developers>
                <developer>
                  <id>${metadata.getValue(JvmPomMetadataProperty.DEVELOPER_ID)}</id>
                  <name>${metadata.getValue(JvmPomMetadataProperty.DEVELOPER_NAME)}</name>
                </developer>
                <developer>
                  <id>${metadata.getValue(JvmPomMetadataProperty.DEVELOPER_ID)}</id>
                  <name>${metadata.getValue(JvmPomMetadataProperty.DEVELOPER_NAME)}</name>
                </developer>
              </developers>
              <scm>
                <connection>${metadata.getValue(JvmPomMetadataProperty.SCM_CONNECTION)}</connection>
                <developerConnection>${metadata.getValue(JvmPomMetadataProperty.SCM_DEVELOPER_CONNECTION)}</developerConnection>
                <url>${metadata.getValue(JvmPomMetadataProperty.SCM_URL)}</url>
              </scm>
              <issueManagement>
                <system>${metadata.getValue(JvmPomMetadataProperty.ISSUE_MANAGEMENT_SYSTEM)}</system>
                <url>${metadata.getValue(JvmPomMetadataProperty.ISSUE_MANAGEMENT_URL)}</url>
              </issueManagement>
            </project>
        """.trimIndent()

        assertEquals(
            listOf(
                "POM name occurs 2 times; expected exactly one",
                "POM description occurs 2 times; expected exactly one",
                "POM url occurs 2 times; expected exactly one",
                "POM licenses/license occurs 2 times; expected exactly one",
                "POM developers/developer occurs 2 times; expected exactly one",
            ),
            JvmPublicationPolicy.pomViolations(generatedPom, metadata),
        )
    }

    @Test
    fun `inventory requires normal plugin implementation plugin marker and Dokka javadoc artifacts`() {
        val expectedPublications = setOf(
            ":repost-client:maven",
            ":repost-client-kotlin:maven",
            ":repost-gradle-plugin:pluginMaven",
            ":repost-gradle-plugin:repostSdkPluginMarkerMaven",
        )
        val actualPublications = expectedPublications - ":repost-gradle-plugin:repostSdkPluginMarkerMaven"
        val expectedArtifacts = listOf(
            JvmPublicationArtifact(":repost-client", "maven", null, "jar", "any"),
            JvmPublicationArtifact(":repost-client-kotlin", "maven", "javadoc", "jar", "dokka-html"),
            JvmPublicationArtifact(":repost-gradle-plugin", "pluginMaven", null, "jar", "any"),
        )
        val actualArtifacts = listOf(
            JvmPublicationArtifact(":repost-client", "maven", null, "jar", "jar"),
            JvmPublicationArtifact(":repost-client-kotlin", "maven", "javadoc", "jar", "java-javadoc"),
            JvmPublicationArtifact(":repost-gradle-plugin", "pluginMaven", null, "jar", "jar"),
        )

        assertEquals(
            listOf(
                "Missing Maven publication :repost-gradle-plugin:repostSdkPluginMarkerMaven",
                "Kotlin javadoc classifier is not sourced from Dokka HTML: " +
                    ":repost-client-kotlin:maven:javadoc:jar (java-javadoc)",
            ),
            JvmPublicationPolicy.artifactInventoryViolations(
                expectedPublications,
                actualPublications,
                expectedArtifacts,
                actualArtifacts,
            ),
        )
    }

    private fun approvedMetadata(): Map<JvmPomMetadataProperty, String> = mapOf(
        JvmPomMetadataProperty.NAME to "Repost JVM SDK",
        JvmPomMetadataProperty.DESCRIPTION to "Generated JVM client for Repost",
        JvmPomMetadataProperty.URL to "https://repost.sh",
        JvmPomMetadataProperty.LICENSE_NAME to "Approved License",
        JvmPomMetadataProperty.LICENSE_URL to "https://example.test/license",
        JvmPomMetadataProperty.DEVELOPER_ID to "repost",
        JvmPomMetadataProperty.DEVELOPER_NAME to "Repost",
        JvmPomMetadataProperty.SCM_URL to "https://example.test/repost/tree/sdk-v1.0.0/repost-jvm",
        JvmPomMetadataProperty.SCM_CONNECTION to "scm:git:https://example.test/repost.git",
        JvmPomMetadataProperty.SCM_DEVELOPER_CONNECTION to "scm:git:ssh://git@example.test/repost.git",
        JvmPomMetadataProperty.ISSUE_MANAGEMENT_SYSTEM to "GitHub",
        JvmPomMetadataProperty.ISSUE_MANAGEMENT_URL to "https://example.test/repost/issues",
    )
}
