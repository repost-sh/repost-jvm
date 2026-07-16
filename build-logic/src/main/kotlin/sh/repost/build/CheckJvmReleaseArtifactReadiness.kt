package sh.repost.build

import org.gradle.api.DefaultTask
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault

@DisableCachingByDefault(because = "The task reports catalog state and has no outputs")
abstract class CheckJvmReleaseArtifactReadiness : DefaultTask() {
    @get:Input
    abstract val unimplementedModules: ListProperty<String>

    @TaskAction
    fun verify() {
        check(unimplementedModules.get().isEmpty()) {
            "JVM artifacts are explicitly unimplemented and cannot qualify for Task 3 or GA: " +
                unimplementedModules.get().sorted().joinToString()
        }
    }
}
