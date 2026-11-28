plugins {
    `java-library`
    `maven-publish`
    id("sh.repost.sdk")
}

group = "com.acme.consumer"
version = "1.0.0"
repostSdk.generators.named("kotlinSdk") { enabled.set(false) }
dependencies {
    api(platform("sh.repost:repost-bom:${providers.gradleProperty("repostVersion").get()}"))
    api("sh.repost:repost-client")
}
java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}
publishing {
    publications { create<MavenPublication>("consumer") { from(components["java"]) } }
    repositories { maven { url = uri(providers.gradleProperty("consumerRepository").get()) } }
}
