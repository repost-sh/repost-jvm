import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.jvm.tasks.Jar

plugins {
    `java-library`
}

val vendorDirectory = rootProject.layout.projectDirectory.dir("vendor/apache-httpcomponents")
val vendorLock = vendorDirectory.file("vendor-lock.toml")
val vendorScript = rootProject.layout.projectDirectory.file("scripts/http-engine-vendor.js")
val engineContract = rootProject.layout.projectDirectory.file("certification/transport/jvm-engine-contract.js")
val relocatedSources = layout.buildDirectory.dir("vendor/relocated")
val lockText = providers.fileContents(vendorLock).asText
val productionReady = lockText.map { it.contains("production_state = \"materialized\"") }
val archiveDirectory = providers.environmentVariable("REPOST_HTTP_ENGINE_ARCHIVE_DIR")
    .orElse(providers.gradleProperty("repostHttpEngineArchiveDir"))
val provenanceTests = vendorDirectory.asFileTree.matching {
    include("vendor-lock.test.js", "relocation.test.js", "patch-*.test.js")
}.files.sortedBy { it.name }

extensions.configure<SourceSetContainer> {
    named("main") {
        // The engine is compiled only as the verified implicit source closure of
        // repost-client. Keeping this source set empty prevents a standalone engine
        // artifact or unused optional upstream classes from becoming a surface.
        java.setSrcDirs(emptyList<String>())
        resources.setSrcDirs(emptyList<String>())
    }
}

val checkHttpEngineProvenance = tasks.register<Exec>("checkHttpEngineProvenance") {
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    description = "Verifies the pinned engine sources, patch ledger, and optional local archives."

    inputs.files(
        vendorLock,
        vendorScript,
        engineContract,
        vendorDirectory.file("engine-build-contract.test.js"),
        vendorDirectory.file("patch-ledger.md"),
        provenanceTests,
    )
    if (vendorDirectory.dir("patches").asFile.isDirectory) {
        inputs.dir(vendorDirectory.dir("patches"))
    }

    commandLine(listOf("node", "--test") + provenanceTests.map { it.absolutePath })
    environment("REPOST_RUN_GRADLE_INTEGRATION", "0")
    providers.environmentVariable("REPOST_HTTP_ENGINE_ARCHIVE_DIR").orNull?.let {
        environment("REPOST_HTTP_ENGINE_ARCHIVE_DIR", it)
    }
}

val materializeHttpEngineSources = tasks.register<Exec>("materializeHttpEngineSources") {
    group = LifecycleBasePlugin.BUILD_GROUP
    description = "Hash-verifies, patches, prunes, and relocates the internal HTTP engine source tree."
    enabled = productionReady.get()
    inputs.files(vendorLock, vendorScript, engineContract)
    if (vendorDirectory.dir("patches").asFile.isDirectory) {
        inputs.dir(vendorDirectory.dir("patches"))
    }
    archiveDirectory.orNull?.let {
        val directory = file(it)
        if (directory.isDirectory) inputs.dir(directory)
    }
    outputs.dir(relocatedSources)
    commandLine(
        "node",
        vendorScript.asFile.absolutePath,
        "materialize-relocated",
        "--archive-dir",
        archiveDirectory.orElse("__REPOST_HTTP_ENGINE_ARCHIVE_DIR_IS_REQUIRED__").get(),
        "--output",
        relocatedSources.get().asFile.absolutePath,
    )
}

val removeUnreadyHttpEngineOutputs = tasks.register<Delete>("removeUnreadyHttpEngineOutputs") {
    group = LifecycleBasePlugin.BUILD_GROUP
    description = "Removes stale compiled engine outputs while the verified patch lock is not production-ready."
    enabled = !productionReady.get()
    delete(
        layout.buildDirectory.dir("classes"),
        layout.buildDirectory.dir("libs"),
        layout.buildDirectory.dir("resources"),
        layout.buildDirectory.dir("tmp"),
    )
}

tasks.register<Exec>("checkHttpEngineProductionReady") {
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    description = "Fails closed until every pinned patch and the relocated source tree are materialized."
    dependsOn(checkHttpEngineProvenance, materializeHttpEngineSources)
    inputs.files(vendorLock, vendorScript)
    if (relocatedSources.get().asFile.isDirectory) {
        inputs.dir(relocatedSources)
    }
    commandLine(
        "node",
        vendorScript.asFile.absolutePath,
        "check-production-ready",
        "--relocated-source-dir",
        relocatedSources.get().asFile.absolutePath,
    )
}

tasks.withType<JavaCompile>().configureEach {
    enabled = productionReady.get()
    dependsOn(materializeHttpEngineSources)
}

tasks.named<Jar>("jar") {
    enabled = productionReady.get()
    dependsOn(removeUnreadyHttpEngineOutputs)
}

tasks.named("check") {
    dependsOn(checkHttpEngineProvenance, removeUnreadyHttpEngineOutputs)
}

tasks.named("assemble") {
    dependsOn(removeUnreadyHttpEngineOutputs)
}

tasks.matching { it.name == "checkClassfileVersion" }.configureEach {
    enabled = productionReady.get()
    dependsOn(removeUnreadyHttpEngineOutputs)
}
