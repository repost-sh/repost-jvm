import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

plugins {
    application
    kotlin("jvm")
    id("sh.repost.sdk")
}

repostSdk.generators.named("javaSdk") { enabled.set(false) }

dependencies {
    implementation(platform("sh.repost:repost-bom:${providers.gradleProperty("repostVersion").get()}"))
    implementation("sh.repost:repost-client-kotlin")
    implementation("sh.repost:repost-client-test")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
}

java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}
kotlin.compilerOptions {
    languageVersion.set(KotlinVersion.KOTLIN_2_1)
    apiVersion.set(KotlinVersion.KOTLIN_2_1)
    jvmTarget.set(JvmTarget.JVM_11)
}

application { mainClass.set("com.acme.consumer.ConsumerSmokeKt") }
tasks.check { dependsOn(tasks.run) }
