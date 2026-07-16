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

dependencies {
    api(project(":repost-client"))

    compileOnly("org.springframework.boot:spring-boot-autoconfigure:$boot40Version")
    compileOnly("org.springframework.boot:spring-boot-health:$boot40Version")
    compileOnly("com.fasterxml.jackson.core:jackson-annotations:2.21")
    annotationProcessor("org.springframework.boot:spring-boot-configuration-processor:$boot40Version")
    compileOnly(project(":repost-client-micrometer")) {
        exclude(group = "io.micrometer", module = "micrometer-core")
    }
    compileOnly(project(":repost-client-opentelemetry"))
    compileOnly("io.micrometer:micrometer-core:1.16.6")
    compileOnly(libs.opentelemetry.api)

    testImplementation("org.springframework.boot:spring-boot-autoconfigure:$boot40Version")
    testImplementation("org.springframework.boot:spring-boot-health:$boot40Version")
    testImplementation("org.springframework.boot:spring-boot-actuator:$boot40Version")
    testImplementation("org.springframework.boot:spring-boot-actuator-autoconfigure:$boot40Version")
    testImplementation("com.fasterxml.jackson.core:jackson-annotations:2.21")
    testImplementation("org.springframework.boot:spring-boot-test:$boot40Version")
    testImplementation("org.yaml:snakeyaml:2.5")
    testImplementation(project(":repost-client-test"))
    testImplementation(project(":repost-client-micrometer")) {
        exclude(group = "io.micrometer", module = "micrometer-core")
    }
    testImplementation(project(":repost-client-opentelemetry"))
    testImplementation("io.micrometer:micrometer-core:1.16.6")
    testImplementation(libs.opentelemetry.api)
    testImplementation(libs.opentelemetry.sdk)
    testImplementation(libs.opentelemetry.sdk.testing)
    testImplementation("org.assertj:assertj-core:3.27.7")
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    add(boot41TestRuntimeClasspath.name, project(":" + project.name))
    add(boot41TestRuntimeClasspath.name, "org.springframework.boot:spring-boot-autoconfigure:$boot41Version")
    add(boot41TestRuntimeClasspath.name, "org.springframework.boot:spring-boot-health:$boot41Version")
    add(boot41TestRuntimeClasspath.name, "org.springframework.boot:spring-boot-actuator:$boot41Version")
    add(boot41TestRuntimeClasspath.name, "org.springframework.boot:spring-boot-actuator-autoconfigure:$boot41Version")
    add(boot41TestRuntimeClasspath.name, "org.springframework.boot:spring-boot-test:$boot41Version")
    add(boot41TestRuntimeClasspath.name, project(":repost-client-test"))
    add(boot41TestRuntimeClasspath.name, project(":repost-client-micrometer")) {
        exclude(group = "io.micrometer", module = "micrometer-core")
    }
    add(boot41TestRuntimeClasspath.name, project(":repost-client-opentelemetry"))
    add(boot41TestRuntimeClasspath.name, "io.micrometer:micrometer-core:1.16.6")
    add(boot41TestRuntimeClasspath.name, libs.opentelemetry.api)
    add(boot41TestRuntimeClasspath.name, libs.opentelemetry.sdk)
    add(boot41TestRuntimeClasspath.name, libs.opentelemetry.sdk.testing)
    add(boot41TestRuntimeClasspath.name, "com.fasterxml.jackson.core:jackson-annotations:2.21")
    add(boot41TestRuntimeClasspath.name, "org.yaml:snakeyaml:2.5")
    add(boot41TestRuntimeClasspath.name, "org.assertj:assertj-core:3.27.7")
    add(boot41TestRuntimeClasspath.name, platform(libs.junit.bom))
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
