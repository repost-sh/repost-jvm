plugins {
    application
    id("org.jetbrains.kotlin.jvm")
    id("sh.repost.sdk") version "1.0.0"
}

val ktorVersion = providers.gradleProperty("ktorVersion").get()
val otelVersion = providers.gradleProperty("otelVersion").get()

dependencies {
    implementation(platform("sh.repost:repost-bom:1.0.0"))
    implementation("sh.repost:repost-client-kotlin")
    implementation("sh.repost:repost-client-opentelemetry")
    implementation("sh.repost:repost-client-test")
    implementation("io.ktor:ktor-server-netty-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-core-jvm:$ktorVersion")
    implementation("io.opentelemetry:opentelemetry-sdk:$otelVersion")
    implementation("io.opentelemetry:opentelemetry-sdk-testing:$otelVersion")
}

kotlin {
    jvmToolchain(21)
}

application {
    mainClass.set("cert.KtorCertificationKt")
    applicationDefaultJvmArgs = listOf("-Dcert.ktorVersion=$ktorVersion")
}
