package sh.repost.gradle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.jar.JarFile;
import java.util.stream.Collectors;
import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.gradle.testkit.runner.TaskOutcome;
import org.junit.jupiter.api.Test;

final class GradlePluginDistributionTest {
    private static final String PLUGIN_ID = "sh.repost.sdk";
    private static final String VERSION = "1.0.0";

    @Test
    void publishesCompleteDistributionAndResolvesMarkerFromIsolatedRepository() throws Exception {
        String repositoryProperty = System.getProperty("repost.testPluginRepository");
        assertNotNull(repositoryProperty, "isolated test-plugin repository was not configured");
        assertEquals(VERSION, System.getProperty("repost.testPluginVersion"));

        Path repository = Path.of(repositoryProperty);
        Path implementation = repository.resolve("sh/repost/repost-gradle-plugin/" + VERSION);
        Path marker = repository.resolve("sh/repost/sdk/sh.repost.sdk.gradle.plugin/" + VERSION);
        Path pluginJar = implementation.resolve("repost-gradle-plugin-" + VERSION + ".jar");
        Path sourcesJar = implementation.resolve("repost-gradle-plugin-" + VERSION + "-sources.jar");
        Path javadocJar = implementation.resolve("repost-gradle-plugin-" + VERSION + "-javadoc.jar");
        Path pluginPom = implementation.resolve("repost-gradle-plugin-" + VERSION + ".pom");
        Path markerPom = marker.resolve("sh.repost.sdk.gradle.plugin-" + VERSION + ".pom");

        for (Path artifact : new Path[] {pluginJar, sourcesJar, javadocJar, pluginPom, markerPom}) {
            assertTrue(Files.isRegularFile(artifact), "missing isolated publication artifact " + artifact);
        }
        assertJarEntry(
            pluginJar,
            "META-INF/gradle-plugins/sh.repost.sdk.properties",
            "implementation-class=sh.repost.gradle.RepostSdkPlugin"
        );
        assertJarEntry(sourcesJar, "sh/repost/gradle/RepostSdkPlugin.java", "class RepostSdkPlugin");
        assertJarEntry(javadocJar, "sh/repost/gradle/RepostSdkPlugin.html", "RepostSdkPlugin");

        String implementationPom = Files.readString(pluginPom, StandardCharsets.UTF_8);
        assertPomCoordinate(implementationPom, "sh.repost", "repost-gradle-plugin", VERSION);
        assertTrue(implementationPom.contains("<name>Repost JVM SDK</name>"), implementationPom);
        assertTrue(implementationPom.contains("<url>https://repost.sh</url>"), implementationPom);

        String markerDocument = Files.readString(markerPom, StandardCharsets.UTF_8);
        assertPomCoordinate(markerDocument, PLUGIN_ID, PLUGIN_ID + ".gradle.plugin", VERSION);
        assertTrue(markerDocument.contains("<groupId>sh.repost</groupId>"), markerDocument);
        assertTrue(markerDocument.contains("<artifactId>repost-gradle-plugin</artifactId>"), markerDocument);
        assertTrue(PLUGIN_ID.startsWith("sh.repost."), "plugin ID does not belong to the sh.repost namespace");

        Path consumer = Files.createTempDirectory("repost-test-plugin-consumer-");
        try {
            Files.writeString(
                consumer.resolve("settings.gradle"),
                "pluginManagement { repositories { maven { url = uri('" + groovy(repository) + "') } } }\n" +
                    "rootProject.name = 'isolated-plugin-consumer'\n",
                StandardCharsets.UTF_8
            );
            Files.writeString(
                consumer.resolve("build.gradle"),
                "plugins { id 'sh.repost.sdk' version '1.0.0' }\n" +
                    "tasks.register('assertPluginDistribution') { doLast {\n" +
                    "  assert plugins.hasPlugin('sh.repost.sdk')\n" +
                    "  assert tasks.names.containsAll(['repostGenerate', 'repostGenerateCheck'])\n" +
                    "} }\n",
                StandardCharsets.UTF_8
            );
            BuildResult result = GradleRunner.create()
                .withProjectDir(consumer.toFile())
                .withArguments("--offline", "--stacktrace", "assertPluginDistribution")
                .build();
            assertEquals(TaskOutcome.SUCCESS, result.task(":assertPluginDistribution").getOutcome());
        } finally {
            deleteTree(consumer);
        }
    }

    private static void assertPomCoordinate(String pom, String group, String artifact, String version) {
        assertTrue(pom.contains("<groupId>" + group + "</groupId>"), pom);
        assertTrue(pom.contains("<artifactId>" + artifact + "</artifactId>"), pom);
        assertTrue(pom.contains("<version>" + version + "</version>"), pom);
    }

    private static void assertJarEntry(Path jar, String name, String expectedText) throws IOException {
        try (JarFile archive = new JarFile(jar.toFile())) {
            var entry = archive.getJarEntry(name);
            assertNotNull(entry, "missing " + name + " in " + jar);
            String contents = new String(archive.getInputStream(entry).readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(contents.contains(expectedText), name + " did not contain " + expectedText);
        }
    }

    private static String groovy(Path path) {
        return path.toAbsolutePath().toString().replace("\\", "\\\\").replace("'", "\\'");
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) return;
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).collect(Collectors.toList())) {
                Files.deleteIfExists(path);
            }
        }
    }
}
