plugins {
    application
    id("sh.repost.sdk") version "1.0.0"
}

repostSdk.generators.named("kotlinSdk") { enabled.set(false) }

dependencies {
    implementation(platform("sh.repost:repost-bom:1.0.0"))
    implementation("sh.repost:repost-client")
    implementation("sh.repost:repost-client-test")
}

java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

application { mainClass.set("com.acme.consumer.ConsumerSmoke") }
tasks.check { dependsOn(tasks.run) }
