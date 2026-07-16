plugins { java }

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(
        providers.gradleProperty("jvmVersion").get().toInt(),
    ))
}

val bootVersion = providers.gradleProperty("bootVersion").get()

dependencies {
    implementation(enforcedPlatform("org.springframework.boot:spring-boot-dependencies:$bootVersion"))
    implementation("sh.repost:repost-client-spring-boot-starter:1.0.0")
    implementation("sh.repost:repost-client-micrometer:1.0.0")
    implementation("sh.repost:repost-client-opentelemetry:1.0.0")
    implementation("org.springframework.boot:spring-boot-actuator")
    implementation("org.springframework.boot:spring-boot-autoconfigure")
    implementation("org.springframework.boot:spring-boot-health")
    implementation("org.springframework.boot:spring-boot-jackson2")
    implementation("io.micrometer:micrometer-core")
    implementation("io.opentelemetry:opentelemetry-api")
    implementation("tools.jackson.core:jackson-databind")

    testImplementation("org.springframework.boot:spring-boot-test")
    testImplementation("org.assertj:assertj-core")
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

configurations.configureEach {
    resolutionStrategy.failOnVersionConflict()
}

tasks.test { useJUnitPlatform() }
