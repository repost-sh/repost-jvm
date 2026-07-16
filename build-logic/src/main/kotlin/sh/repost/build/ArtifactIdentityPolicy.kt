package sh.repost.build

internal data class JvmArtifactIdentity(
    val projectName: String,
    val coordinate: String?,
    val archiveBaseName: String?,
    val automaticModuleName: String?,
    val gradlePluginIds: Set<String> = emptySet(),
) {
    fun encode(): String = listOf(
        projectName,
        coordinate ?: "-",
        archiveBaseName ?: "-",
        automaticModuleName ?: "-",
        gradlePluginIds.sorted().joinToString(",").ifEmpty { "-" },
    ).joinToString("|")

    fun describe(): String = encode().substringAfter('|')

    companion object {
        fun decode(value: String): JvmArtifactIdentity {
            val parts = value.split('|')
            require(parts.size == 5) { "Invalid JVM artifact identity: $value" }
            return JvmArtifactIdentity(
                projectName = parts[0],
                coordinate = parts[1].takeUnless { it == "-" },
                archiveBaseName = parts[2].takeUnless { it == "-" },
                automaticModuleName = parts[3].takeUnless { it == "-" },
                gradlePluginIds = parts[4].takeUnless { it == "-" }?.split(',')?.toSet().orEmpty(),
            )
        }
    }
}

internal object ArtifactIdentityPolicy {
    fun violations(
        expected: Collection<JvmArtifactIdentity>,
        actual: Collection<JvmArtifactIdentity>,
    ): List<String> {
        val expectedByProject = expected.associateBy(JvmArtifactIdentity::projectName)
        val actualByProject = actual.associateBy(JvmArtifactIdentity::projectName)
        return (expectedByProject.keys + actualByProject.keys).sorted().mapNotNull { projectName ->
            val expectedIdentity = expectedByProject[projectName]
            val actualIdentity = actualByProject[projectName]
            when {
                expectedIdentity == null -> "Unexpected JVM artifact identity for :$projectName: ${actualIdentity?.describe()}"
                actualIdentity == null -> "Missing JVM artifact identity for :$projectName: ${expectedIdentity.describe()}"
                expectedIdentity != actualIdentity ->
                    "JVM artifact identity mismatch for :$projectName: " +
                        "expected ${expectedIdentity.describe()}, actual ${actualIdentity.describe()}"
                else -> null
            }
        }
    }
}
