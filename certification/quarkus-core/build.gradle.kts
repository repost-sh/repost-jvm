plugins {
    java
    id("io.quarkus")
    id("sh.repost.sdk")
}

val quarkusVersion = providers.gradleProperty("quarkusVersion").get()

dependencies {
    implementation(platform("sh.repost:repost-bom:${providers.gradleProperty("repostVersion").get()}"))
    implementation("sh.repost:repost-client")
    implementation("sh.repost:repost-client-test")
    implementation(enforcedPlatform("io.quarkus.platform:quarkus-bom:$quarkusVersion"))
    implementation("io.quarkus:quarkus-arc")
    implementation("io.quarkus:quarkus-rest")
}

repostSdk {
    generators.named("kotlinSdk") { enabled.set(false) }
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
}

tasks.withType<JavaExec>().configureEach {
    systemProperty("cert.quarkusVersion", quarkusVersion)
}
