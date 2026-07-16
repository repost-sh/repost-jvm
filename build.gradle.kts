import org.cyclonedx.gradle.CyclonedxAggregateTask
import org.cyclonedx.gradle.CyclonedxDirectTask
import org.gradle.api.attributes.Usage
import org.gradle.api.attributes.java.TargetJvmEnvironment
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.tasks.bundling.Jar
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.api.tasks.javadoc.Javadoc
import org.gradle.api.tasks.testing.Test
import org.gradle.language.jvm.tasks.ProcessResources
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension
import org.owasp.dependencycheck.gradle.extension.DependencyCheckExtension
import sh.repost.build.NativeEngineTrustArguments
import sh.repost.build.RunJapicmp
import java.io.File

plugins {
    base
    id("repost.jvm.workspace")
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.dokka) apply false
    alias(libs.plugins.kotlin.abi.validator) apply false
    alias(libs.plugins.cyclonedx)
    alias(libs.plugins.dependency.check)
    alias(libs.plugins.japicmp)
}

val repostLibs = libs

val japicmpTool = configurations.create("japicmpTool") {
    isCanBeConsumed = false
    isCanBeResolved = true
    attributes {
        attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_RUNTIME))
        attribute(
            TargetJvmEnvironment.TARGET_JVM_ENVIRONMENT_ATTRIBUTE,
            objects.named(TargetJvmEnvironment.STANDARD_JVM),
        )
    }
    resolutionStrategy.activateDependencyLocking()
}

dependencies {
    add(japicmpTool.name, repostLibs.japicmp)
}

extensions.configure<DependencyCheckExtension> {
    outputDirectory.set(layout.buildDirectory.dir("reports/dependency-check"))
    scanBuildEnv.set(false)
    scanConfigurations.set(listOf("compileClasspath", "runtimeClasspath"))
    formats.set(listOf("HTML", "JSON", "SARIF"))
    failBuildOnCVSS.set(7.0f)
    failOnError.set(true)
    nvd.apiKey.set(
        providers.environmentVariable("NVD_API_KEY")
            .orElse(providers.gradleProperty("nvdApiKey")),
    )
    analyzers.setOssIndexEnabled(false)
}

allprojects {
    group = providers.gradleProperty("repostGroup").get()
    version = providers.gradleProperty("repostVersion").get()

    providers.gradleProperty("repostStagingRepository").orNull?.let { repository ->
        pluginManager.withPlugin("maven-publish") {
            extensions.configure<PublishingExtension> {
                repositories.maven {
                    name = "staging"
                    url = uri(repository)
                }
            }
        }
    }

    tasks.withType<CyclonedxDirectTask>().configureEach {
        includeConfigs.set(listOf("compileClasspath", "runtimeClasspath"))
        componentGroup.set(providers.gradleProperty("repostGroup"))
        componentName.set(project.name)
        componentVersion.set(providers.gradleProperty("repostVersion"))
        includeBomSerialNumber.set(false)
        includeBuildSystem.set(false)
        includeBuildEnvironment.set(false)
    }
}

tasks.named<CyclonedxAggregateTask>("cyclonedxBom") {
    componentGroup.set(providers.gradleProperty("repostGroup"))
    componentName.set("repost-jvm")
    componentVersion.set(providers.gradleProperty("repostVersion"))
    includeBomSerialNumber.set(false)
    includeBuildSystem.set(false)
    jsonOutput.set(layout.buildDirectory.file("reports/cyclonedx/repost-jvm-bom.json"))
    xmlOutput.set(layout.buildDirectory.file("reports/cyclonedx/repost-jvm-bom.xml"))
}

project(":repost-client") {
    val relocatedEngineSources = project(":repost-http-engine")
        .layout.buildDirectory.dir("vendor/relocated")

    dependencies {
        "api"(repostLibs.jspecify)
        "implementation"(repostLibs.jackson.core)
        "testImplementation"(platform(repostLibs.junit.bom))
        "testImplementation"(repostLibs.junit.jupiter)
        "testRuntimeOnly"("org.junit.platform:junit-platform-launcher")
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
    }

    tasks.named<JavaCompile>("compileJava") {
        dependsOn(":repost-http-engine:materializeHttpEngineSources")
        inputs.dir(relocatedEngineSources)
        options.sourcepath = files(relocatedEngineSources)
        options.compilerArgs.addAll(
            listOf(
                "-Xlint:all,-overloads,-serial,-fallthrough,-try,-dep-ann,-deprecation,-removal,-unchecked,-cast,-this-escape",
                "-Werror",
            ),
        )
    }
}

project(":repost-client-kotlin") {
    pluginManager.apply("org.jetbrains.kotlin.jvm")
    pluginManager.apply("org.jetbrains.dokka")
    pluginManager.apply("org.jetbrains.kotlinx.binary-compatibility-validator")

    extensions.configure<KotlinJvmProjectExtension> {
        jvmToolchain(repostLibs.versions.build.jdk.get().toInt())
        compilerOptions {
            languageVersion.set(KotlinVersion.KOTLIN_2_1)
            apiVersion.set(KotlinVersion.KOTLIN_2_1)
            jvmTarget.set(JvmTarget.JVM_11)
            allWarningsAsErrors.set(true)
        }
    }

    dependencies {
        "api"(platform(repostLibs.kotlin.bom))
        "api"(project(":repost-client"))
        "api"(repostLibs.kotlin.stdlib)
        "implementation"(repostLibs.kotlinx.coroutines.core)
        constraints {
            add("implementation", "org.jetbrains:annotations") {
                version {
                    strictly(repostLibs.versions.jetbrains.annotations.get())
                }
                because("Kotlin stdlib and coroutines request different historical annotation-only versions")
            }
        }
    }
}

project(":repost-gradle-plugin") {
    extensions.configure<JavaPluginExtension> {
        sourceSets.named("main") {
            java.srcDir(rootProject.layout.projectDirectory.dir("build-plugin-common/src/main/java"))
        }
    }

    dependencies {
        "compileOnly"(repostLibs.kotlin.gradle.plugin) { isTransitive = false }
        "compileOnly"(repostLibs.kotlin.gradle.plugin.api) { isTransitive = false }
        "compileOnly"(repostLibs.kotlin.tooling.core) { isTransitive = false }
        "testImplementation"(platform(repostLibs.junit.bom))
        "testImplementation"(repostLibs.junit.jupiter)
        "testImplementation"(repostLibs.kotlin.gradle.plugin)
        "testRuntimeOnly"("org.junit.platform:junit-platform-launcher")
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
    }
    tasks.withType<Javadoc>().configureEach {
        exclude("sh/repost/buildplugin/internal/**")
    }
}

project(":repost-maven-plugin") {
    extensions.configure<JavaPluginExtension> {
        sourceSets.named("main") {
            java.srcDir(rootProject.layout.projectDirectory.dir("build-plugin-common/src/main/java"))
        }
    }
    dependencies {
        "compileOnly"(repostLibs.maven.plugin.api) { isTransitive = false }
        "compileOnly"(repostLibs.maven.core) { isTransitive = false }
        "compileOnly"(repostLibs.maven.model) { isTransitive = false }
        "compileOnly"(repostLibs.maven.resolver.api) { isTransitive = false }
        "compileOnly"(repostLibs.maven.plugin.annotations) { isTransitive = false }
        "testImplementation"(platform(repostLibs.junit.bom))
        "testImplementation"(repostLibs.junit.jupiter)
        "testImplementation"(repostLibs.maven.plugin.api) { isTransitive = false }
        "testImplementation"(repostLibs.maven.core) { isTransitive = false }
        "testImplementation"(repostLibs.maven.model) { isTransitive = false }
        "testImplementation"(repostLibs.maven.resolver.api) { isTransitive = false }
        "testImplementation"(repostLibs.maven.plugin.annotations) { isTransitive = false }
        "testImplementation"(repostLibs.slf4j.api) { isTransitive = false }
        "testRuntimeOnly"("org.junit.platform:junit-platform-launcher")
    }
    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
    }
    tasks.withType<Javadoc>().configureEach {
        exclude("sh/repost/buildplugin/internal/**")
    }
}

val jvmWorkspaceDirectory = layout.projectDirectory.asFile
val nativeEngineSigningKeys = providers.gradleProperty("repostNativeEngineSigningKeysFile")
    .map(jvmWorkspaceDirectory::resolve)
val testNativeEngineSigningKeys = layout.projectDirectory.file(
    "repost-gradle-plugin/src/test/resources/sh/repost/gradle/ephemeral-native-engine-signing-key.pem",
).asFile
val embeddedNativeEngineSigningKeys = nativeEngineSigningKeys.orElse(testNativeEngineSigningKeys)
val nativeEnginePluginProjects = listOf(
    project(":repost-gradle-plugin"),
    project(":repost-maven-plugin"),
)

nativeEnginePluginProjects.forEach { pluginProject ->
    pluginProject.tasks.named<ProcessResources>("processResources") {
        from(embeddedNativeEngineSigningKeys) {
            into("META-INF/repost")
            rename { "native-engine-signing-keys.pem" }
        }
    }
}

val gradlePluginProject = project(":repost-gradle-plugin")
val gradlePluginArchive = gradlePluginProject.tasks.named<Jar>("jar")
val mavenPluginArchive = project(":repost-maven-plugin").tasks.named<Jar>("jar")
val nativeEngineTrustTestSourceSet = gradlePluginProject.extensions
    .getByType<JavaPluginExtension>().sourceSets.named("test")
val validateNativeEngineSigningTrust = gradlePluginProject.tasks.register<Test>(
    "validateNativeEngineSigningTrust",
) {
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    description = "Validates the public native-engine trust roots embedded in both build plugins."
    dependsOn(gradlePluginArchive, mavenPluginArchive)
    testClassesDirs = nativeEngineTrustTestSourceSet.get().output.classesDirs
    classpath = nativeEngineTrustTestSourceSet.get().runtimeClasspath
    filter.includeTestsMatching("sh.repost.gradle.NativeEngineTrustEmbeddingTest")
    jvmArgumentProviders.add(objects.newInstance<NativeEngineTrustArguments>().apply {
        signingKeysFile.fileProvider(nativeEngineSigningKeys)
        gradlePluginJar.set(gradlePluginArchive.flatMap { it.archiveFile })
        mavenPluginJar.set(mavenPluginArchive.flatMap { it.archiveFile })
    })
}

gradlePluginProject.tasks.named<Test>("test") {
    filter.excludeTestsMatching("sh.repost.gradle.NativeEngineTrustEmbeddingTest")
}
nativeEnginePluginProjects.forEach { pluginProject ->
    pluginProject.tasks.matching { it.name.startsWith("publish") }.configureEach {
        dependsOn(validateNativeEngineSigningTrust)
    }
}

project(":fixture-kotlin") {
    pluginManager.apply("org.jetbrains.kotlin.jvm")

    extensions.configure<KotlinJvmProjectExtension> {
        jvmToolchain(repostLibs.versions.build.jdk.get().toInt())
        compilerOptions {
            languageVersion.set(KotlinVersion.KOTLIN_2_1)
            apiVersion.set(KotlinVersion.KOTLIN_2_1)
            jvmTarget.set(JvmTarget.JVM_11)
            allWarningsAsErrors.set(true)
        }
    }
}

val coreJar = project(":repost-client").tasks.named<Jar>("jar")
val compatibilityBaseline = providers.gradleProperty("repostCompatibilityBaselineFile").map(::File)
val japicmpRepostClient = tasks.register<RunJapicmp>("japicmpRepostClient") {
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    description = "Rejects binary or source incompatible changes to repost-client against an approved baseline JAR."
    dependsOn(coreJar)
    toolClasspath.from(japicmpTool)
    baselineArchive.fileProvider(compatibilityBaseline)
    currentArchive.set(coreJar.flatMap { it.archiveFile })
    reportFile.set(layout.buildDirectory.file("reports/japicmp/repost-client.html"))
}

val checkJvmDocumentation = tasks.register("checkJvmDocumentation") {
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    description = "Generates the pinned Dokka documentation output."
    dependsOn(":repost-client:javadoc", ":repost-client-kotlin:dokkaGenerateHtml")
}

val apiCheck = tasks.register("apiCheck") {
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    description = "Checks every frozen Java API and Kotlin ABI baseline."
    dependsOn(subprojects.mapNotNull { it.tasks.findByName("apiCheck") })
}

tasks.register("apiDump") {
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    description = "Updates every reviewed Java API and Kotlin ABI baseline."
    dependsOn(subprojects.mapNotNull { it.tasks.findByName("apiDump") })
}

val checkJvmApiCompatibility = tasks.register("checkJvmApiCompatibility") {
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    description = "Runs Kotlin ABI and Java JApiCmp compatibility gates."
    dependsOn(apiCheck)
    if (compatibilityBaseline.isPresent) {
        dependsOn(japicmpRepostClient)
    }
}

tasks.register("compatibilityCheck") {
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    description = "Runs the frozen Java API and Kotlin ABI compatibility gates."
    dependsOn(checkJvmApiCompatibility)
}

val checkJvmSupplyChain = tasks.register("checkJvmSupplyChain") {
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    description = "Generates the CycloneDX SBOM and runs the OWASP dependency vulnerability gate."
    dependsOn("cyclonedxBom", "dependencyCheckAggregate", "verifyPublishedContents")
}

tasks.register("checkJvmReleaseQuality") {
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    description = "Runs documentation, API compatibility, SBOM, and dependency vulnerability release gates."
    dependsOn("checkJvmPublicationShape", checkJvmDocumentation, checkJvmApiCompatibility, checkJvmSupplyChain)
}

tasks.named("check") {
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    description = "Runs the JVM workspace policy and all module checks."
    dependsOn("checkJvmWorkspace")
    dependsOn(subprojects.mapNotNull { it.tasks.findByName("check") })
}
