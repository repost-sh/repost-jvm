package sh.repost.build

internal object OwnedVersionLiteralPolicy {
    private val matrixVersion = Regex("\"([0-9]+(?:\\.[0-9]+)+(?:[A-Za-z0-9.+-]*))\"")
    private val candidateVersion = Regex(
        "(?<![A-Za-z0-9.])([0-9]+(?:\\.[0-9]+)+(?:[A-Za-z0-9.+-]*))(?![A-Za-z0-9.])",
    )

    fun violations(matrix: String, candidates: Map<String, String>): List<String> {
        var section = ""
        val ownedVersions = matrix.lineSequence().flatMap { line ->
            Regex("^\\s*\\[([^]]+)]").find(line)?.let { match ->
                section = match.groupValues[1]
                return@flatMap emptySequence()
            }
            if (section == "family") emptySequence()
            else matrixVersion.findAll(line).map { it.groupValues[1] }
        }.toSet()
        return candidates.flatMap { (path, contents) ->
            contents.lineSequence().flatMapIndexed { index, line ->
                candidateVersion.findAll(line)
                    .map { it.groupValues[1] }
                    .filter { version -> version in ownedVersions }
                    .map { version ->
                        "Owned JVM version literal $version is duplicated at $path:${index + 1}; " +
                            "consume compatibility-matrix.toml instead"
                    }
            }.toList()
        }.distinct().sorted()
    }
}
