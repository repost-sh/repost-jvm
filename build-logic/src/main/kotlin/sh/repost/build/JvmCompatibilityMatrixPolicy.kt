package sh.repost.build

internal object JvmCompatibilityMatrixPolicy {
    private val sectionPattern = Regex("^\\[([^]]+)]$")
    private val keyPattern = Regex("^([A-Za-z0-9_]+)\\s*=")
    private val approvedFrameworkSections = setOf("frameworks.spring_boot", "frameworks.jakarta")
    private val requiredSmokeSections = setOf("compatibility_smokes.quarkus", "compatibility_smokes.micronaut")
    private val approvedCertifiedKeys = setOf("ktor", "vertx", "owner", "support_status", "eol_status")
    private val versionPattern = Regex("[0-9]+(?:\\.[0-9]+){2}(?:[-A-Za-z0-9.]*)?")

    fun violations(matrix: String): List<String> {
        val sections = linkedMapOf<String, MutableMap<String, String>>()
        var section = ""
        matrix.lineSequence().forEach { rawLine ->
            val line = rawLine.substringBefore('#').trim()
            sectionPattern.matchEntire(line)?.let { match ->
                section = match.groupValues[1]
                sections.getOrPut(section, ::linkedMapOf)
                return@forEach
            }
            keyPattern.find(line)?.let { match ->
                sections.getOrPut(section, ::linkedMapOf)[match.groupValues[1]] =
                    line.substringAfter('=').trim()
            }
        }

        val failures = mutableListOf<String>()
        sections.keys.filter { it.startsWith("frameworks.") && it !in approvedFrameworkSections }
            .forEach { failures += "Deferred JVM matrix section [$it] is outside the approved GA scope" }
        sections.keys.filter { it.startsWith("compatibility_smokes.") && it !in requiredSmokeSections }
            .forEach { failures += "Unapproved JVM compatibility smoke [$it] is outside the approved GA scope" }
        sections["certified"].orEmpty().keys.filter { it !in approvedCertifiedKeys }
            .forEach { failures += "Unapproved certified JVM matrix key $it is outside the approved GA scope" }
        requiredSmokeSections.forEach { required ->
            val values = sections[required]
            if (values == null) {
                failures += "JVM matrix is missing [$required]"
            } else if (values["mode"] != "\"framework-neutral-core\"") {
                failures += "JVM matrix [$required] must use mode framework-neutral-core"
            }
        }
        if (sections["native_image"]?.get("scope") != "\"framework-neutral-core\"") {
            failures += "JVM native-image matrix must be scoped to framework-neutral-core"
        }
        if ("micronaut_5" in sections["kotlin"].orEmpty()) {
            failures += "Deferred Kotlin Micronaut compiler pin is outside the approved GA scope"
        }
        if ("gradle_micronaut_5" in sections["build_tools"].orEmpty()) {
            failures += "Deferred Gradle Micronaut pin is outside the approved GA scope"
        }
        val versionKeys = mapOf(
            "java" to setOf("security_update_pins"),
            "kotlin" to setOf("baseline", "current"),
            "frameworks.spring_boot" to setOf("versions"),
            "frameworks.jakarta" to setOf("wildfly", "open_liberty", "payara"),
            "compatibility_smokes.quarkus" to setOf("version"),
            "compatibility_smokes.micronaut" to setOf("version"),
            "certified" to setOf("ktor", "vertx"),
            "native_image" to setOf("graalvm"),
        )
        versionKeys.forEach { (versionSection, keys) ->
            keys.forEach { key ->
                sections[versionSection]?.get(key)?.let { raw ->
                    val values = Regex("\"([^\"]+)\"").findAll(raw).map { it.groupValues[1] }.toList()
                    if (values.isEmpty()) {
                        failures += "JVM compatibility matrix $versionSection.$key must contain exact quoted versions"
                    }
                    values.filterNot(versionPattern::matches).forEach { version ->
                        failures += "JVM compatibility matrix $versionSection.$key has invalid version $version"
                    }
                }
            }
        }
        return failures.distinct().sorted()
    }
}
