package sh.repost.buildplugin.internal;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Authenticates generated source/resource trees restored by Gradle. */
final class GeneratedOutputVerifier {
    private static final String MARKER = ".repost-client.json";
    private static final Pattern GENERATOR_ID = Pattern.compile("[0-9a-f]{16}");
    private static final Pattern SHA_256 = Pattern.compile("sha256:[0-9a-f]{64}");
    private static final int MAX_MARKER_BYTES = 1024 * 1024;
    private static final List<String> MARKER_FIELDS = List.of(
        "formatVersion", "language", "engineVersion", "generator", "outputKind",
        "generatorId", "packageName", "schemaHash", "runtimeVersion", "descriptorVersion",
        "clientName", "regenerateCommand", "treeHash", "files"
    );

    private GeneratedOutputVerifier() {
    }

    static void verify(BuildPluginEngine.Generator generator) {
        Path sourceOutput = Path.of(generator.getSourceOutputDirectory()).toAbsolutePath().normalize();
        Path resourceOutput = Path.of(generator.getResourceOutputDirectory()).toAbsolutePath().normalize();
        Path resourceMarker = resourceMarker(resourceOutput);
        Marker resource = marker(resourceMarker, "registry", generator.getName());
        if (!resource.generatorId.equals(resourceMarker.getParent().getFileName().toString())) {
            throw failure("Repost generated resource marker is stored under the wrong generator ID");
        }
        Path sourceRoot = packageRoot(sourceOutput, resource.packageName);
        Marker source = marker(sourceRoot.resolve(MARKER), "source", generator.getName());
        if (!source.generatorId.equals(resource.generatorId)
            || !source.language.equals(resource.language)
            || !source.packageName.equals(resource.packageName)
            || !source.schemaHash.equals(resource.schemaHash)) {
            throw failure("Repost generated source and resource markers do not describe one output");
        }
        verifyTree(sourceRoot, source);
        verifyTree(resourceMarker.getParent(), resource);
    }

    private static Path resourceMarker(Path output) {
        Path root = output.resolve("META-INF/repost/generated-clients/v1");
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            throw failure("Repost generated output marker is missing");
        }
        List<Path> markers = new ArrayList<>();
        try (java.util.stream.Stream<Path> children = Files.list(root)) {
            children.filter(path -> GENERATOR_ID.matcher(path.getFileName().toString()).matches())
                .map(path -> path.resolve(MARKER))
                .filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                .forEach(markers::add);
        } catch (IOException exception) {
            throw failure("Repost generated output marker could not be enumerated");
        }
        if (markers.size() != 1) {
            throw failure("Repost generated output must contain exactly one marker");
        }
        return markers.get(0);
    }

    private static Path packageRoot(Path output, String packageName) {
        Path result = output;
        for (String segment : packageName.split("\\.", -1)) {
            if (segment.isEmpty() || !segment.matches("[A-Za-z_$][A-Za-z0-9_$]*")) {
                throw failure("Repost generated output marker has an invalid package name");
            }
            result = result.resolve(segment);
        }
        result = result.normalize();
        if (!result.startsWith(output)) {
            throw failure("Repost generated output marker escapes its source root");
        }
        return result;
    }

    private static Marker marker(Path path, String outputKind, String generatorName) {
        final byte[] bytes;
        try {
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                throw failure("Repost generated output marker is missing");
            }
            if (Files.size(path) > MAX_MARKER_BYTES) {
                throw failure("Repost generated output marker exceeds 1 MiB");
            }
            bytes = Files.readAllBytes(path);
        } catch (IOException exception) {
            throw failure("Repost generated output marker could not be read");
        }
        final Map<String, Object> fields;
        try {
            Object parsed = StrictJson.parse(bytes);
            if (!(parsed instanceof Map)) {
                throw failure("Repost generated output marker must be an object");
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> cast = (Map<String, Object>) parsed;
            fields = cast;
        } catch (GenerationProtocolV1.ProtocolException exception) {
            throw failure("Repost generated output marker is invalid JSON");
        }
        if (!new ArrayList<>(fields.keySet()).equals(MARKER_FIELDS)) {
            throw failure("Repost generated output marker fields are not canonical format 2");
        }
        integer(fields, "formatVersion", 2);
        String language = string(fields, "language");
        if (!expectedLanguage(generatorName).equals(language)) {
            throw failure("Repost generated output marker language does not match its generator");
        }
        string(fields, "engineVersion");
        String actualKind = string(fields, "outputKind");
        String actualGenerator = string(fields, "generator");
        String generatorId = matching(fields, "generatorId", GENERATOR_ID);
        String schemaHash = matching(fields, "schemaHash", SHA_256);
        string(fields, "runtimeVersion");
        integer(fields, "descriptorVersion", 2);
        string(fields, "clientName");
        string(fields, "regenerateCommand");
        String treeHash = matching(fields, "treeHash", SHA_256);
        if (!outputKind.equals(actualKind) || !generatorName.equals(actualGenerator)) {
            throw failure("Repost generated output marker ownership does not match the Gradle task");
        }
        Object fileValue = fields.get("files");
        if (!(fileValue instanceof List)) {
            throw failure("Repost generated output marker files must be an array");
        }
        List<String> files = new ArrayList<>();
        for (Object value : (List<?>) fileValue) {
            if (!(value instanceof String) || !safeRelativePath((String) value)) {
                throw failure("Repost generated output marker contains an unsafe file path");
            }
            files.add((String) value);
        }
        List<String> sorted = new ArrayList<>(files);
        Collections.sort(sorted);
        Set<String> folded = new HashSet<>();
        for (String file : files) {
            folded.add(file.toLowerCase(java.util.Locale.ROOT));
        }
        if (!files.equals(sorted) || new HashSet<>(files).size() != files.size() || folded.size() != files.size()) {
            throw failure("Repost generated output marker files are not sorted and unique");
        }
        return new Marker(language, generatorId, string(fields, "packageName"), schemaHash, treeHash, files);
    }

    private static void verifyTree(Path root, Marker marker) {
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            throw failure("Repost generated output root is missing");
        }
        MessageDigest digest = sha256();
        Set<String> expected = new HashSet<>(marker.files);
        expected.add(MARKER);
        for (String name : marker.files) {
            Path file = root.resolve(name).normalize();
            if (!file.startsWith(root) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                throw failure("Repost generated output marker lists a missing file");
            }
            try {
                hashPart(digest, name.getBytes(StandardCharsets.UTF_8));
                hashPart(digest, Files.readAllBytes(file));
            } catch (IOException exception) {
                throw failure("Repost generated output file could not be read");
            }
        }
        if (!marker.treeHash.equals("sha256:" + hex(digest.digest()))) {
            throw failure("Repost generated output tree hash does not match its marker");
        }
        Set<String> actual = new HashSet<>();
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) {
                    if (attributes.isSymbolicLink()) {
                        throw failure("Repost generated output contains a symbolic link");
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                    if (attributes.isSymbolicLink() || !attributes.isRegularFile()) {
                        throw failure("Repost generated output contains a non-regular file");
                    }
                    actual.add(root.relativize(file).toString().replace('\\', '/'));
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException exception) {
            throw failure("Repost generated output files could not be enumerated");
        }
        if (!actual.equals(expected)) {
            throw failure("Repost generated output contains files not listed by its marker");
        }
    }

    private static boolean safeRelativePath(String value) {
        if (value.isEmpty() || value.equals(MARKER) || value.startsWith("/")
            || value.contains("\\") || value.contains(":") || value.contains("//")) {
            return false;
        }
        try {
            Path path = Path.of(value);
            return !path.isAbsolute() && path.normalize().equals(path)
                && !value.startsWith("./") && !value.endsWith("/");
        } catch (InvalidPathException exception) {
            return false;
        }
    }

    private static String expectedLanguage(String generatorName) {
        if ("javaSdk".equals(generatorName)) {
            return "java";
        }
        if ("kotlinSdk".equals(generatorName)) {
            return "kotlin";
        }
        throw failure("Repost generated output uses an unsupported JVM generator");
    }

    private static void integer(Map<String, Object> fields, String name, long expected) {
        Object value = fields.get(name);
        if (!(value instanceof Long) || ((Long) value).longValue() != expected) {
            throw failure("Repost generated output marker has an invalid " + name);
        }
    }

    private static String matching(Map<String, Object> fields, String name, Pattern pattern) {
        String value = string(fields, name);
        if (!pattern.matcher(value).matches()) {
            throw failure("Repost generated output marker has an invalid " + name);
        }
        return value;
    }

    private static String string(Map<String, Object> fields, String name) {
        Object value = fields.get(name);
        if (!(value instanceof String) || ((String) value).isEmpty()) {
            throw failure("Repost generated output marker has an invalid " + name);
        }
        return (String) value;
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static void hashPart(MessageDigest digest, byte[] bytes) {
        digest.update(ByteBuffer.allocate(8).putLong(bytes.length).array());
        digest.update(bytes);
    }

    private static String hex(byte[] bytes) {
        StringBuilder output = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            output.append(String.format("%02x", value & 0xff));
        }
        return output.toString();
    }

    private static BuildPluginEngine.EngineException failure(String message) {
        return new BuildPluginEngine.EngineException(message);
    }

    private static final class Marker {
        private final String language;
        private final String generatorId;
        private final String packageName;
        private final String schemaHash;
        private final String treeHash;
        private final List<String> files;

        private Marker(
            String language,
            String generatorId,
            String packageName,
            String schemaHash,
            String treeHash,
            List<String> files
        ) {
            this.language = language;
            this.generatorId = generatorId;
            this.packageName = packageName;
            this.schemaHash = schemaHash;
            this.treeHash = treeHash;
            this.files = files;
        }
    }
}
