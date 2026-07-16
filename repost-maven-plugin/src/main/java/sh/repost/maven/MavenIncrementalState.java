package sh.repost.maven;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.jar.JarFile;
import java.util.regex.Pattern;
import sh.repost.buildplugin.internal.BuildPluginEngine;

/** Hash-only Maven incremental state; no raw environment or secret value is persisted. */
final class MavenIncrementalState {
    private static final String FILE = "incremental-v1.state";
    private static final Pattern REGISTRY = Pattern.compile(
        "META-INF/repost/registries/v1/[0-9a-f]{16}/registry\\.json"
    );

    private MavenIncrementalState() {
    }

    static String fingerprint(
        BuildPluginEngine.Request request,
        String schemaMode,
        String integration,
        Set<Path> dependencyArtifacts
    ) {
        MessageDigest digest = sha256();
        hash(digest, "plugin", "1.0.0");
        hash(digest, "engine", request.getEngineVersion());
        hash(digest, "protocol", "1");
        hash(digest, "runtime", "1.0.0");
        hash(digest, "descriptor", "2");
        hash(digest, "schemaMode", schemaMode);
        hash(digest, "integration", integration);
        try {
            hash(digest, "schema", bytesHash(Files.readAllBytes(Path.of(request.getSchemaPath()))));
        } catch (IOException exception) {
            throw new IllegalArgumentException("Repost schema bytes could not be fingerprinted");
        }
        List<BuildPluginEngine.Generator> generators = new ArrayList<>(request.getGenerators());
        generators.sort(Comparator.comparing(BuildPluginEngine.Generator::getName));
        for (BuildPluginEngine.Generator generator : generators) {
            hash(digest, "generator", generator.getName());
            hash(digest, "source", generator.getSourceOutputDirectory());
            hash(digest, "resource", generator.getResourceOutputDirectory());
        }
        for (Map.Entry<String, String> entry : new TreeMap<>(request.getEnvironment()).entrySet()) {
            hash(digest, "environmentName", entry.getKey());
            hash(digest, "environmentValueHash", bytesHash(entry.getValue().getBytes(StandardCharsets.UTF_8)));
        }
        List<Path> artifacts = new ArrayList<>(dependencyArtifacts);
        artifacts.sort(Comparator.comparing(path -> path.toAbsolutePath().normalize().toString()));
        List<String> registryHashes = new ArrayList<>();
        for (Path artifact : artifacts) {
            registryHashes.addAll(dependencyRegistryHashes(artifact));
        }
        registryHashes.sort(String::compareTo);
        for (String registryHash : registryHashes) hash(digest, "dependencyRegistry", registryHash);
        return "sha256:" + hex(digest.digest());
    }

    static String aggregateFingerprint(
        String schemaMode,
        String integration,
        Set<Path> dependencyArtifacts
    ) {
        MessageDigest digest = sha256();
        hash(digest, "plugin", "1.0.0");
        hash(digest, "runtime", "1.0.0");
        hash(digest, "descriptor", "2");
        hash(digest, "schemaMode", schemaMode);
        hash(digest, "integration", integration);
        List<String> hashes = new ArrayList<>();
        for (Path artifact : dependencyArtifacts) hashes.addAll(dependencyRegistryHashes(artifact));
        hashes.sort(String::compareTo);
        for (String registryHash : hashes) hash(digest, "dependencyRegistry", registryHash);
        return "sha256:" + hex(digest.digest());
    }

    static boolean canReuse(
        Path stateDirectory,
        String fingerprint,
        List<BuildPluginEngine.Generator> generators,
        Path aggregateSource,
        Path aggregateResource
    ) {
        Path state = stateDirectory.resolve(FILE);
        if (!Files.isRegularFile(state, LinkOption.NOFOLLOW_LINKS)) {
            return false;
        }
        try {
            BuildPluginEngine.verifyGeneratedOutputs(generators);
            return Files.readString(state, StandardCharsets.UTF_8).equals(contents(
                fingerprint,
                generators,
                aggregateSource,
                aggregateResource
            ));
        } catch (IOException | RuntimeException exception) {
            return false;
        }
    }

    static void store(
        Path stateDirectory,
        String fingerprint,
        List<BuildPluginEngine.Generator> generators,
        Path aggregateSource,
        Path aggregateResource
    ) {
        BuildPluginEngine.verifyGeneratedOutputs(generators);
        String contents = contents(fingerprint, generators, aggregateSource, aggregateResource);
        Path state = stateDirectory.resolve(FILE);
        Path temporary = state.resolveSibling(FILE + ".tmp");
        try {
            Files.createDirectories(stateDirectory);
            Files.writeString(temporary, contents, StandardCharsets.UTF_8);
            Files.move(temporary, state, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException exception) {
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException ignored) {
                // The stable state-write failure remains primary.
            }
            throw new IllegalArgumentException("Repost incremental state could not be stored");
        }
    }

    private static String contents(
        String fingerprint,
        List<BuildPluginEngine.Generator> generators,
        Path aggregateSource,
        Path aggregateResource
    ) {
        StringBuilder output = new StringBuilder("format=1\nfingerprint=").append(fingerprint).append('\n');
        List<BuildPluginEngine.Generator> sorted = new ArrayList<>(generators);
        sorted.sort(Comparator.comparing(BuildPluginEngine.Generator::getName));
        for (BuildPluginEngine.Generator generator : sorted) {
            output.append(generator.getName()).append(".source=")
                .append(treeHash(Path.of(generator.getSourceOutputDirectory()))).append('\n');
            output.append(generator.getName()).append(".resource=")
                .append(treeHash(Path.of(generator.getResourceOutputDirectory()))).append('\n');
        }
        output.append("aggregate.source=").append(treeHash(aggregateSource)).append('\n');
        output.append("aggregate.resource=").append(treeHash(aggregateResource)).append('\n');
        return output.toString();
    }

    private static String treeHash(Path root) {
        Path normalized = root.toAbsolutePath().normalize();
        if (!Files.exists(normalized, LinkOption.NOFOLLOW_LINKS)) {
            return "sha256:" + hex(sha256().digest());
        }
        if (Files.isRegularFile(normalized, LinkOption.NOFOLLOW_LINKS)) {
            try {
                return "sha256:" + bytesHash(Files.readAllBytes(normalized));
            } catch (IOException exception) {
                throw new IllegalArgumentException("Repost incremental input could not be hashed");
            }
        }
        if (!Files.isDirectory(normalized, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("Repost incremental input is not a regular file or directory");
        }
        List<Path> files = new ArrayList<>();
        try {
            Files.walkFileTree(normalized, new SimpleFileVisitor<Path>() {
                @Override public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) {
                    if (attributes.isSymbolicLink()) {
                        throw new IllegalArgumentException("Repost incremental input contains a symbolic link");
                    }
                    return FileVisitResult.CONTINUE;
                }
                @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                    if (attributes.isSymbolicLink() || !attributes.isRegularFile()) {
                        throw new IllegalArgumentException("Repost incremental input contains a non-regular file");
                    }
                    files.add(file);
                    return FileVisitResult.CONTINUE;
                }
            });
            files.sort(Comparator.comparing(path -> normalized.relativize(path).toString().replace('\\', '/')));
            MessageDigest digest = sha256();
            for (Path file : files) {
                hash(digest, "path", normalized.relativize(file).toString().replace('\\', '/'));
                hash(digest, "bytes", bytesHash(Files.readAllBytes(file)));
            }
            return "sha256:" + hex(digest.digest());
        } catch (IOException exception) {
            throw new IllegalArgumentException("Repost incremental input could not be hashed");
        }
    }

    private static String bytesHash(byte[] bytes) {
        return hex(sha256().digest(bytes));
    }

    private static List<String> dependencyRegistryHashes(Path artifact) {
        Path normalized = artifact.toAbsolutePath().normalize();
        List<String> hashes = new ArrayList<>();
        try {
            if (Files.isDirectory(normalized, LinkOption.NOFOLLOW_LINKS)) {
                try (java.util.stream.Stream<Path> files = Files.walk(normalized)) {
                    files.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                        .filter(path -> REGISTRY.matcher(
                            normalized.relativize(path).toString().replace('\\', '/')
                        ).matches())
                        .forEach(path -> {
                            try {
                                hashes.add(bytesHash(Files.readAllBytes(path)));
                            } catch (IOException exception) {
                                throw new IllegalArgumentException(
                                    "Repost dependency registry input could not be hashed"
                                );
                            }
                        });
                }
            } else if (Files.isRegularFile(normalized, LinkOption.NOFOLLOW_LINKS)) {
                try (JarFile jar = new JarFile(normalized.toFile(), false)) {
                    jar.stream().filter(entry -> !entry.isDirectory())
                        .filter(entry -> REGISTRY.matcher(entry.getName()).matches())
                        .forEach(entry -> {
                            try (java.io.InputStream input = jar.getInputStream(entry)) {
                                hashes.add(bytesHash(input.readAllBytes()));
                            } catch (IOException exception) {
                                throw new IllegalArgumentException(
                                    "Repost dependency registry input could not be hashed"
                                );
                            }
                        });
                }
            }
        } catch (IOException exception) {
            throw new IllegalArgumentException("Repost dependency registry input could not be hashed");
        }
        hashes.sort(String::compareTo);
        return hashes;
    }

    private static void hash(MessageDigest digest, String name, String value) {
        byte[] nameBytes = name.getBytes(StandardCharsets.UTF_8);
        byte[] valueBytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(4).putInt(nameBytes.length).array());
        digest.update(nameBytes);
        digest.update(ByteBuffer.allocate(4).putInt(valueBytes.length).array());
        digest.update(valueBytes);
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder output = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            output.append(String.format("%02x", value & 0xff));
        }
        return output.toString();
    }
}
