package sh.repost.build

internal data class ProjectDependencyEdge(
    val from: String,
    val to: String,
    val configuration: String,
)

internal object DependencyBoundaryPolicy {
    fun violations(edges: Collection<ProjectDependencyEdge>): List<String> = edges.mapNotNull { edge ->
        val spec = JvmModuleCatalog.modules[edge.from]
            ?: return@mapNotNull "Forbidden JVM dependency path: :${edge.from} -> :${edge.to} (unknown source module)"

        when {
            edge.to == "repost-http-engine" ->
                "Forbidden JVM dependency path: :${edge.from} -> :${edge.to} via ${edge.configuration}; " +
                    "the engine may enter only the verified repost-client relocation/archive path"
            edge.from == edge.to && edge.configuration.contains("Test") -> null
            edge.to == "repost-client-test" && edge.configuration.startsWith("test") -> null
            edge.to !in spec.allowedProjectDependencies ->
                "Forbidden JVM dependency path: :${edge.from} -> :${edge.to} via ${edge.configuration}; " +
                    "allowed targets are ${spec.allowedProjectDependencies.sorted()}"
            else -> null
        }
    }.sorted()
}
