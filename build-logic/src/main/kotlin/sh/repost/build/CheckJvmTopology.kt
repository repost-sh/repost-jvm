package sh.repost.build

import org.gradle.api.DefaultTask
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault

@DisableCachingByDefault(because = "The task validates small configuration values without file outputs")
abstract class CheckJvmTopology : DefaultTask() {
    @get:Input
    abstract val expectedModules: ListProperty<String>

    @get:Input
    abstract val actualModules: ListProperty<String>

    @get:Input
    abstract val expectedGroup: Property<String>

    @get:Input
    abstract val actualGroups: MapProperty<String, String>

    @get:Input
    abstract val expectedVersion: Property<String>

    @get:Input
    abstract val actualVersions: MapProperty<String, String>

    @get:Input
    abstract val expectedArtifactIdentities: ListProperty<String>

    @get:Input
    abstract val actualArtifactIdentities: ListProperty<String>

    @TaskAction
    fun verify() {
        val expected = expectedModules.get().toSet()
        val actual = actualModules.get().toSet()
        check(actual == expected) {
            "JVM module topology mismatch; missing=${(expected - actual).sorted()}, unexpected=${(actual - expected).sorted()}"
        }

        val badGroups = actualGroups.get().filterValues { it != expectedGroup.get() }
        check(badGroups.isEmpty()) {
            "JVM module groups differ from ${expectedGroup.get()}: ${badGroups.toSortedMap()}"
        }

        val badVersions = actualVersions.get().filterValues { it != expectedVersion.get() }
        check(badVersions.isEmpty()) {
            "JVM module versions differ from family ${expectedVersion.get()}: ${badVersions.toSortedMap()}"
        }

        val identityViolations = ArtifactIdentityPolicy.violations(
            expectedArtifactIdentities.get().map(JvmArtifactIdentity::decode),
            actualArtifactIdentities.get().map(JvmArtifactIdentity::decode),
        )
        check(identityViolations.isEmpty()) { identityViolations.joinToString("\n") }
    }
}
