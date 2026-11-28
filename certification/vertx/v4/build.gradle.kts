plugins {
    application
    java
    id("sh.repost.sdk")
}

val vertxVersion = providers.gradleProperty("vertxVersion").get()

dependencies {
    implementation(platform("sh.repost:repost-bom:${providers.gradleProperty("repostVersion").get()}"))
    implementation("sh.repost:repost-client")
    implementation("sh.repost:repost-client-test")
    implementation("io.vertx:vertx-core:$vertxVersion")
}

repostSdk {
    generators.named("kotlinSdk") { enabled.set(false) }
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
}

application {
    mainClass.set("cert.VertxCertification")
    applicationDefaultJvmArgs = listOf("-Dcert.vertxVersion=$vertxVersion")
}
