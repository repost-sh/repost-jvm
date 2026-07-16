plugins {
    `java-library`
}

dependencies {
    api(project(":repost-client"))

    compileOnly(libs.jakarta.cdi.api)
    compileOnly(libs.microprofile.config.api)
    compileOnly(project(":repost-client-micrometer"))
    compileOnly(project(":repost-client-opentelemetry"))

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.jakarta.cdi.api)
    testImplementation(libs.microprofile.config.api)
    testImplementation(project(":repost-client-micrometer"))
    testImplementation(project(":repost-client-opentelemetry"))
    testImplementation(libs.micrometer.core)
    testImplementation(libs.opentelemetry.api)
    testImplementation(libs.opentelemetry.sdk)
    testImplementation(libs.opentelemetry.sdk.testing)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
