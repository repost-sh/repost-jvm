plugins {
    application
    java
    id("sh.repost.sdk") version "1.0.0"
}

val micronautVersion = providers.gradleProperty("micronautVersion").get()

dependencies {
    implementation(platform("sh.repost:repost-bom:1.0.0"))
    implementation("sh.repost:repost-client")
    implementation("sh.repost:repost-client-test")

    annotationProcessor(platform("io.micronaut.platform:micronaut-platform:$micronautVersion"))
    annotationProcessor("io.micronaut:micronaut-inject-java")
    implementation(platform("io.micronaut.platform:micronaut-platform:$micronautVersion"))
    implementation("io.micronaut:micronaut-http-server-netty")
    implementation("io.micronaut.serde:micronaut-serde-jackson")
    runtimeOnly("org.slf4j:slf4j-simple:2.0.17")
}

repostSdk {
    generators.named("kotlinSdk") { enabled.set(false) }
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}

application {
    mainClass.set("cert.MicronautCertification")
    applicationDefaultJvmArgs = listOf("-Dcert.micronautVersion=$micronautVersion")
}
