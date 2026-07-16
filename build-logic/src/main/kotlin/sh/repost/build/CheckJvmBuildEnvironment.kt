package sh.repost.build

import org.gradle.api.DefaultTask
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault

@DisableCachingByDefault(because = "The task validates the current build process")
abstract class CheckJvmBuildEnvironment : DefaultTask() {
    @get:Input
    abstract val runningGradle: Property<String>

    @get:Input
    abstract val expectedGradle: Property<String>

    @get:Input
    abstract val runningJava: Property<Int>

    @get:Input
    abstract val expectedJava: Property<Int>

    @TaskAction
    fun verify() {
        check(runningGradle.get() == expectedGradle.get()) {
            "Gradle ${runningGradle.get()} is running; the repository wrapper and minimum are pinned to ${expectedGradle.get()}"
        }
        check(runningJava.get() == expectedJava.get()) {
            "JDK ${runningJava.get()} is running; the JVM workspace build JDK is pinned to ${expectedJava.get()}"
        }
    }
}
