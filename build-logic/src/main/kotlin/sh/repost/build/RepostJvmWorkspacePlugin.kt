package sh.repost.build

import org.gradle.api.JavaVersion
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.ExternalModuleDependency
import org.gradle.api.artifacts.ProjectDependency
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DuplicatesStrategy
import org.gradle.api.plugins.BasePluginExtension
import org.gradle.api.plugins.JavaLibraryPlugin
import org.gradle.api.plugins.JavaPlugin
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.plugins.JavaPlatformPlugin
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.publish.maven.plugins.MavenPublishPlugin
import org.gradle.api.publish.maven.tasks.GenerateMavenPom
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.Exec
import org.gradle.api.tasks.TaskProvider
import org.gradle.api.tasks.bundling.AbstractArchiveTask
import org.gradle.api.tasks.bundling.Jar
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.api.tasks.javadoc.Javadoc
import org.gradle.api.tasks.testing.Test
import org.gradle.plugin.devel.GradlePluginDevelopmentExtension
import org.gradle.plugin.devel.plugins.JavaGradlePluginPlugin
import org.gradle.external.javadoc.StandardJavadocDocletOptions
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.jvm.toolchain.JavaToolchainService
import org.gradle.language.base.plugins.LifecycleBasePlugin
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.named
import org.gradle.kotlin.dsl.register
import org.gradle.kotlin.dsl.withType

class RepostJvmWorkspacePlugin : Plugin<Project> {
    override fun apply(root: Project) {
        require(root == root.rootProject) { "repost.jvm.workspace must be applied to the root project" }
        root.pluginManager.apply("base")

        JvmModuleCatalog.modules.values.forEach { spec ->
            val project = root.project(":${spec.name}")
            configureModule(project, spec)
        }

        val topology = root.tasks.register<CheckJvmTopology>("checkJvmTopology") {
            group = LifecycleBasePlugin.VERIFICATION_GROUP
            description = "Checks the complete §3.2 module, coordinate, baseline, and JPMS topology."
            expectedModules.set(JvmModuleCatalog.modules.keys.sorted())
            expectedGroup.set(JvmModuleCatalog.GROUP)
            expectedArtifactIdentities.set(JvmModuleCatalog.expectedArtifactIdentities().map(JvmArtifactIdentity::encode))
        }

        val boundaries = root.tasks.register<CheckJvmDependencyBoundaries>("checkJvmDependencyBoundaries") {
            group = LifecycleBasePlugin.VERIFICATION_GROUP
            description = "Rejects outward or engine-project dependencies and an expanded Java core graph."
            dependsOn(
                JvmDependencyGraphPolicy.resolvedGraphModules.map { module ->
                    val moduleProject = root.project(":$module")
                    val configurations = listOf("compileClasspath", "runtimeClasspath").map {
                        moduleProject.configurations.getByName(it)
                    }
                    moduleProject.tasks.register<CheckJvmResolvedDependencyGraph>("checkResolvedDependencyGraph") {
                        group = LifecycleBasePlugin.VERIFICATION_GROUP
                        description = "Checks the canonical compile and runtime dependency paths for $module."
                        violations.set(moduleProject.providers.provider {
                            val paths = configurations.flatMap { configuration ->
                                JvmResolvedDependencyPathCollector.collect(module, configuration)
                            }
                            JvmDependencyGraphPolicy.violations(emptyList(), paths)
                        })
                    }
                },
            )
        }

        val environment = root.tasks.register<CheckJvmBuildEnvironment>("checkJvmBuildEnvironment") {
            group = LifecycleBasePlugin.VERIFICATION_GROUP
            description = "Checks the pinned Gradle wrapper and build JDK."
            runningGradle.set(root.gradle.gradleVersion)
            expectedGradle.set("8.12.1")
            runningJava.set(JavaVersion.current().majorVersion.toInt())
            expectedJava.set(21)
        }

        val versionLiterals = root.tasks.register<CheckOwnedVersionLiterals>("checkOwnedVersionLiterals") {
            group = LifecycleBasePlugin.VERIFICATION_GROUP
            description = "Rejects compatibility-matrix version copies in JVM workflows and sample builds."
            val repository = root.layout.projectDirectory.dir("../..").asFile
            compatibilityMatrix.set(root.layout.projectDirectory.file("compatibility-matrix.toml"))
            repositoryRoot.set(repository)
            candidates.from(
                root.fileTree(repository.resolve(".github/workflows")) {
                    include("*jvm*.yml", "*jvm*.yaml")
                },
                root.fileTree(root.layout.projectDirectory) {
                    include(
                        "fixture-*/build.gradle",
                        "fixture-*/build.gradle.kts",
                        "samples/**/build.gradle",
                        "samples/**/build.gradle.kts",
                        "samples/**/pom.xml",
                        "repost-client-spring-boot-starter/build.gradle.kts",
                        "certification/**/build.gradle",
                        "certification/**/build.gradle.kts",
                        "certification/**/settings.gradle",
                        "certification/**/settings.gradle.kts",
                        "certification/**/Dockerfile*",
                        "certification/**/src/**/*.java",
                        "certification/**/src/**/*.kt",
                    )
                },
            )
        }

        val fixtureJava = root.project(":fixture-java")
        val clientTest = root.project(":repost-client-test")
        val fixtureMain = fixtureJava.extensions
            .getByType(JavaPluginExtension::class.java).sourceSets.getByName("main")
        val fixtureTest = fixtureJava.extensions
            .getByType(JavaPluginExtension::class.java).sourceSets.getByName("test")
        val clientTestMain = clientTest.extensions
            .getByType(JavaPluginExtension::class.java).sourceSets.getByName("main")
        val javaToolchains = fixtureJava.extensions.getByType(JavaToolchainService::class.java)
        val virtualThreadCallers = listOf(21, 25).map { feature ->
            fixtureJava.tasks.register<Test>("virtualThreadCallersJdk$feature") {
                group = LifecycleBasePlugin.VERIFICATION_GROUP
                description = "Runs generated-client virtual-thread callers on JDK $feature."
                testClassesDirs = fixtureTest.output.classesDirs
                classpath = fixtureTest.runtimeClasspath
                useJUnitPlatform()
                filter.includeTestsMatching("com.repost.fixture.VirtualThreadCallerTest")
                javaLauncher.set(javaToolchains.launcherFor {
                    languageVersion.set(JavaLanguageVersion.of(feature))
                })
            }
        }
        val stagedConsumer = root.providers.gradleProperty("repostRepository")
            .orElse(root.providers.environmentVariable("REPOST_JVM_STAGED_REPOSITORY"))
            .isPresent
        val nativeSmokeClasspath = if (stagedConsumer) {
            root.files(
                fixtureMain.output,
                fixtureJava.configurations.getByName(JavaPlugin.TEST_RUNTIME_CLASSPATH_CONFIGURATION_NAME),
            )
        } else {
            root.files(
                fixtureMain.output,
                clientTestMain.output,
                fixtureJava.configurations.getByName(JavaPlugin.RUNTIME_CLASSPATH_CONFIGURATION_NAME),
                clientTest.configurations.getByName(JavaPlugin.RUNTIME_CLASSPATH_CONFIGURATION_NAME),
            )
        }
        val nativeSmokeSource = fixtureJava.layout.projectDirectory
            .file("src/nativeSmoke/java/com/repost/fixture/CoreNativeSmoke.java")
        val nativeSmokeScript = root.layout.projectDirectory.file("../../scripts/core-native-smoke.sh")
        val forbiddenNativeSmokeOutputs = listOf(root.project(":repost-client"), clientTest)
            .map { project -> project.layout.buildDirectory.get().asFile.absolutePath }
        root.tasks.register<Exec>("coreNativeSmoke") {
            group = LifecycleBasePlugin.VERIFICATION_GROUP
            description = "Builds and runs the framework-neutral core smoke with GraalVM 25."
            dependsOn(fixtureJava.tasks.named(fixtureMain.classesTaskName), virtualThreadCallers)
            if (!stagedConsumer) {
                dependsOn(clientTest.tasks.named(clientTestMain.classesTaskName))
            }
            inputs.file(nativeSmokeSource)
            inputs.file(nativeSmokeScript)
            inputs.files(nativeSmokeClasspath)
            outputs.upToDateWhen { false }
            environment("REPOST_NATIVE_SMOKE_CLASSPATH", nativeSmokeClasspath.asPath)
            if (stagedConsumer) {
                doFirst {
                    require(
                        nativeSmokeClasspath.files.none { file ->
                            forbiddenNativeSmokeOutputs.any(file.absolutePath::startsWith)
                        },
                    ) {
                        "staged native smoke classpath contains reactor client output"
                    }
                }
            }
            commandLine(
                nativeSmokeScript.asFile.absolutePath,
                nativeSmokeSource.asFile.absolutePath,
                root.layout.buildDirectory.dir("native-smoke").get().asFile.absolutePath,
            )
        }
        root.tasks.register<WriteGradleCompatibilityMatrix>("writeGradleCompatibilityMatrix") {
            group = LifecycleBasePlugin.VERIFICATION_GROUP
            description = "Writes the approved Gradle and paired Kotlin CI lanes from compatibility-matrix.toml."
            compatibilityMatrix.set(root.layout.projectDirectory.file("compatibility-matrix.toml"))
            githubOutput.set(root.layout.buildDirectory.file("compatibility/gradle-matrix.env"))
        }

        val publicationShape = root.tasks.register<CheckJvmPublicationShape>("checkJvmPublicationShape") {
            group = LifecycleBasePlugin.VERIFICATION_GROUP
            description = "Checks generated POM metadata and the complete Maven publication artifact inventory."
            workspaceDirectory.set(root.layout.projectDirectory)
            val inputs = JvmPomMetadataInputs(root)
            JvmPomMetadataProperty.values().forEach { property ->
                metadata.put(property.gradleProperty, inputs[property])
            }
        }
        val releaseFiles = root.objects.fileCollection()
        val supplyChainInputs = root.tasks.register<CheckJvmSupplyChainInputs>("checkJvmSupplyChainInputs") {
            group = LifecycleBasePlugin.VERIFICATION_GROUP
            description = "Rejects dynamic pins, wrapper drift, missing locks, and unverified dependency bytes."
            versionCatalog.set(root.layout.projectDirectory.file("gradle/libs.versions.toml"))
            wrapperProperties.set(root.layout.projectDirectory.file("gradle/wrapper/gradle-wrapper.properties"))
            verificationMetadata.set(root.layout.projectDirectory.file("gradle/verification-metadata.xml"))
            lockFiles.from(root.fileTree(root.layout.projectDirectory) {
                include("**/gradle.lockfile", "settings-gradle.lockfile")
                exclude("**/build/**")
            })
            workspaceDirectory.set(root.layout.projectDirectory)
        }
        val publishedContents = root.tasks.register<VerifyJvmPublishedContents>("verifyPublishedContents") {
            group = LifecycleBasePlugin.VERIFICATION_GROUP
            description = "Scans release artifacts and writes deterministic provenance subjects."
            artifacts.from(releaseFiles)
            workspaceDirectory.set(root.layout.projectDirectory)
            provenanceSubjects.set(root.layout.buildDirectory.file("reports/supply-chain/provenance-subjects.json"))
            dependsOn(supplyChainInputs)
        }
        root.tasks.register<VerifyReproducibleArchives>("verifyReproducibleArchives") {
            group = LifecycleBasePlugin.VERIFICATION_GROUP
            description = "Compares release hashes with an independent clean build."
            artifacts.from(releaseFiles)
            workspaceDirectory.set(root.layout.projectDirectory)
            referenceManifest.fileProvider(
                root.providers.gradleProperty("repostReproducibilityReferenceManifest").map { path -> java.io.File(path) },
            )
            sourceDateEpoch.set(root.providers.environmentVariable("SOURCE_DATE_EPOCH"))
            dependsOn(publishedContents)
        }

        root.gradle.projectsEvaluated {
            topology.configure {
                actualModules.set(root.subprojects.map { it.name }.sorted())
                expectedVersion.set(root.version.toString())
                actualGroups.set(root.subprojects.associate { it.path to it.group.toString() })
                actualVersions.set(root.subprojects.associate { it.path to it.version.toString() })
                actualArtifactIdentities.set(root.subprojects.map(::actualArtifactIdentity).map(JvmArtifactIdentity::encode))
            }
            boundaries.configure {
                violations.set(dependencyBoundaryViolations(root))
            }
            configurePublicationShape(root, publicationShape)
            configureSupplyChainFiles(root, releaseFiles, publishedContents)
        }

        root.tasks.register("checkJvmWorkspace") {
            group = LifecycleBasePlugin.VERIFICATION_GROUP
            description = "Runs the reproducible JVM workspace policy gates."
            dependsOn(topology, boundaries, environment, versionLiterals)
            dependsOn(root.subprojects.mapNotNull { it.tasks.findByName("checkClassfileVersion") })
            dependsOn(root.project(":repost-client").tasks.named("checkPublicApiBoundary"))
        }

        root.tasks.register<CheckJvmReleaseArtifactReadiness>("checkJvmReleaseArtifactReadiness") {
            group = LifecycleBasePlugin.VERIFICATION_GROUP
            description = "Fails until every JVM artifact has real implementation classes and release evidence."
            unimplementedModules.set(JvmModuleCatalog.unimplementedArtifactModules.sorted())
        }
    }

    private fun configureModule(project: Project, spec: JvmModuleSpec) {
        project.configurations.configureEach {
            if (!isBuildToolConfiguration(name)) {
                resolutionStrategy.failOnVersionConflict()
            }
            resolutionStrategy.activateDependencyLocking()
        }

        when (spec.kind) {
            JvmModuleKind.PLATFORM -> project.pluginManager.apply(JavaPlatformPlugin::class.java)
            JvmModuleKind.NATIVE_DISTRIBUTION -> project.pluginManager.apply("base")
            JvmModuleKind.GRADLE_PLUGIN -> project.pluginManager.apply(JavaGradlePluginPlugin::class.java)
            JvmModuleKind.JAVA_FIXTURE, JvmModuleKind.KOTLIN_FIXTURE -> project.pluginManager.apply(JavaPlugin::class.java)
            else -> project.pluginManager.apply(JavaLibraryPlugin::class.java)
        }

        if (spec.kind == JvmModuleKind.PLATFORM) {
            JvmModuleCatalog.publicLibraryModules.sorted().forEach { module ->
                project.dependencies.constraints.add(
                    "api",
                    project.dependencies.project(mapOf("path" to ":$module")),
                )
            }
        }

        if (spec.javaRelease != null) {
            configureJavaModule(project, spec)
        }
        configureArtifactIdentity(project, spec)
        configurePublishing(project, spec)
        configurePublicationMetadata(project)
        if (spec.kind == JvmModuleKind.KOTLIN_LIBRARY) {
            configureKotlinDocumentation(project)
        }
    }

    private fun configureArtifactIdentity(project: Project, spec: JvmModuleSpec) {
        project.extensions.configure<BasePluginExtension> {
            archivesName.set(spec.publishedCoordinate?.substringAfter(':') ?: spec.name)
        }
        spec.gradlePluginId?.let { pluginId ->
            project.extensions.configure<GradlePluginDevelopmentExtension> {
                plugins.create("repostSdk") {
                    id = pluginId
                    implementationClass = "sh.repost.gradle.RepostSdkPlugin"
                    displayName = "Repost SDK generation"
                    description = "Generates Repost Java and Kotlin SDK sources from a Repost schema."
                }
            }
        }
    }

    private fun configurePublishing(project: Project, spec: JvmModuleSpec) {
        val coordinate = spec.publishedCoordinate ?: return
        project.pluginManager.apply(MavenPublishPlugin::class.java)
        if (spec.kind == JvmModuleKind.GRADLE_PLUGIN) return

        project.extensions.configure<PublishingExtension> {
            publications.create("maven", MavenPublication::class.java) {
                artifactId = coordinate.substringAfter(':')
                when (spec.kind) {
                    JvmModuleKind.PLATFORM -> from(project.components.getByName("javaPlatform"))
                    JvmModuleKind.NATIVE_DISTRIBUTION -> Unit
                    else -> from(project.components.getByName("java"))
                }
            }
        }
    }

    private fun configurePublicationMetadata(project: Project) {
        project.pluginManager.withPlugin("maven-publish") {
            val inputs = JvmPomMetadataInputs(project)
            val validate = project.tasks.register<ValidateJvmPublicationMetadata>("validatePublicationMetadata") {
                group = LifecycleBasePlugin.VERIFICATION_GROUP
                description = "Fails publication while Task 0-owned POM metadata remains absent."
                missingProperties.set(inputs.missingProperties)
            }

            project.tasks.matching { task -> JvmPublicationPolicy.requiresApprovedMetadata(task.name) }
                .configureEach { dependsOn(validate) }
        }
    }

    private fun applyPomMetadata(publication: MavenPublication, inputs: JvmPomMetadataInputs) {
        publication.pom {
            name.set(inputs[JvmPomMetadataProperty.NAME])
            description.set(inputs[JvmPomMetadataProperty.DESCRIPTION])
            url.set(inputs[JvmPomMetadataProperty.URL])
            licenses {
                license {
                    name.set(inputs[JvmPomMetadataProperty.LICENSE_NAME])
                    url.set(inputs[JvmPomMetadataProperty.LICENSE_URL])
                }
            }
            developers {
                developer {
                    id.set(inputs[JvmPomMetadataProperty.DEVELOPER_ID])
                    name.set(inputs[JvmPomMetadataProperty.DEVELOPER_NAME])
                }
            }
            scm {
                url.set(inputs[JvmPomMetadataProperty.SCM_URL])
                connection.set(inputs[JvmPomMetadataProperty.SCM_CONNECTION])
                developerConnection.set(inputs[JvmPomMetadataProperty.SCM_DEVELOPER_CONNECTION])
            }
            issueManagement {
                system.set(inputs[JvmPomMetadataProperty.ISSUE_MANAGEMENT_SYSTEM])
                url.set(inputs[JvmPomMetadataProperty.ISSUE_MANAGEMENT_URL])
            }
        }
    }

    private fun configureKotlinDocumentation(project: Project) {
        project.pluginManager.withPlugin("org.jetbrains.dokka") {
            val dokka = project.tasks.named("dokkaGeneratePublicationHtml")
            val dokkaHtml = project.layout.buildDirectory.dir("dokka/html")
            project.tasks.named<Javadoc>("javadoc") {
                setSource(project.files())
            }
            project.tasks.named<Jar>("javadocJar") {
                dependsOn(dokka)
                from(dokkaHtml)
                extensions.extraProperties.set(DOCUMENTATION_SOURCE_KIND, "dokka-html")
            }
        }
    }

    private fun configureJavaModule(project: Project, spec: JvmModuleSpec) {
        project.extensions.configure<JavaPluginExtension> {
            toolchain.languageVersion.set(JavaLanguageVersion.of(21))
            if (spec.publishedCoordinate != null && spec.kind != JvmModuleKind.INTERNAL_ENGINE) {
                withSourcesJar()
                withJavadocJar()
            }
        }

        project.tasks.withType<JavaCompile>().configureEach {
            options.encoding = "UTF-8"
            options.release.set(spec.javaRelease)
        }
        project.tasks.withType<Javadoc>().configureEach {
            isFailOnError = true
            (options as StandardJavadocDocletOptions).apply {
                encoding = "UTF-8"
                charSet = "UTF-8"
                addBooleanOption("Xdoclint:all", true)
                addBooleanOption("Werror", true)
            }
        }
        project.tasks.withType<AbstractArchiveTask>().configureEach {
            isPreserveFileTimestamps = false
            isReproducibleFileOrder = true
            duplicatesStrategy = DuplicatesStrategy.FAIL
        }
        project.tasks.withType<Jar>().configureEach {
            manifest.attributes(
                mapOf(
                "Implementation-Title" to project.name,
                "Implementation-Version" to project.version.toString(),
                ),
            )
            spec.automaticModuleName?.let {
                manifest.attributes(mapOf("Automatic-Module-Name" to it))
            }
        }

        val jar = project.tasks.named<Jar>("jar")
        val classfileCheck = project.tasks.register<CheckClassfileVersion>("checkClassfileVersion") {
            group = LifecycleBasePlugin.VERIFICATION_GROUP
            archiveFile.set(jar.flatMap { it.archiveFile })
            expectedJavaRelease.set(spec.javaRelease)
            implementedArtifact.set(spec.implementedArtifact)
            expectedClassEntries.set(expectedClassEntries(spec))
            expectedAutomaticModuleName.set(spec.automaticModuleName.orEmpty())
            if (project.name == "repost-client") {
                forbiddenEntryPrefixes.set(
                    listOf("org/apache/hc/", "org/slf4j/", "META-INF/services/", "sh/repost/internal/http/"),
                )
                forbiddenText.set(
                    listOf(
                        "org.apache.hc",
                        "org/apache/hc",
                        "org.slf4j",
                        "org/slf4j",
                        "sh.repost.internal.http",
                        "sh/repost/internal/http",
                    ),
                )
                allowedTextPrefixes.set(
                    listOf(
                        "sh.repost.internal.apache.",
                        "sh/repost/internal/apache/",
                    ),
                )
            } else {
                forbiddenEntryPrefixes.set(emptyList())
                forbiddenText.set(emptyList())
                allowedTextPrefixes.set(emptyList())
            }
            dependsOn(jar)
        }
        project.tasks.named("check").configure { dependsOn(classfileCheck) }

        if (
            spec.publishedCoordinate != null &&
            spec.kind != JvmModuleKind.KOTLIN_LIBRARY &&
            spec.kind != JvmModuleKind.INTERNAL_ENGINE
        ) {
            val baseline = project.layout.projectDirectory.file("api/${project.name}.api")
            val excludedPackages = listOf(
                "sh.repost.internal.",
                "sh.repost.client.internal.",
                "sh.repost.buildplugin.internal.",
            )
            val dumpApi = project.tasks.register<DumpJavaApiBaseline>("apiDump") {
                archiveFile.set(jar.flatMap { it.archiveFile })
                baselineFile.set(baseline)
                excludedPackagePrefixes.set(excludedPackages)
                dependsOn(jar)
            }
            val checkApi = project.tasks.register<CheckJavaApiBaseline>("apiCheck") {
                archiveFile.set(jar.flatMap { it.archiveFile })
                baselineFile.set(baseline)
                excludedPackagePrefixes.set(excludedPackages)
                dependsOn(jar)
                mustRunAfter(dumpApi)
            }
            project.tasks.named("check").configure { dependsOn(checkApi) }
        }

        if (project.name == "repost-client") {
            val apiCheck = project.tasks.register<CheckPublicApiBoundary>("checkPublicApiBoundary") {
                group = LifecycleBasePlugin.VERIFICATION_GROUP
                archiveFile.set(jar.flatMap { it.archiveFile })
                forbiddenPackages.set(
                    listOf(
                        "org.apache.hc",
                        "org.slf4j",
                        "com.fasterxml.jackson",
                        "kotlin.",
                        "io.micrometer",
                        "io.opentelemetry.sdk",
                        "org.springframework",
                        "jakarta.",
                        "io.quarkus",
                        "io.micronaut",
                    ),
                )
                allowedPackagePrefixes.set(listOf("sh.repost.internal.apache."))
                dependsOn(jar)
            }
            project.tasks.named("check").configure { dependsOn(apiCheck) }
        }
    }

    private fun configurePublicationShape(
        root: Project,
        shape: org.gradle.api.tasks.TaskProvider<CheckJvmPublicationShape>,
    ) {
        val actualPublications = mutableListOf<String>()
        val actualArtifacts = mutableListOf<JvmPublicationArtifact>()
        val pomPaths = mutableMapOf<String, String>()
        val pomTasks = mutableListOf<org.gradle.api.tasks.TaskProvider<GenerateMavenPom>>()

        root.subprojects.forEach { project ->
            val publishing = project.extensions.findByType(PublishingExtension::class.java) ?: return@forEach
            val metadataInputs = JvmPomMetadataInputs(project)
            publishing.publications.withType(MavenPublication::class.java).forEach { publication ->
                // Gradle's plugin marker publishing conventions may assign their own POM values
                // after container callbacks. Re-apply the Task 0-owned providers after evaluation.
                applyPomMetadata(publication, metadataInputs)
                val publicationId = "${project.path}:${publication.name}"
                actualPublications += publicationId
                val taskName = "generatePomFileFor" +
                    publication.name.replaceFirstChar { it.uppercaseChar() } +
                    "Publication"
                val pomTask = project.tasks.named(taskName, GenerateMavenPom::class.java)
                pomTasks += pomTask
                pomPaths[publicationId] = project.layout.buildDirectory
                    .file("publications/${publication.name}/pom-default.xml")
                    .get().asFile.relativeTo(root.layout.projectDirectory.asFile).invariantSeparatorsPath

                publication.artifacts.forEach { artifact ->
                    val sourceKind = if (
                        project.path == ":repost-client-kotlin" &&
                        artifact.classifier == "javadoc" &&
                        project.tasks.named<Jar>("javadocJar").get().extensions.extraProperties
                            .has(DOCUMENTATION_SOURCE_KIND)
                    ) {
                        project.tasks.named<Jar>("javadocJar").get().extensions.extraProperties
                            .get(DOCUMENTATION_SOURCE_KIND).toString()
                    } else {
                        artifact.classifier ?: "main"
                    }
                    actualArtifacts += JvmPublicationArtifact(
                        project.path,
                        publication.name,
                        artifact.classifier,
                        artifact.extension,
                        sourceKind,
                    )
                }
            }
        }

        val expectedPublications = JvmModuleCatalog.modules.values
            .filter { it.publishedCoordinate != null }
            .flatMap { spec ->
                if (spec.kind == JvmModuleKind.GRADLE_PLUGIN) {
                    listOf(
                        ":${spec.name}:pluginMaven",
                        ":${spec.name}:repostSdkPluginMarkerMaven",
                    )
                } else {
                    listOf(":${spec.name}:maven")
                }
            }
            .sorted()
        val expectedArtifacts = JvmModuleCatalog.modules.values
            .filter { spec -> spec.publishedCoordinate != null }
            .flatMap { spec ->
                when {
                    spec.javaRelease != null -> {
                        val publication = if (spec.kind == JvmModuleKind.GRADLE_PLUGIN) "pluginMaven" else "maven"
                        listOf(
                            JvmPublicationArtifact(":${spec.name}", publication, null, "jar", "any"),
                            JvmPublicationArtifact(":${spec.name}", publication, "sources", "jar", "any"),
                            JvmPublicationArtifact(
                                ":${spec.name}",
                                publication,
                                "javadoc",
                                "jar",
                                if (spec.kind == JvmModuleKind.KOTLIN_LIBRARY) "dokka-html" else "any",
                            ),
                        )
                    }
                    spec.kind == JvmModuleKind.NATIVE_DISTRIBUTION ->
                        NativeEngineReleasePolicy.targets.map { target ->
                            JvmPublicationArtifact(":${spec.name}", "maven", target.classifier, "zip", target.classifier)
                        } + listOf(
                            JvmPublicationArtifact(":${spec.name}", "maven", "checksums", "json", "checksums"),
                            JvmPublicationArtifact(":${spec.name}", "maven", "checksums", "sig", "checksums"),
                        )
                    else -> emptyList()
                }
            }
            .sortedBy(JvmPublicationArtifact::identity)

        shape.configure {
            dependsOn(pomTasks)
            this.pomPaths.set(pomPaths)
            this.expectedPublications.set(expectedPublications)
            this.actualPublications.set(actualPublications.sorted())
            this.expectedArtifacts.set(expectedArtifacts.map(JvmPublicationArtifact::encode))
            this.actualArtifacts.set(actualArtifacts.sortedBy(JvmPublicationArtifact::identity).map(JvmPublicationArtifact::encode))
            val kotlinProject = root.project(":repost-client-kotlin")
            val kotlinDocs = kotlinProject.tasks.named<Jar>("javadocJar")
            dependsOn(kotlinDocs)
            kotlinJavadocJar.set(kotlinDocs.flatMap { it.archiveFile })
            kotlinSources.from(kotlinProject.fileTree("src/main") { include("**/*.kt", "**/*.java") })
        }
    }

    private fun configureSupplyChainFiles(
        root: Project,
        releaseFiles: ConfigurableFileCollection,
        publishedContents: TaskProvider<VerifyJvmPublishedContents>,
    ) {
        val archiveTasks = JvmModuleCatalog.modules.values
            .filter { it.publishedCoordinate != null && it.javaRelease != null }
            .flatMap { spec ->
                val project = root.project(":${spec.name}")
                listOf("jar", "sourcesJar", "javadocJar").map { project.tasks.named(it, Jar::class.java) }
            }
        val pomTasks = root.subprojects.flatMap { project ->
            project.tasks.withType(GenerateMavenPom::class.java).toList()
        }
        releaseFiles.from(archiveTasks.map { it.flatMap(Jar::getArchiveFile) })
        releaseFiles.from(pomTasks.map(GenerateMavenPom::getDestination))
        releaseFiles.from(
            root.layout.buildDirectory.file("reports/cyclonedx/repost-jvm-bom.json"),
            root.layout.buildDirectory.file("reports/cyclonedx/repost-jvm-bom.xml"),
        )
        publishedContents.configure {
            dependsOn(archiveTasks, pomTasks, ":checkJvmPublicationShape", ":cyclonedxBom")
        }
    }

    private fun actualArtifactIdentity(project: Project): JvmArtifactIdentity {
        val spec = JvmModuleCatalog.modules.getValue(project.name)
        val coordinate = project.extensions.findByType(PublishingExtension::class.java)
            ?.publications
            ?.withType(MavenPublication::class.java)
            ?.firstOrNull { it.name == "maven" || it.name == "pluginMaven" }
            ?.let { "${it.groupId}:${it.artifactId}" }
        val archiveBaseName = spec.javaRelease?.let {
            project.extensions.getByType(BasePluginExtension::class.java).archivesName.get()
        }
        val automaticModuleName = spec.javaRelease?.let {
            project.tasks.named<Jar>("jar").get().manifest.attributes["Automatic-Module-Name"]?.toString()
        }
        val pluginIds = project.extensions.findByType(GradlePluginDevelopmentExtension::class.java)
            ?.plugins
            ?.map { it.id }
            ?.toSet()
            .orEmpty()
        return JvmArtifactIdentity(
            projectName = project.name,
            coordinate = coordinate,
            archiveBaseName = archiveBaseName,
            automaticModuleName = automaticModuleName,
            gradlePluginIds = pluginIds,
        )
    }

    private fun expectedClassEntries(spec: JvmModuleSpec): List<String> = when (spec.name) {
        "repost-client" -> listOf(
            "sh/repost/client/ClientOptions.class",
            "sh/repost/client/Transport.class",
            "sh/repost/client/error/RepostException.class",
        )
        "repost-client-spring-boot-starter" -> listOf(
            "sh/repost/client/spring/RepostClientAutoConfiguration.class",
            "sh/repost/client/spring/RepostClientProperties.class",
            "sh/repost/client/spring/RepostConfigurationKeys.class",
        )
        "repost-client-cdi" -> listOf(
            "sh/repost/client/cdi/RepostCdiExtension.class",
            "sh/repost/client/cdi/RepostRuntimeCreator.class",
            "sh/repost/client/cdi/RepostRuntimeDisposer.class",
        )
        "repost-maven-plugin" -> listOf(
            "sh/repost/maven/RepostCheckMojo.class",
            "sh/repost/maven/RepostGenerateMojo.class",
            "sh/repost/maven/RepostHelpMojo.class",
        )
        "repost-gradle-plugin" -> listOf("sh/repost/gradle/RepostSdkPlugin.class")
        else -> emptyList()
    }

    private fun declaredDependencies(root: Project): List<JvmDependencyDeclaration> =
        root.subprojects.flatMap { project ->
            project.configurations.filterNot { isBuildToolConfiguration(it.name) }.flatMap { configuration ->
                val external = configuration.dependencies.withType<ExternalModuleDependency>().map { dependency ->
                    JvmDependencyDeclaration(
                        project.name,
                        configuration.name,
                        "${dependency.group}:${dependency.name}${dependency.version?.let { ":$it" }.orEmpty()}",
                    )
                }
                val projects = configuration.dependencies.withType<ProjectDependency>().map { dependency ->
                    val target = root.project(dependency.path)
                    JvmDependencyDeclaration(
                        project.name,
                        configuration.name,
                        "${target.group}:${target.name}:${target.version}",
                    )
                }
                external + projects
            }
        }.distinct()

    private fun dependencyBoundaryViolations(root: Project): List<String> {
        val edges = root.subprojects.flatMap { project ->
            project.configurations.flatMap { configuration ->
                configuration.dependencies.withType<ProjectDependency>().map { dependency ->
                    ProjectDependencyEdge(project.name, dependency.path.substringAfterLast(':'), configuration.name)
                }
            }
        }.distinct()
        val messages = DependencyBoundaryPolicy.violations(edges).toMutableList()
        val declarations = declaredDependencies(root)
        messages += JvmDependencyGraphPolicy.violations(declarations, emptyList())
        messages += JvmDependencyGraphPolicy.kotlinCoreViolations(root.version.toString(), declarations)
        val bomConstraints = root.project(":repost-bom")
            .configurations.getByName("api")
            .dependencyConstraints
            .mapTo(mutableSetOf()) { "${it.group}:${it.name}" }
        messages += JvmDependencyGraphPolicy.bomConstraintViolations(
            expected = JvmModuleCatalog.publicLibraryModules.mapTo(mutableSetOf()) { "${JvmModuleCatalog.GROUP}:$it" },
            actual = bomConstraints,
        )
        return messages.sorted()
    }

    private fun isBuildToolConfiguration(name: String): Boolean =
        name.startsWith("dokka") || name == "bcv-rt-jvm-cp" || name in setOf(
        "apiDependenciesMetadata",
        "compileOnlyDependenciesMetadata",
        "implementationDependenciesMetadata",
        "intransitiveDependenciesMetadata",
        "kotlinBuildToolsApiClasspath",
        "kotlinCompilerClasspath",
        "kotlinCompilerPluginClasspath",
        "kotlinCompilerPluginClasspathMain",
        "kotlinCompilerPluginClasspathTest",
        "kotlinKlibCommonizerClasspath",
        "kotlinNativeCompilerPluginClasspath",
        "kotlinScriptDefExtensions",
        "testApiDependenciesMetadata",
        "testCompileOnlyDependenciesMetadata",
        "testImplementationDependenciesMetadata",
        "testIntransitiveDependenciesMetadata",
        "testKotlinScriptDefExtensions",
        )

    private class JvmPomMetadataInputs(project: Project) {
        private val values = JvmPomMetadataProperty.values().associateWith { property ->
            project.providers.gradleProperty(property.gradleProperty)
        }

        operator fun get(property: JvmPomMetadataProperty): Provider<String> = values.getValue(property)

        val missingProperties: Provider<List<String>> = project.providers.provider {
            JvmPublicationPolicy.missingMetadataProperties(
                values.mapValues { (_, provider) -> provider.orNull },
            )
        }
    }

    private companion object {
        const val DOCUMENTATION_SOURCE_KIND = "repostDocumentationSourceKind"
    }
}
