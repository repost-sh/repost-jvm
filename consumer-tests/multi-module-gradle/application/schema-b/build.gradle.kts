plugins {
    `java-library`
    id("sh.repost.sdk")
}
repostSdk.generators.named("kotlinSdk") { enabled.set(false) }
dependencies {
    api(platform("sh.repost:repost-bom:${providers.gradleProperty("repostVersion").get()}"))
    api("sh.repost:repost-client")
}
java { sourceCompatibility = JavaVersion.VERSION_11; targetCompatibility = JavaVersion.VERSION_11 }
