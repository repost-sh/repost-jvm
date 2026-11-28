package sh.repost.build

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class JvmLockfileReleasePolicyTest {
    private val publishedLockFiles = listOf("repost-client", "repost-client-spring-boot-starter", "repost-gradle-plugin")
        .mapTo(mutableSetOf()) { module -> "$module/gradle.lockfile" }

    private fun lockfile(vararg entries: String) =
        "# This is a Gradle generated file for dependency locking.\n${entries.joinToString("\n")}\nempty=\n"

    private class Case(val name: String, val path: String, val before: String?, val after: String, val coordinates: List<String>)

    @Test
    fun `ships a changed lock entry of a published classpath or of a buildscript or settings lock`() {
        val cases = listOf(
            Case(
                "a published module's runtime classpath",
                "repost-client/gradle.lockfile",
                lockfile("com.fasterxml.jackson.core:jackson-core:2.21.2=compileClasspath,runtimeClasspath,testCompileClasspath"),
                lockfile("com.fasterxml.jackson.core:jackson-core:2.21.3=compileClasspath,runtimeClasspath,testCompileClasspath"),
                listOf("com.fasterxml.jackson.core:jackson-core:2.21.2", "com.fasterxml.jackson.core:jackson-core:2.21.3"),
            ),
            Case(
                "an entry leaving a published compile classpath",
                "repost-client/gradle.lockfile",
                lockfile("org.example:library:1.0=compileClasspath,testCompileClasspath"),
                lockfile("org.example:library:1.0=testCompileClasspath"),
                listOf("org.example:library:1.0"),
            ),
            Case(
                "a published module's lockfile the base did not have",
                "repost-gradle-plugin/gradle.lockfile",
                null,
                lockfile("org.example:library:1.0=runtimeClasspath"),
                listOf("org.example:library:1.0"),
            ),
            Case(
                "the root buildscript lock",
                "buildscript-gradle.lockfile",
                lockfile("org.jetbrains.dokka:dokka-gradle-plugin:2.2.0=classpath"),
                lockfile("org.jetbrains.dokka:dokka-gradle-plugin:2.2.1=classpath"),
                listOf("org.jetbrains.dokka:dokka-gradle-plugin:2.2.0", "org.jetbrains.dokka:dokka-gradle-plugin:2.2.1"),
            ),
            Case(
                "a published module's buildscript lock",
                "repost-gradle-plugin/buildscript-gradle.lockfile",
                lockfile("com.gradle.publish:plugin-publish-plugin:2.1.1=classpath"),
                lockfile("com.gradle.publish:plugin-publish-plugin:2.1.2=classpath"),
                listOf("com.gradle.publish:plugin-publish-plugin:2.1.1", "com.gradle.publish:plugin-publish-plugin:2.1.2"),
            ),
            Case(
                "the settings lock",
                "settings-gradle.lockfile",
                lockfile(),
                lockfile("org.example.settings:org.example.settings.gradle.plugin:1.0=classpath"),
                listOf("org.example.settings:org.example.settings.gradle.plugin:1.0"),
            ),
            Case(
                "a published module's test classpaths",
                "repost-client/gradle.lockfile",
                lockfile("org.junit:junit-bom:5.13.4=testCompileClasspath,testRuntimeClasspath"),
                lockfile("org.junit:junit-bom:5.13.5=testCompileClasspath,testRuntimeClasspath"),
                emptyList(),
            ),
            Case(
                "a published module's certification classpath",
                "repost-client-spring-boot-starter/gradle.lockfile",
                lockfile("org.springframework.boot:spring-boot:4.1.0=boot41TestRuntimeClasspath"),
                lockfile("org.springframework.boot:spring-boot:4.1.1=boot41TestRuntimeClasspath"),
                emptyList(),
            ),
            Case(
                "a certification-only move of entries a published classpath shares",
                "repost-client-spring-boot-starter/gradle.lockfile",
                lockfile(
                    "com.fasterxml.jackson.core:jackson-core:2.21.2=boot41TestRuntimeClasspath,runtimeClasspath,testRuntimeClasspath",
                    "org.springframework:spring-core:7.0.8=boot41TestRuntimeClasspath,compileClasspath,testCompileClasspath,testRuntimeClasspath",
                ),
                lockfile(
                    "com.fasterxml.jackson.core:jackson-core:2.21.2=boot41TestRuntimeClasspath,runtimeClasspath,testRuntimeClasspath",
                    "org.springframework:spring-core:7.0.8=compileClasspath,testCompileClasspath,testRuntimeClasspath",
                    "org.springframework:spring-core:7.0.9=boot41TestRuntimeClasspath",
                ),
                emptyList(),
            ),
            Case(
                "a shared entry leaving only a certification classpath",
                "repost-client-spring-boot-starter/gradle.lockfile",
                lockfile("com.fasterxml.jackson.core:jackson-core:2.21.2=boot41TestRuntimeClasspath,runtimeClasspath,testRuntimeClasspath"),
                lockfile("com.fasterxml.jackson.core:jackson-core:2.21.2=runtimeClasspath,testRuntimeClasspath"),
                emptyList(),
            ),
            Case(
                "a shared entry's version on a published runtime classpath",
                "repost-client-spring-boot-starter/gradle.lockfile",
                lockfile(
                    "com.fasterxml.jackson.core:jackson-core:2.21.2=boot41TestRuntimeClasspath,runtimeClasspath,testRuntimeClasspath",
                    "org.springframework:spring-core:7.0.8=boot41TestRuntimeClasspath,compileClasspath,testCompileClasspath,testRuntimeClasspath",
                ),
                lockfile(
                    "com.fasterxml.jackson.core:jackson-core:2.21.3=boot41TestRuntimeClasspath,runtimeClasspath,testRuntimeClasspath",
                    "org.springframework:spring-core:7.0.8=boot41TestRuntimeClasspath,compileClasspath,testCompileClasspath,testRuntimeClasspath",
                ),
                listOf("com.fasterxml.jackson.core:jackson-core:2.21.2", "com.fasterxml.jackson.core:jackson-core:2.21.3"),
            ),
            Case(
                "a certification entry joining a published runtime classpath",
                "repost-client-spring-boot-starter/gradle.lockfile",
                lockfile("org.springframework:spring-jcl:7.0.8=boot41TestRuntimeClasspath,testRuntimeClasspath"),
                lockfile("org.springframework:spring-jcl:7.0.8=boot41TestRuntimeClasspath,runtimeClasspath,testRuntimeClasspath"),
                listOf("org.springframework:spring-jcl:7.0.8"),
            ),
            Case(
                "a shared entry leaving a published compile classpath",
                "repost-client-spring-boot-starter/gradle.lockfile",
                lockfile("org.springframework:spring-aop:7.0.8=boot41TestRuntimeClasspath,compileClasspath,testCompileClasspath"),
                lockfile("org.springframework:spring-aop:7.0.8=boot41TestRuntimeClasspath,testCompileClasspath"),
                listOf("org.springframework:spring-aop:7.0.8"),
            ),
            Case(
                "an unpublished module's runtime classpath",
                "fixture-java/gradle.lockfile",
                lockfile("org.junit:junit-bom:5.13.4=compileClasspath,runtimeClasspath"),
                lockfile("org.junit:junit-bom:5.13.5=compileClasspath,runtimeClasspath"),
                emptyList(),
            ),
            Case(
                "the build logic's lock",
                "build-logic/gradle.lockfile",
                lockfile("org.junit:junit-bom:5.13.4=testRuntimeClasspath"),
                lockfile("org.junit:junit-bom:5.13.5=testRuntimeClasspath"),
                emptyList(),
            ),
            Case(
                "comments and the empty line of a published module's lock",
                "repost-client/gradle.lockfile",
                lockfile("org.example:library:1.0=runtimeClasspath"),
                "# Regenerated.\norg.example:library:1.0=runtimeClasspath\nempty=annotationProcessor\n",
                emptyList(),
            ),
        )

        cases.forEach { case ->
            val release = JvmLockfileReleasePolicy.decide(
                base = listOfNotNull(case.before?.let { case.path to it }).toMap(),
                current = mapOf(case.path to case.after),
                publishedLockFiles = publishedLockFiles,
            )
            assertEquals(case.coordinates, release.coordinates, case.name)
        }
    }

    @Test
    fun `renders a patch with its coordinates and an empty release without any`() {
        assertEquals(
            "{\n  \"release\": \"patch\",\n  \"coordinates\": [\n    \"org.example:library:1.0\"\n  ]\n}\n",
            JvmLockfileRelease(listOf("org.example:library:1.0")).render(),
        )
        assertEquals(
            "{\n  \"release\": \"empty\",\n  \"coordinates\": [\n\n  ]\n}\n",
            JvmLockfileRelease(emptyList()).render(),
        )
    }
}
