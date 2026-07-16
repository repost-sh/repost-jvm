package sh.repost.build

import org.gradle.api.DefaultTask
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault

@DisableCachingByDefault(because = "The task validates declared dependency metadata without file outputs")
abstract class CheckJvmDependencyBoundaries : DefaultTask() {
    @get:Input
    abstract val violations: ListProperty<String>

    @TaskAction
    fun verify() {
        check(violations.get().isEmpty()) {
            violations.get().sorted().joinToString(separator = "\n")
        }
    }
}
