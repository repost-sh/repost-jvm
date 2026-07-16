package sh.repost.build

import org.w3c.dom.Element
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory
import org.xml.sax.InputSource

internal enum class JvmPomMetadataProperty(
    val gradleProperty: String,
    val elementPath: List<String>,
) {
    NAME("repostPomName", listOf("name")),
    DESCRIPTION("repostPomDescription", listOf("description")),
    URL("repostPomUrl", listOf("url")),
    LICENSE_NAME("repostPomLicenseName", listOf("licenses", "license", "name")),
    LICENSE_URL("repostPomLicenseUrl", listOf("licenses", "license", "url")),
    DEVELOPER_ID("repostPomDeveloperId", listOf("developers", "developer", "id")),
    DEVELOPER_NAME("repostPomDeveloperName", listOf("developers", "developer", "name")),
    SCM_URL("repostPomScmUrl", listOf("scm", "url")),
    SCM_CONNECTION("repostPomScmConnection", listOf("scm", "connection")),
    SCM_DEVELOPER_CONNECTION(
        "repostPomScmDeveloperConnection",
        listOf("scm", "developerConnection"),
    ),
    ISSUE_MANAGEMENT_SYSTEM(
        "repostPomIssueManagementSystem",
        listOf("issueManagement", "system"),
    ),
    ISSUE_MANAGEMENT_URL(
        "repostPomIssueManagementUrl",
        listOf("issueManagement", "url"),
    ),
}

internal data class JvmPublicationArtifact(
    val projectPath: String,
    val publicationName: String,
    val classifier: String?,
    val extension: String,
    val sourceKind: String,
) {
    val identity: String
        get() = listOf(projectPath, publicationName, classifier.orEmpty(), extension).joinToString("|")

    val displayName: String
        get() = "$projectPath:$publicationName:${classifier ?: "main"}:$extension"

    fun encode(): String = listOf(projectPath, publicationName, classifier.orEmpty(), extension, sourceKind)
        .joinToString("|")

    companion object {
        fun decode(value: String): JvmPublicationArtifact {
            val parts = value.split('|')
            require(parts.size == 5) { "Invalid publication artifact identity: $value" }
            return JvmPublicationArtifact(
                projectPath = parts[0],
                publicationName = parts[1],
                classifier = parts[2].ifEmpty { null },
                extension = parts[3],
                sourceKind = parts[4],
            )
        }
    }
}

internal object JvmPublicationPolicy {
    fun requiresApprovedMetadata(taskName: String): Boolean =
        taskName.startsWith("publish") || taskName.startsWith("generatePomFileFor")

    fun missingMetadataProperties(values: Map<JvmPomMetadataProperty, String?>): List<String> =
        JvmPomMetadataProperty.values()
            .filter { values[it].isNullOrBlank() }
            .map(JvmPomMetadataProperty::gradleProperty)

    fun pomViolations(
        xml: String,
        expected: Map<JvmPomMetadataProperty, String>,
    ): List<String> {
        val document = try {
            val factory = DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = true
                setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
                setFeature("http://xml.org/sax/features/external-general-entities", false)
                setFeature("http://xml.org/sax/features/external-parameter-entities", false)
                setAttribute("http://javax.xml.XMLConstants/property/accessExternalDTD", "")
                setAttribute("http://javax.xml.XMLConstants/property/accessExternalSchema", "")
            }
            factory.newDocumentBuilder().parse(InputSource(StringReader(xml)))
        } catch (failure: Exception) {
            return listOf("POM is not safe, well-formed XML: ${failure.message}")
        }

        val root = document.documentElement ?: return listOf("POM has no project element")
        val failures = singletonMetadataPaths.mapNotNull { path ->
            val count = root.countAt(path)
            if (count > 1) "POM ${path.joinToString("/")} occurs $count times; expected exactly one" else null
        }.toMutableList()
        failures += JvmPomMetadataProperty.values().mapNotNull { property ->
            val expectedValue = expected[property]
                ?: return@mapNotNull "Missing expected metadata value ${property.gradleProperty}"
            val actual = root.textAt(property.elementPath)
            if (actual == expectedValue) null
            else "POM ${property.elementPath.joinToString("/")} is ${actual ?: "missing"}; expected $expectedValue"
        }
        return failures
    }

    fun artifactInventoryViolations(
        expectedPublications: Set<String>,
        actualPublications: Set<String>,
        expectedArtifacts: Collection<JvmPublicationArtifact>,
        actualArtifacts: Collection<JvmPublicationArtifact>,
    ): List<String> {
        val failures = mutableListOf<String>()
        (expectedPublications - actualPublications).sorted().forEach {
            failures += "Missing Maven publication $it"
        }
        (actualPublications - expectedPublications).sorted().forEach {
            failures += "Unexpected Maven publication $it"
        }

        val expectedByIdentity = expectedArtifacts.associateBy(JvmPublicationArtifact::identity)
        val actualByIdentity = actualArtifacts.associateBy(JvmPublicationArtifact::identity)
        (expectedByIdentity.keys - actualByIdentity.keys).sorted().forEach { identity ->
            failures += "Missing publication artifact ${expectedByIdentity.getValue(identity).displayName}"
        }
        (actualByIdentity.keys - expectedByIdentity.keys).sorted().forEach { identity ->
            failures += "Unexpected publication artifact ${actualByIdentity.getValue(identity).displayName}"
        }

        actualArtifacts.firstOrNull {
            it.projectPath == ":repost-client-kotlin" &&
                it.publicationName == "maven" &&
                it.classifier == "javadoc" &&
                it.extension == "jar"
        }?.takeIf { it.sourceKind != "dokka-html" }?.let { artifact ->
            failures += "Kotlin javadoc classifier is not sourced from Dokka HTML: " +
                "${artifact.displayName} (${artifact.sourceKind})"
        }
        return failures
    }

    private fun Element.textAt(path: List<String>): String? {
        var current = this
        path.forEach { name ->
            current = current.childElements().firstOrNull { it.localName == name || it.nodeName == name }
                ?: return null
        }
        return current.textContent?.trim()?.takeIf(String::isNotEmpty)
    }

    private fun Element.countAt(path: List<String>): Int {
        var current = sequenceOf(this)
        path.forEach { name ->
            current = current.flatMap { element ->
                element.childElements().filter { it.localName == name || it.nodeName == name }
            }
        }
        return current.count()
    }

    private fun Element.childElements(): Sequence<Element> = sequence {
        val children = childNodes
        for (index in 0 until children.length) {
            val child = children.item(index)
            if (child is Element) yield(child)
        }
    }

    private val singletonMetadataPaths = listOf(
        listOf("name"),
        listOf("description"),
        listOf("url"),
        listOf("licenses", "license"),
        listOf("developers", "developer"),
        listOf("scm"),
        listOf("issueManagement"),
    )
}
