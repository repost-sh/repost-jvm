package sh.repost.maven;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import sh.repost.buildplugin.internal.BuildPluginEngine;

/** Validates and marks Maven-owned parent output roots. */
final class MavenOutputOwnership {
    private static final String MARKER = ".repost-output-root.json";
    private static final Pattern RELATIVE_CHILD = Pattern.compile(
        "\\\"relativeChild\\\": \\\"([^\\\"]+)\\\""
    );

    private MavenOutputOwnership() {
    }

    static void validateRoots(
        Path buildDirectory,
        List<BuildPluginEngine.Generator> generators,
        String groupId,
        String artifactId
    ) {
        Path sourceParent = buildDirectory.resolve("generated-sources/repost");
        Path resourceParent = buildDirectory.resolve("generated-resources/repost");
        List<BuildPluginEngine.Generator> defaultSources = defaults(generators, sourceParent, true);
        List<BuildPluginEngine.Generator> defaultResources = defaults(generators, resourceParent, false);
        validate(
            sourceParent,
            marker("source", defaultSources, groupId, artifactId, false),
            managedChildren(defaultSources, true)
        );
        validate(
            resourceParent,
            marker("resource", defaultResources, groupId, artifactId, false),
            managedChildren(defaultResources, false)
        );
        validateOverrides(generators, sourceParent, true, groupId, artifactId);
        validateOverrides(generators, resourceParent, false, groupId, artifactId);
    }

    static void markRoots(
        Path buildDirectory,
        List<BuildPluginEngine.Generator> generators,
        String groupId,
        String artifactId
    ) {
        Path sourceParent = buildDirectory.resolve("generated-sources/repost");
        Path resourceParent = buildDirectory.resolve("generated-resources/repost");
        List<BuildPluginEngine.Generator> defaultSources = defaults(generators, sourceParent, true);
        List<BuildPluginEngine.Generator> defaultResources = defaults(generators, resourceParent, false);
        if (!defaultSources.isEmpty() || Files.exists(sourceParent, LinkOption.NOFOLLOW_LINKS)) {
            mark(sourceParent, marker("source", defaultSources, groupId, artifactId, false));
        }
        if (!defaultResources.isEmpty() || Files.exists(resourceParent, LinkOption.NOFOLLOW_LINKS)) {
            mark(resourceParent, marker("resource", defaultResources, groupId, artifactId, false));
        }
        markOverrides(generators, sourceParent, true, groupId, artifactId);
        markOverrides(generators, resourceParent, false, groupId, artifactId);
    }

    static void pruneStaleDefaultRoots(
        Path buildDirectory,
        List<BuildPluginEngine.Generator> generators
    ) {
        prune(
            buildDirectory.resolve("generated-sources/repost"),
            managedChildren(defaults(
                generators, buildDirectory.resolve("generated-sources/repost"), true
            ), true)
        );
        prune(
            buildDirectory.resolve("generated-resources/repost"),
            managedChildren(defaults(
                generators, buildDirectory.resolve("generated-resources/repost"), false
            ), false)
        );
    }

    static void validateAggregateRoots(Path source, Path resource, String groupId, String artifactId) {
        validateOverride(source, aggregateMarker("source", groupId, artifactId));
        validateOverride(resource, aggregateMarker("resource", groupId, artifactId));
    }

    static void markAggregateRoots(Path source, Path resource, String groupId, String artifactId) {
        mark(source, aggregateMarker("source", groupId, artifactId));
        mark(resource, aggregateMarker("resource", groupId, artifactId));
    }

    private static void validate(Path root, String expectedMarker, Set<String> managedChildren) {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("Repost generated output parent is not a directory: " + root);
        }
        Path marker = root.resolve(MARKER);
        boolean empty = true;
        String actualMarker = null;
        try {
            if (Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS)) {
                actualMarker = Files.readString(marker, StandardCharsets.UTF_8);
            }
        } catch (IOException exception) {
            throw new IllegalArgumentException("Repost generated output parent marker could not be read");
        }
        Set<String> allowedChildren = new HashSet<>(managedChildren);
        if (actualMarker != null && sameOwnership(actualMarker, expectedMarker)) {
            allowedChildren.addAll(markerChildren(actualMarker));
        }
        try (DirectoryStream<Path> children = Files.newDirectoryStream(root)) {
            for (Path child : children) {
                empty = false;
                String name = child.getFileName().toString();
                if (!MARKER.equals(name) && !allowedChildren.contains(name)) {
                    throw new IllegalArgumentException(
                        "Repost generated output parent contains unmanaged child " + child
                    );
                }
                if (Files.isSymbolicLink(child)) {
                    throw new IllegalArgumentException("Repost generated output parent contains a symbolic link");
                }
            }
        } catch (IOException exception) {
            throw new IllegalArgumentException("Repost generated output parent could not be inspected");
        }
        if (empty) {
            return;
        }
        if (actualMarker == null || !sameOwnership(actualMarker, expectedMarker)) {
            throw new IllegalArgumentException(
                "Repost generated output parent is not owned by this Maven execution: " + root
            );
        }
    }

    private static boolean sameOwnership(String actual, String expected) {
        int actualManaged = actual.indexOf("  \"managed\": [\n");
        int expectedManaged = expected.indexOf("  \"managed\": [\n");
        if (actualManaged < 0 || expectedManaged < 0) return false;
        String prefix = expected.substring(0, expectedManaged);
        if (!actual.substring(0, actualManaged).equals(prefix)) return false;
        boolean source = prefix.contains("\"outputKind\": \"source\"");
        String javaEntry = "    {\"generatorId\": \"cfc20961be395d84\", "
            + "\"language\": \"java\", \"relativeChild\": \"cfc20961be395d84"
            + (source ? "/java" : "") + "\"}";
        String kotlinEntry = "    {\"generatorId\": \"c756cf657644d412\", "
            + "\"language\": \"kotlin\", \"relativeChild\": \"c756cf657644d412"
            + (source ? "/kotlin" : "") + "\"}";
        String managedPrefix = "  \"managed\": [\n";
        String suffix = "  ]\n}\n";
        return actual.equals(prefix + managedPrefix + suffix)
            || actual.equals(prefix + managedPrefix + javaEntry + "\n" + suffix)
            || actual.equals(prefix + managedPrefix + kotlinEntry + "\n" + suffix)
            || actual.equals(prefix + managedPrefix + javaEntry + ",\n" + kotlinEntry + "\n" + suffix);
    }

    private static Set<String> markerChildren(String contents) {
        Set<String> result = new HashSet<>();
        Matcher matcher = RELATIVE_CHILD.matcher(contents);
        while (matcher.find()) {
            String relative = matcher.group(1);
            if (relative.equals(".") || relative.startsWith("/") || relative.contains("..")
                || relative.contains("\\") || relative.contains(":")) {
                throw new IllegalArgumentException("Repost generated output parent marker has an unsafe child");
            }
            result.add(relative.substring(0, relative.indexOf('/') < 0
                ? relative.length()
                : relative.indexOf('/')));
        }
        return result;
    }

    private static void prune(Path root, Set<String> currentChildren) {
        Path marker = root.resolve(MARKER);
        if (!Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS)) return;
        try {
            for (String previous : markerChildren(Files.readString(marker, StandardCharsets.UTF_8))) {
                if (!currentChildren.contains(previous)) deleteTree(root.resolve(previous));
            }
        } catch (IOException exception) {
            throw new IllegalArgumentException("Repost stale generated output could not be pruned");
        }
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return;
        Files.walkFileTree(root, new java.nio.file.SimpleFileVisitor<Path>() {
            @Override public java.nio.file.FileVisitResult visitFile(
                Path file,
                java.nio.file.attribute.BasicFileAttributes attributes
            ) throws IOException {
                Files.delete(file);
                return java.nio.file.FileVisitResult.CONTINUE;
            }
            @Override public java.nio.file.FileVisitResult postVisitDirectory(
                Path directory,
                IOException failure
            ) throws IOException {
                if (failure != null) throw failure;
                Files.delete(directory);
                return java.nio.file.FileVisitResult.CONTINUE;
            }
        });
    }

    private static void mark(Path root, String contents) {
        Path temporary = root.resolve(MARKER + ".tmp");
        try {
            Files.createDirectories(root);
            Files.writeString(temporary, contents, StandardCharsets.UTF_8);
            Files.move(
                temporary,
                root.resolve(MARKER),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
            );
        } catch (IOException exception) {
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException ignored) {
                // The stable ownership failure remains primary.
            }
            throw new IllegalArgumentException("Repost generated output parent marker could not be written");
        }
    }

    private static List<BuildPluginEngine.Generator> defaults(
        List<BuildPluginEngine.Generator> generators,
        Path parent,
        boolean source
    ) {
        List<BuildPluginEngine.Generator> result = new ArrayList<>();
        for (BuildPluginEngine.Generator generator : generators) {
            Path output = Path.of(source
                ? generator.getSourceOutputDirectory()
                : generator.getResourceOutputDirectory()).toAbsolutePath().normalize();
            Path normalizedParent = parent.toAbsolutePath().normalize();
            Path expected = source
                ? normalizedParent.resolve(generatorId(generator.getName()))
                    .resolve("javaSdk".equals(generator.getName()) ? "java" : "kotlin")
                : normalizedParent.resolve(generatorId(generator.getName()));
            if (output.equals(expected)) {
                result.add(generator);
            } else if (output.startsWith(normalizedParent)) {
                throw new IllegalArgumentException(
                    "Repost output overrides cannot use a non-default child inside a default parent root"
                );
            }
        }
        return result;
    }

    private static void validateOverrides(
        List<BuildPluginEngine.Generator> generators,
        Path parent,
        boolean source,
        String groupId,
        String artifactId
    ) {
        for (BuildPluginEngine.Generator generator : generators) {
            Path output = Path.of(source
                ? generator.getSourceOutputDirectory()
                : generator.getResourceOutputDirectory()).toAbsolutePath().normalize();
            if (!output.startsWith(parent.toAbsolutePath().normalize())) {
                validateOverride(output, overrideMarker(source, generator, groupId, artifactId));
            }
        }
    }

    private static void markOverrides(
        List<BuildPluginEngine.Generator> generators,
        Path parent,
        boolean source,
        String groupId,
        String artifactId
    ) {
        for (BuildPluginEngine.Generator generator : generators) {
            Path output = Path.of(source
                ? generator.getSourceOutputDirectory()
                : generator.getResourceOutputDirectory()).toAbsolutePath().normalize();
            if (!output.startsWith(parent.toAbsolutePath().normalize())) {
                mark(output, overrideMarker(source, generator, groupId, artifactId));
            }
        }
    }

    private static void validateOverride(Path root, String expectedMarker) {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return;
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("Repost generated output override is not a directory: " + root);
        }
        try (DirectoryStream<Path> children = Files.newDirectoryStream(root)) {
            boolean empty = true;
            for (Path child : children) {
                empty = false;
                if (Files.isSymbolicLink(child)) {
                    throw new IllegalArgumentException("Repost generated output override contains a symbolic link");
                }
            }
            if (empty) return;
            Path marker = root.resolve(MARKER);
            if (!Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS)
                || !expectedMarker.equals(Files.readString(marker, StandardCharsets.UTF_8))) {
                throw new IllegalArgumentException(
                    "Repost generated output override is not owned by this Maven execution: " + root
                );
            }
        } catch (IOException exception) {
            throw new IllegalArgumentException("Repost generated output override could not be inspected");
        }
    }

    private static String overrideMarker(
        boolean source,
        BuildPluginEngine.Generator generator,
        String groupId,
        String artifactId
    ) {
        return marker(
            source ? "source-override" : "resource-override",
            List.of(generator), groupId, artifactId, true
        );
    }

    private static String aggregateMarker(String kind, String groupId, String artifactId) {
        return marker("aggregate-" + kind, List.of(), groupId, artifactId, false);
    }

    private static Set<String> managedChildren(
        List<BuildPluginEngine.Generator> generators,
        boolean source
    ) {
        Set<String> result = new HashSet<>();
        for (BuildPluginEngine.Generator generator : generators) {
            Path output = Path.of(source
                ? generator.getSourceOutputDirectory()
                : generator.getResourceOutputDirectory());
            Path parent = source ? output.getParent().getParent() : output.getParent();
            result.add(parent.relativize(output).getName(0).toString());
        }
        return result;
    }

    private static String marker(
        String outputKind,
        List<BuildPluginEngine.Generator> generators,
        String groupId,
        String artifactId,
        boolean override
    ) {
        List<BuildPluginEngine.Generator> sorted = new ArrayList<>(generators);
        sorted.sort(Comparator.comparing(BuildPluginEngine.Generator::getName));
        StringBuilder output = new StringBuilder();
        output.append("{\n")
            .append("  \"formatVersion\": 1,\n")
            .append("  \"buildIdentity\": {\"kind\": \"MAVEN\", \"groupId\": ")
            .append(json(groupId)).append(", \"artifactId\": ").append(json(artifactId)).append("},\n")
            .append("  \"outputKind\": ").append(json(outputKind)).append(",\n")
            .append("  \"managed\": [\n");
        for (int index = 0; index < sorted.size(); index++) {
            BuildPluginEngine.Generator generator = sorted.get(index);
            String language = "javaSdk".equals(generator.getName()) ? "java" : "kotlin";
            boolean source = outputKind.startsWith("source");
            Path path = Path.of(source
                ? generator.getSourceOutputDirectory()
                : generator.getResourceOutputDirectory());
            Path parent = source && "source".equals(outputKind)
                ? path.getParent().getParent()
                : path.getParent();
            output.append("    {\"generatorId\": ").append(json(generatorId(generator.getName())))
                .append(", \"language\": ").append(json(language))
                .append(", \"relativeChild\": ")
                .append(json(override ? "." : parent.relativize(path).toString().replace('\\', '/')))
                .append("}").append(index + 1 == sorted.size() ? "\n" : ",\n");
        }
        return output.append("  ]\n}\n").toString();
    }

    private static String json(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static String generatorId(String generator) {
        return "javaSdk".equals(generator) ? "cfc20961be395d84" : "c756cf657644d412";
    }
}
