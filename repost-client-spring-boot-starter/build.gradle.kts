import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.api.tasks.testing.Test
import org.gradle.api.artifacts.VersionCatalogsExtension

val versionCatalog = extensions.getByType<VersionCatalogsExtension>().named("libs")
val boot40Version = versionCatalog.findVersion("spring-boot-40").get().requiredVersion
val boot41Version = versionCatalog.findVersion("spring-boot-41").get().requiredVersion

val boot41TestRuntimeClasspath = configurations.create("boot41TestRuntimeClasspath") {
    isCanBeConsumed = false
    isCanBeResolved = true
}

// Spring Boot's dependency management decides the versions in each Boot lane,
// as in a Boot application. The platform sits on compileOnly and the test
// buckets, so it never reaches the published POM or module metadata.
val bootTestLibraries = listOf(
    "org.springframework.boot:spring-boot-autoconfigure",
    "org.springframework.boot:spring-boot-health",
    "org.springframework.boot:spring-boot-actuator",
    "org.springframework.boot:spring-boot-actuator-autoconfigure",
    "org.springframework.boot:spring-boot-test",
    "com.fasterxml.jackson.core:jackson-annotations",
    "org.yaml:snakeyaml",
    "io.micrometer:micrometer-core",
    "io.opentelemetry:opentelemetry-api",
    "io.opentelemetry:opentelemetry-sdk",
    "io.opentelemetry:opentelemetry-sdk-testing",
    "org.assertj:assertj-core",
)

dependencies {
    api(project(":repost-client"))

    compileOnly(enforcedPlatform("org.springframework.boot:spring-boot-dependencies:$boot40Version"))
    compileOnly("org.springframework.boot:spring-boot-autoconfigure")
    compileOnly("org.springframework.boot:spring-boot-health")
    compileOnly("com.fasterxml.jackson.core:jackson-annotations")
    annotationProcessor("org.springframework.boot:spring-boot-configuration-processor:$boot40Version")
    compileOnly(project(":repost-client-micrometer"))
    compileOnly(project(":repost-client-opentelemetry"))
    compileOnly("io.micrometer:micrometer-core")
    compileOnly("io.opentelemetry:opentelemetry-api")

    testImplementation(enforcedPlatform("org.springframework.boot:spring-boot-dependencies:$boot40Version"))
    bootTestLibraries.forEach { testImplementation(it) }
    testImplementation(project(":repost-client-test"))
    testImplementation(project(":repost-client-micrometer"))
    testImplementation(project(":repost-client-opentelemetry"))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    add(boot41TestRuntimeClasspath.name, enforcedPlatform("org.springframework.boot:spring-boot-dependencies:$boot41Version"))
    bootTestLibraries.forEach { add(boot41TestRuntimeClasspath.name, it) }
    add(boot41TestRuntimeClasspath.name, project(":" + project.name))
    add(boot41TestRuntimeClasspath.name, project(":repost-client-test"))
    add(boot41TestRuntimeClasspath.name, project(":repost-client-micrometer"))
    add(boot41TestRuntimeClasspath.name, project(":repost-client-opentelemetry"))
    add(boot41TestRuntimeClasspath.name, libs.junit.jupiter)
    add(boot41TestRuntimeClasspath.name, "org.junit.platform:junit-platform-launcher")
}

tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.add("-parameters")
}

tasks.test {
    useJUnitPlatform()
}

val boot41Test = tasks.register<Test>("boot41Test") {
    description = "Runs the Boot $boot40Version-compiled starter tests on Spring Boot $boot41Version."
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().output + boot41TestRuntimeClasspath
    dependsOn(tasks.testClasses)
    useJUnitPlatform()
}

tasks.check {
    dependsOn(boot41Test)
}
