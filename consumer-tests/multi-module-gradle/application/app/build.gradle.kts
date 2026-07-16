import sh.repost.gradle.RepostIntegration
import sh.repost.gradle.RepostSchemaMode

plugins {
    java
    id("sh.repost.sdk") version "1.0.0"
}

val selectedIntegration = providers.gradleProperty("repostIntegration").orElse("SPRING_BOOT")
repostSdk {
    schemaMode.set(RepostSchemaMode.AGGREGATE_ONLY)
    integration.set(selectedIntegration.map(RepostIntegration::valueOf))
}
dependencies {
    implementation(platform("sh.repost:repost-bom:1.0.0"))
    implementation(project(":schema-a"))
    if (providers.gradleProperty("includeSchemaB").orElse("true").get().toBoolean()) {
        implementation(project(":schema-b"))
    }
    implementation("com.acme.consumer:external-clients:1.0.0")
    if (selectedIntegration.get() == "SPRING_BOOT") {
        implementation("sh.repost:repost-client-spring-boot-starter")
        implementation("org.springframework.boot:spring-boot-autoconfigure:4.0.7")
    } else {
        implementation("sh.repost:repost-client-cdi")
        compileOnly("jakarta.enterprise:jakarta.enterprise.cdi-api:4.0.1")
    }
}
java { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
