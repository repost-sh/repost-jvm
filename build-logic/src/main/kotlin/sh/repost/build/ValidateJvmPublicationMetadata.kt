package sh.repost.build

import org.gradle.api.DefaultTask
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault

@DisableCachingByDefault(because = "The task validates release-owner inputs and has no outputs")
abstract class ValidateJvmPublicationMetadata : DefaultTask() {
    @get:Input
    abstract val missingProperties: ListProperty<String>

    @TaskAction
    fun validate() {
        val missing = missingProperties.get()
        check(missing.isEmpty()) {
            "Publication metadata is not approved; missing Gradle properties: ${missing.joinToString()}"
        }
    }
}
