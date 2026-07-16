package sh.repost.build

import org.gradle.api.DefaultTask
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.component.ProjectComponentIdentifier
import org.gradle.api.artifacts.result.ResolvedComponentResult
import org.gradle.api.artifacts.result.ResolvedDependencyResult
import org.gradle.api.artifacts.result.UnresolvedDependencyResult
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault

@DisableCachingByDefault(because = "The task validates the owning project's live dependency resolution graph")
abstract class CheckJvmResolvedDependencyGraph : DefaultTask() {
    @get:Input
    abstract val violations: ListProperty<String>

    @TaskAction
    fun verify() {
        check(violations.get().isEmpty()) { violations.get().joinToString(separator = "\n") }
    }
}

internal object JvmResolvedDependencyPathCollector {
    fun collect(module: String, configuration: Configuration): List<JvmResolvedDependencyPath> {
        val rootComponent = configuration.incoming.resolutionResult.rootComponent.get()
        return collectResolvedPaths(
            module = module,
            component = rootComponent,
            configuration = configuration.name,
            path = listOf(":$module"),
        )
    }

    private fun collectResolvedPaths(
        module: String,
        component: ResolvedComponentResult,
        configuration: String,
        path: List<String>,
    ): List<JvmResolvedDependencyPath> = component.dependencies.flatMap { dependency ->
        when (dependency) {
            is ResolvedDependencyResult -> {
                val selected = dependency.selected
                val selectedName = selected.id.asPolicyName()
                val selectedPath = path + selectedName
                listOf(JvmResolvedDependencyPath(module, configuration, selectedPath)) +
                    if (selectedName in path) emptyList()
                    else collectResolvedPaths(module, selected, configuration, selectedPath)
            }
            is UnresolvedDependencyResult -> listOf(
                JvmResolvedDependencyPath(
                    module,
                    configuration,
                    path + "unresolved:${dependency.attempted.displayName}",
                ),
            )
            else -> emptyList()
        }
    }

    private fun org.gradle.api.artifacts.component.ComponentIdentifier.asPolicyName(): String = when (this) {
        is ModuleComponentIdentifier -> "$group:$module:$version"
        is ProjectComponentIdentifier -> projectPath
        else -> displayName
    }
}
