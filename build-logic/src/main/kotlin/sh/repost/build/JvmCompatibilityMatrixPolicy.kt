package sh.repost.build

internal object JvmCompatibilityMatrixPolicy {
    private val sectionPattern = Regex("^\\[([^]]+)]$")
    private val keyPattern = Regex("^([A-Za-z0-9_]+)\\s*=")
    private val approvedFrameworkSections = setOf("frameworks.spring_boot", "frameworks.jakarta")
    private val requiredSmokeSections = setOf("compatibility_smokes.quarkus", "compatibility_smokes.micronaut")
    private val approvedCertifiedKeys = setOf("ktor", "vertx", "owner", "support_status", "eol_status")
    private val versionPattern = Regex("[0-9]+(?:\\.[0-9]+){2}(?:[-A-Za-z0-9.]*)?")
    private val javaGradleKeys = listOf("gradle_wrapper", "gradle_current_8", "gradle_current_9")

    data class KotlinGradlePair(val kotlin: String, val gradle: String)

    data class GradleExecutionMatrix(
        val javaGradle: List<String>,
        val kotlinGradle: List<KotlinGradlePair>,
    ) {
        fun githubOutputs(): String =
            "java_gradle=" + javaGradle.joinToString(separator = ",", prefix = "[", postfix = "]") {
                "\"$it\""
            } + "\n" +
                "kotlin_gradle=" + kotlinGradle.joinToString(separator = ",", prefix = "[", postfix = "]") { pair ->
                    "{\"kotlin\":\"${pair.kotlin}\",\"gradle\":\"${pair.gradle}\"}"
                }
    }

    fun gradleExecutionMatrix(matrix: String): GradleExecutionMatrix {
        val buildTools = linkedMapOf<String, String>()
        val pairs = mutableListOf<MutableMap<String, String>>()
        var section = ""
        var pair: MutableMap<String, String>? = null
        matrix.lineSequence().forEach { rawLine ->
            val line = rawLine.substringBefore('#').trim()
            if (line == "[[paired_kotlin_gradle]]") {
                section = "paired_kotlin_gradle"
                pair = linkedMapOf<String, String>().also(pairs::add)
                return@forEach
            }
            sectionPattern.matchEntire(line)?.let { match ->
                section = match.groupValues[1]
                pair = null
                return@forEach
            }
            keyPattern.find(line)?.let { match ->
                when (section) {
                    "build_tools" -> if (match.groupValues[1] in javaGradleKeys) {
                        buildTools[match.groupValues[1]] =
                            quotedVersion(line.substringAfter('=').trim(), match.groupValues[1])
                    }
                    "paired_kotlin_gradle" -> if (match.groupValues[1] == "kotlin" || match.groupValues[1] == "gradle") {
                        pair?.set(
                            match.groupValues[1],
                            quotedVersion(line.substringAfter('=').trim(), match.groupValues[1]),
                        )
                    }
                }
            }
        }
        val java = javaGradleKeys.map { key ->
            requireNotNull(buildTools[key]) { "JVM compatibility matrix is missing build_tools.$key" }
        }
        val kotlin = pairs.mapIndexed { index, values ->
            KotlinGradlePair(
                requireNotNull(values["kotlin"]) {
                    "JVM compatibility matrix paired_kotlin_gradle[$index] is missing kotlin"
                },
                requireNotNull(values["gradle"]) {
                    "JVM compatibility matrix paired_kotlin_gradle[$index] is missing gradle"
                },
            )
        }
        require(java.distinct().size == java.size) { "JVM Java Gradle execution lanes must be unique" }
        require(kotlin.distinct().size == kotlin.size) { "JVM Kotlin/Gradle execution lanes must be unique" }
        return GradleExecutionMatrix(java, kotlin)
    }

    private fun quotedVersion(value: String, key: String): String {
        require(value.length >= 2 && value.first() == '"' && value.last() == '"') {
            "JVM compatibility matrix $key must be a quoted version"
        }
        val version = value.substring(1, value.length - 1)
        require(versionPattern.matches(version)) { "JVM compatibility matrix $key has invalid version $version" }
        return version
    }

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
