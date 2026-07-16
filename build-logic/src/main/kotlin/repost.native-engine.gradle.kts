import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.tasks.bundling.Zip
import sh.repost.build.GenerateNativeEngineManifest
import sh.repost.build.NativeEngineReleasePolicy
import sh.repost.build.SignNativeEngineManifest

plugins {
    base
    `maven-publish`
}

val selectedEngineVersion = extensions.getByType<VersionCatalogsExtension>()
    .named("libs")
    .findVersion("engine")
    .orElseThrow()
    .requiredVersion
val nativeInputDirectory = providers.gradleProperty("repostNativeEngineInputDirectory").orNull
    ?.let(::file)
    ?: layout.buildDirectory.dir("native-inputs").get().asFile
val distributionDirectory = layout.buildDirectory.dir("distributions")
val licenseFile = rootProject.layout.projectDirectory.file("../../repost-rs/repost-schema/LICENSE")
val noticeFile = layout.projectDirectory.file("src/distribution/NOTICE")

val archives = NativeEngineReleasePolicy.targets.associateWith { target ->
    val classifier = target.classifier
    val inputExecutableName = target.inputExecutableName
    val releasedExecutableName = target.releasedExecutableName(selectedEngineVersion)
    tasks.register<Zip>("packageNativeEngine${classifier.replace("-", "_").split("_").joinToString("") { part -> part.replaceFirstChar(Char::uppercase) }}") {
        group = "distribution"
        description = "Packages the $classifier Repost schema engine classifier."
        archiveBaseName.set("repost-schema-engine")
        archiveVersion.set(selectedEngineVersion)
        archiveClassifier.set(classifier)
        destinationDirectory.set(distributionDirectory)
        isPreserveFileTimestamps = false
        isReproducibleFileOrder = true
        duplicatesStrategy = DuplicatesStrategy.FAIL

        from(nativeInputDirectory.resolve(classifier).resolve(inputExecutableName)) {
            rename("^${Regex.escape(inputExecutableName)}$", releasedExecutableName)
            filePermissions { unix("rwxr-xr-x") }
        }
        from(licenseFile) {
            rename { "LICENSE" }
            filePermissions { unix("rw-r--r--") }
        }
        from(noticeFile) {
            rename { "NOTICE" }
            filePermissions { unix("rw-r--r--") }
        }
    }
}

val manifestFile = distributionDirectory.map {
    it.file("repost-schema-engine-$selectedEngineVersion-checksums.json")
}
val generateManifest = tasks.register<GenerateNativeEngineManifest>("generateNativeEngineManifest") {
    group = "distribution"
    description = "Generates the canonical checksum and target-family manifest."
    dependsOn(archives.values)
    engineVersion.set(selectedEngineVersion)
    inputDirectory.set(nativeInputDirectory)
    archiveDirectory.set(distributionDirectory)
    outputFile.set(manifestFile)
}

val signingKey = providers.gradleProperty("repostNativeEngineSigningKeyFile").orNull?.let(::file)
val detachedSignatureFile = distributionDirectory.map {
    it.file("repost-schema-engine-$selectedEngineVersion-checksums.sig")
}
val signManifest = tasks.register<SignNativeEngineManifest>("signNativeEngineManifest") {
    group = "distribution"
    description = "Signs the canonical native-engine checksum manifest."
    dependsOn(generateManifest)
    manifestFile.set(generateManifest.flatMap { it.outputFile })
    if (signingKey != null) {
        privateKeyFile.set(signingKey)
    }
    signatureFile.set(detachedSignatureFile)
}

tasks.named("assemble") {
    dependsOn(generateManifest)
}

tasks.register("assembleSignedNativeEngineDistribution") {
    group = "distribution"
    description = "Builds all five classifiers, their manifest, and its detached signature."
    dependsOn(signManifest)
}

extensions.configure<PublishingExtension> {
    publications.withType<MavenPublication>().configureEach {
        if (name == "maven") {
            version = selectedEngineVersion
            archives.values.forEach { archive -> artifact(archive) }
            artifact(generateManifest.flatMap { it.outputFile }) {
                classifier = "checksums"
                extension = "json"
                builtBy(generateManifest)
            }
            artifact(signManifest.flatMap { it.signatureFile }) {
                classifier = "checksums"
                extension = "sig"
                builtBy(signManifest)
            }
        }
    }
}
