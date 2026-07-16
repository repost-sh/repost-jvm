import org.gradle.util.GradleVersion
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    `kotlin-dsl`
    `java-gradle-plugin`
}

group = "sh.repost.build"
version = "0.0.0-SNAPSHOT"

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
}

kotlin {
    jvmToolchain(21)
    compilerOptions.jvmTarget.set(JvmTarget.JVM_11)
}

gradlePlugin {
    plugins {
        create("repostJvmWorkspace") {
            id = "repost.jvm.workspace"
            implementationClass = "sh.repost.build.RepostJvmWorkspacePlugin"
            displayName = "Repost JVM workspace conventions"
            description = "Enforces the Repost JVM module topology, baselines, and dependency boundaries."
        }
    }
}

dependencies {
    testImplementation(platform("org.junit:junit-bom:5.13.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

configurations.configureEach {
    val gradleOwnedKotlinTooling =
        setOf("kotlinBuildToolsApiClasspath", "kotlinCompilerClasspath")
    if (GradleVersion.current() < GradleVersion.version("9.0") || name !in gradleOwnedKotlinTooling) {
        resolutionStrategy.failOnVersionConflict()
    }
}

dependencyLocking {
    if (GradleVersion.current() >= GradleVersion.version("9.0")) {
        lockFile.set(layout.projectDirectory.file("gradle-${GradleVersion.current().version}.lockfile"))
    }
    lockAllConfigurations()
    lockMode.set(LockMode.STRICT)
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(11)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}

tasks.withType<AbstractArchiveTask>().configureEach {
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}
