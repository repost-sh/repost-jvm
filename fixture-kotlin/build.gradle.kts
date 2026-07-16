import org.gradle.api.tasks.testing.Test
import org.gradle.language.jvm.tasks.ProcessResources
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import java.net.URI

val stagedRepository = providers.gradleProperty("repostRepository")
    .orElse(providers.environmentVariable("REPOST_JVM_STAGED_REPOSITORY"))
    .orNull
    ?.let { value -> rootProject.file(if (value.startsWith("file:")) URI(value) else value) }
val stagedVersion = providers.environmentVariable("REPOST_JVM_VERSION")
    .orElse(providers.gradleProperty("repostVersion"))
    .get()

fun stagedArtifact(artifactId: String) = files(
    requireNotNull(stagedRepository)
        .resolve("sh/repost/$artifactId/$stagedVersion/$artifactId-$stagedVersion.jar")
        .also { require(it.isFile) { "missing staged JVM artifact: $it" } },
)

extensions.configure<KotlinJvmProjectExtension> {
    sourceSets.named("main") {
        kotlin.srcDirs(
            rootProject.file("../../repost-rs/repost-schema/schema/tests/kotlin_sdk/canonical/source"),
            rootProject.file("../../repost-rs/repost-schema/schema/tests/kotlin_sdk/custom_clients/orders/source"),
            rootProject.file("../../repost-rs/repost-schema/schema/tests/kotlin_sdk/custom_clients/billing/source"),
        )
        kotlin.include(
            "**/Author.kt",
            "**/BillingClient.kt",
            "**/BillingClientFactory.kt",
            "**/Book.kt",
            "**/BookWebhooks.kt",
            "**/Currency.kt",
            "**/Invoice.kt",
            "**/InvoiceWebhooks.kt",
            "**/LineItem.kt",
            "**/Order.kt",
            "**/OrderStatus.kt",
            "**/OrderWebhooks.kt",
            "**/OrdersClient.kt",
            "**/OrdersClientFactory.kt",
            "**/RepostClient.kt",
            "**/RepostClientFactory.kt",
            "**/SchemaDescriptors.kt",
            "**/Webhooks.kt",
        )
    }
    sourceSets.named("test") {
        kotlin.srcDir(rootProject.file("../../repost-rs/repost-schema/schema/tests/kotlin_sdk/field_matrix/source"))
        kotlin.exclude(
            "**/RepostClient.kt",
            "**/RepostClientFactory.kt",
            "**/SchemaDescriptors.kt",
            "**/Webhooks.kt",
        )
    }
}

dependencies {
    "implementation"(enforcedPlatform(libs.kotlin.bom))
    if (stagedRepository == null) {
        "implementation"(project(":repost-client-kotlin"))
        "testImplementation"(project(":repost-client-test"))
    } else {
        "implementation"(stagedArtifact("repost-client"))
        "implementation"(stagedArtifact("repost-client-kotlin"))
        "runtimeOnly"(libs.jackson.core)
        "testImplementation"(stagedArtifact("repost-client-test"))
    }
    "implementation"(libs.kotlin.stdlib)
    "implementation"(libs.jspecify)
    "testImplementation"(libs.kotlinx.coroutines.core)
    "testImplementation"(platform(libs.junit.bom))
    "testImplementation"(libs.junit.jupiter)
    "testRuntimeOnly"("org.junit.platform:junit-platform-launcher")
    constraints {
        add("implementation", "org.jetbrains:annotations") {
            version {
                strictly(libs.versions.jetbrains.annotations.get())
            }
            because("Kotlin stdlib and coroutines request different historical annotation-only versions")
        }
    }
}

tasks.named<ProcessResources>("processResources") {
    from(rootProject.file("../../repost-rs/repost-schema/schema/tests/kotlin_sdk/custom_clients/orders/resources/client.json")) {
        into("META-INF/repost/generated-clients/v1/79ddc13e228dee9f")
    }
    from(rootProject.file("../../repost-rs/repost-schema/schema/tests/kotlin_sdk/custom_clients/billing/resources/client.json")) {
        into("META-INF/repost/generated-clients/v1/0c778e4f4d988328")
    }
}

if (providers.gradleProperty("repostCompileKotlinGoldens").orNull == "true") {
    val goldenRoot = rootProject.file("../../repost-rs/repost-schema/schema/tests/kotlin_sdk")
    val goldenFixtures = goldenRoot.listFiles()
        .orEmpty()
        .filter { fixture -> fixture.isDirectory && fixture.resolve("source").isDirectory }
        .sortedBy { fixture -> fixture.name }
    val goldenImplementation = configurations.create("goldenImplementation") {
        isCanBeConsumed = false
        isCanBeResolved = false
    }
    dependencies.add(goldenImplementation.name, project(":repost-client-kotlin"))

    val goldenCompileTasks = goldenFixtures.map { fixture ->
        val taskSuffix = fixture.name
            .split('_')
            .joinToString("") { part -> part.replaceFirstChar(Char::uppercase) }
        val sourceSet = sourceSets.create("golden$taskSuffix") {
            java.setSrcDirs(emptyList<String>())
            resources.setSrcDirs(emptyList<String>())
        }
        extensions.configure<KotlinJvmProjectExtension> {
            sourceSets.named(sourceSet.name) {
                kotlin.setSrcDirs(listOf(fixture.resolve("source")))
            }
        }
        configurations.named(sourceSet.implementationConfigurationName) {
            extendsFrom(goldenImplementation)
        }
        tasks.named<KotlinCompile>(sourceSet.getCompileTaskName("kotlin")) {
            group = LifecycleBasePlugin.VERIFICATION_GROUP
            description = "Compiles the ${fixture.name} generated Kotlin golden."
            compilerOptions {
                moduleName.set("repost-kotlin-golden-${fixture.name}")
                languageVersion.set(KotlinVersion.KOTLIN_2_1)
                apiVersion.set(KotlinVersion.KOTLIN_2_1)
                jvmTarget.set(JvmTarget.JVM_11)
                allWarningsAsErrors.set(true)
                freeCompilerArgs.add("-Xexplicit-api=strict")
            }
        }
    }

    tasks.register("compileKotlinGoldens") {
        group = LifecycleBasePlugin.VERIFICATION_GROUP
        description = "Compiles every generated Kotlin golden against the production client API."
        dependsOn(goldenCompileTasks)
    }
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    if (stagedRepository != null) {
        val forbidden = listOf(":repost-client", ":repost-client-kotlin", ":repost-client-test")
            .map { path -> project(path).layout.buildDirectory.get().asFile.absolutePath }
        doFirst {
            require(classpath.files.none { file -> forbidden.any(file.absolutePath::startsWith) }) {
                "staged Kotlin fixture classpath contains reactor client output"
            }
        }
    }
}
