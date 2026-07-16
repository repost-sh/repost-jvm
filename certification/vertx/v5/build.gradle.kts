plugins {
    application
    java
    id("sh.repost.sdk") version "1.0.0"
}

val vertxVersion = providers.gradleProperty("vertxVersion").get()

dependencies {
    implementation(platform("sh.repost:repost-bom:1.0.0"))
    implementation("sh.repost:repost-client")
    implementation("sh.repost:repost-client-test")
    implementation("io.vertx:vertx-core:$vertxVersion")
}

sourceSets.main {
    java.srcDir("../v4/src/main/java")
}

repostSdk {
    schemaFile.set(file("../v4/repost/schema.repost"))
    generators.named("kotlinSdk") { enabled.set(false) }
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
}

application {
    mainClass.set("cert.VertxCertification")
    applicationDefaultJvmArgs = listOf("-Dcert.vertxVersion=$vertxVersion")
}
