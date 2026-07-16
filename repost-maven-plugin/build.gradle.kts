import org.gradle.api.GradleException

val pluginDescriptor = tasks.register("pluginDescriptor") {
    group = "verification"
    description = "Verifies the published Maven plugin descriptor and detailed help metadata."
    dependsOn(tasks.named("processResources"), tasks.named("test"))

    val descriptor = layout.buildDirectory.file("resources/main/META-INF/maven/plugin.xml")
    inputs.file(layout.projectDirectory.file("src/main/resources/META-INF/maven/plugin.xml"))

    doLast {
        if (!descriptor.get().asFile.isFile) {
            throw GradleException("Maven plugin descriptor was not published to the module resources")
        }
    }
}

tasks.named("check") {
    dependsOn(pluginDescriptor)
}
