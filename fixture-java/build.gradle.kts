import org.gradle.api.tasks.Sync
import org.gradle.jvm.tasks.Jar
import java.net.URI

val stagedRepository = providers.gradleProperty("repostRepository")
    .orElse(providers.environmentVariable("REPOST_JVM_STAGED_REPOSITORY"))
    .orNull
    ?.let { value -> rootProject.file(if (value.startsWith("file:")) URI(value) else value) }
val stagedVersion = providers.environmentVariable("REPOST_JVM_VERSION")
    .orElse(providers.gradleProperty("repostVersion"))
    .get()

fun stagedArtifact(artifactId: String) = files(
    requireNotNull(stagedRepository)
        .resolve("sh/repost/$artifactId/$stagedVersion/$artifactId-$stagedVersion.jar")
        .also { require(it.isFile) { "missing staged JVM artifact: $it" } },
)

dependencies {
    if (stagedRepository == null) {
        implementation(project(":repost-client"))
        testImplementation(project(":repost-client-test"))
    } else {
        implementation(stagedArtifact("repost-client"))
        implementation(libs.jspecify)
        runtimeOnly(libs.jackson.core)
        testImplementation(stagedArtifact("repost-client-test"))
    }
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

sourceSets.named("main") {
    java.srcDirs(
        rootProject.file("../../repost-rs/repost-schema/schema/tests/java_sdk/canonical/source"),
        rootProject.file("../../repost-rs/repost-schema/schema/tests/java_sdk/custom_clients/orders/source"),
        rootProject.file("../../repost-rs/repost-schema/schema/tests/java_sdk/custom_clients/billing/source"),
    )
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    if (stagedRepository != null) {
        val forbidden = listOf(":repost-client", ":repost-client-test")
            .map { path -> project(path).layout.buildDirectory.get().asFile.absolutePath }
        doFirst {
            require(classpath.files.none { file -> forbidden.any(file.absolutePath::startsWith) }) {
                "staged Java fixture classpath contains reactor client output"
            }
        }
    }
}

val repostClientJar = project(":repost-client").tasks.named<Jar>("jar")

tasks.register<Sync>("syncGoldenCompileClasspath") {
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    description = "Builds repost-client and exports the production Java golden compile classpath."
    dependsOn(repostClientJar)
    from(repostClientJar.flatMap { it.archiveFile })
    from(
        providers.provider {
            configurations.compileClasspath.get()
                .filter { file -> file.isFile && file.extension == "jar" }
        },
    )
    into(layout.buildDirectory.dir("golden-compile-classpath"))
}
