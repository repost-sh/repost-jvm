package sh.repost.gradle;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Validates and marks dedicated Gradle-owned generated-output roots. */
final class GradleOutputOwnership {
    private static final String MARKER = ".repost-output-root.json";

    private GradleOutputOwnership() {
    }

    static List<String> validate(GradleOwnedOutputs task) {
        List<String> state = new ArrayList<>();
        for (OwnedRoot root : roots(task)) {
            Path path = root.path.toAbsolutePath().normalize();
            Path projectSource = task.getProjectDirectory().get().getAsFile().toPath()
                .toAbsolutePath().normalize().resolve("src");
            if (path.startsWith(projectSource)) {
                throw new IllegalArgumentException(
                    "Repost primary generated output must not use a shared src directory; " +
                        "generate with the CLI and configure checkAgainst instead"
                );
            }
            Path marker = path.resolve(MARKER);
            if (!hasEntries(path)) {
                state.add(root.marker(task));
                continue;
            }
            if (!Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalArgumentException(
                    "Repost generated output root is non-empty and has no ownership marker: " + path
                );
            }
            try {
                if (!Files.readString(marker, StandardCharsets.UTF_8).equals(root.marker(task))) {
                    throw new IllegalArgumentException(
                        "Repost generated output ownership marker does not match this Gradle project: " + path
                    );
                }
                state.add(root.marker(task));
            } catch (IOException exception) {
                throw new IllegalArgumentException("Repost generated output ownership marker could not be read: " + path);
            }
        }
        return state;
    }

    static void mark(GradleOwnedOutputs task) {
        for (OwnedRoot root : roots(task)) {
            Path path = root.path.toAbsolutePath().normalize();
            try {
                Files.createDirectories(path);
                Files.writeString(
                    path.resolve(MARKER),
                    root.marker(task),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE
                );
            } catch (IOException exception) {
                throw new IllegalArgumentException("Repost generated output ownership marker could not be written: " + path);
            }
        }
    }

    private static List<OwnedRoot> roots(GradleOwnedOutputs task) {
        List<OwnedRoot> roots = new ArrayList<>();
        if (task.getSchemaMode().get() == RepostSchemaMode.GENERATE) {
            Map<String, String> sources = task.getSourceOutputPathsByGenerator().get();
            Map<String, String> resources = task.getResourceOutputPathsByGenerator().get();
            for (String generator : task.getGeneratorNames().get()) {
                roots.add(new OwnedRoot(Path.of(sources.get(generator)), "source", generator));
                roots.add(new OwnedRoot(Path.of(resources.get(generator)), "resource", generator));
            }
        }
        roots.add(new OwnedRoot(
            task.getAggregateSourceOutputDirectory().get().getAsFile().toPath(),
            "aggregate-source",
            "repostRegistry"
        ));
        roots.add(new OwnedRoot(
            task.getAggregateResourceOutputDirectory().get().getAsFile().toPath(),
            "aggregate-resource",
            "repostRegistry"
        ));
        return roots;
    }

    private static boolean hasEntries(Path root) {
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            return Files.exists(root, LinkOption.NOFOLLOW_LINKS);
        }
        try (java.util.stream.Stream<Path> entries = Files.list(root)) {
            return entries.findAny().isPresent();
        } catch (IOException exception) {
            throw new IllegalArgumentException("Repost generated output root could not be inspected: " + root);
        }
    }

    private static String json(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static final class OwnedRoot {
        private final Path path;
        private final String outputKind;
        private final String generator;

        private OwnedRoot(Path path, String outputKind, String generator) {
            this.path = path;
            this.outputKind = outputKind;
            this.generator = generator;
        }

        private String marker(GradleOwnedOutputs task) {
            return "{\n" +
                "  \"formatVersion\": 1,\n" +
                "  \"buildIdentity\": {\n" +
                "    \"kind\": \"GRADLE\",\n" +
                "    \"group\": \"" + json(task.getBuildGroup().get()) + "\",\n" +
                "    \"rootProjectName\": \"" + json(task.getBuildRootProjectName().get()) + "\",\n" +
                "    \"projectPath\": \"" + json(task.getBuildProjectPath().get()) + "\"\n" +
                "  },\n" +
                "  \"outputKind\": \"" + outputKind + "\",\n" +
                "  \"generator\": \"" + generator + "\"\n" +
                "}\n";
        }
    }
}

interface GradleOwnedOutputs {
    org.gradle.api.provider.Property<RepostSchemaMode> getSchemaMode();

    org.gradle.api.provider.ListProperty<String> getGeneratorNames();

    org.gradle.api.provider.MapProperty<String, String> getSourceOutputPathsByGenerator();

    org.gradle.api.provider.MapProperty<String, String> getResourceOutputPathsByGenerator();

    org.gradle.api.file.DirectoryProperty getProjectDirectory();

    org.gradle.api.file.DirectoryProperty getAggregateSourceOutputDirectory();

    org.gradle.api.file.DirectoryProperty getAggregateResourceOutputDirectory();

    org.gradle.api.provider.Property<String> getBuildGroup();

    org.gradle.api.provider.Property<String> getBuildRootProjectName();

    org.gradle.api.provider.Property<String> getBuildProjectPath();
}
