dependencies {
    api(project(":repost-client"))
    api(libs.micrometer.core)
    testImplementation(project(":repost-client-test"))
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}
