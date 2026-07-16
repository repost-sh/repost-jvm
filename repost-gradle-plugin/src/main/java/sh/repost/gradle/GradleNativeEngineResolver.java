package sh.repost.gradle;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.PublicKey;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import sh.repost.buildplugin.internal.NativeEngineArtifacts;

/** Gradle artifact-set boundary around the shared verified native-engine preparation path. */
final class GradleNativeEngineResolver {
    private static final int MAX_MANIFEST_BYTES = 1_048_576;
    private static final int MAX_SIGNATURE_BYTES = 65_536;

    private GradleNativeEngineResolver() {
    }

    static Path resolve(
        String osName,
        String architectureName,
        String engineVersion,
        Collection<Path> resolvedArtifacts,
        List<PublicKey> trustedKeys,
        Path cacheRoot
    ) {
        return resolve(
            osName,
            architectureName,
            engineVersion,
            resolvedArtifacts,
            trustedKeys,
            cacheRoot,
            false
        );
    }

    static Path resolve(
        String osName,
        String architectureName,
        String engineVersion,
        Collection<Path> resolvedArtifacts,
        List<PublicKey> trustedKeys,
        Path cacheRoot,
        boolean offline
    ) {
        try {
            return resolve(
                osName,
                architectureName,
                engineVersion,
                resolvedArtifacts,
                trustedKeys,
                cacheRoot,
                offline,
                (os, architecture, version, archive, manifest, signature, keys, cache) ->
                    NativeEngineArtifacts.prepare(
                        os,
                        architecture,
                        version,
                        archive,
                        manifest,
                        signature,
                        keys,
                        cache
                    ).getExecutable()
            );
        } catch (NativeEngineArtifacts.ArtifactException exception) {
            throw new ResolutionException(exception.getMessage());
        }
    }

    static Path resolve(
        String osName,
        String architectureName,
        String engineVersion,
        Collection<Path> resolvedArtifacts,
        List<PublicKey> trustedKeys,
        Path cacheRoot,
        EnginePreparer preparer
    ) {
        return resolve(
            osName,
            architectureName,
            engineVersion,
            resolvedArtifacts,
            trustedKeys,
            cacheRoot,
            false,
            preparer
        );
    }

    static Path resolve(
        String osName,
        String architectureName,
        String engineVersion,
        Collection<Path> resolvedArtifacts,
        List<PublicKey> trustedKeys,
        Path cacheRoot,
        boolean offline,
        EnginePreparer preparer
    ) {
        Objects.requireNonNull(resolvedArtifacts, "resolvedArtifacts");
        Objects.requireNonNull(trustedKeys, "trustedKeys");
        Objects.requireNonNull(cacheRoot, "cacheRoot");
        Objects.requireNonNull(preparer, "preparer");
        String classifier = NativeEngineArtifacts.classifier(osName, architectureName);
        Map<String, Path> expected = new HashMap<>();
        expected.put(
            "repost-schema-engine-" + engineVersion + "-" + classifier + ".zip",
            null
        );
        expected.put("repost-schema-engine-" + engineVersion + "-checksums.json", null);
        expected.put("repost-schema-engine-" + engineVersion + "-checksums.sig", null);
        for (Path path : new ArrayList<>(resolvedArtifacts)) {
            if (path == null || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                throw mismatch();
            }
            String name = path.getFileName().toString();
            if (!expected.containsKey(name) || expected.put(name, path) != null) {
                throw mismatch();
            }
        }
        if (expected.containsValue(null)) {
            throw missing(engineVersion, classifier, expected, offline);
        }
        if (expected.size() != resolvedArtifacts.size()) {
            throw mismatch();
        }
        return preparer.prepare(
            osName,
            architectureName,
            engineVersion,
            expected.get("repost-schema-engine-" + engineVersion + "-" + classifier + ".zip"),
            readBounded(
                expected.get("repost-schema-engine-" + engineVersion + "-checksums.json"),
                MAX_MANIFEST_BYTES,
                "checksum manifest"
            ),
            readBounded(
                expected.get("repost-schema-engine-" + engineVersion + "-checksums.sig"),
                MAX_SIGNATURE_BYTES,
                "checksum signature"
            ),
            trustedKeys,
            cacheRoot
        );
    }

    private static byte[] readBounded(Path path, int maximum, String description) {
        try {
            if (Files.size(path) > maximum) {
                throw new ResolutionException("native engine " + description + " exceeds " + maximum + " bytes");
            }
            byte[] value = Files.readAllBytes(path);
            if (value.length > maximum) {
                throw new ResolutionException("native engine " + description + " exceeds " + maximum + " bytes");
            }
            return value;
        } catch (IOException exception) {
            throw new ResolutionException("could not read native engine " + description);
        }
    }

    private static ResolutionException mismatch() {
        return new ResolutionException(
            "resolved native engine artifact set does not match the exact selected coordinates"
        );
    }

    private static ResolutionException missing(
        String version,
        String classifier,
        Map<String, Path> expected,
        boolean offline
    ) {
        List<String> coordinates = new ArrayList<>();
        if (expected.get("repost-schema-engine-" + version + "-" + classifier + ".zip") == null) {
            coordinates.add("sh.repost:repost-schema-engine:" + version + ":" + classifier + "@zip");
        }
        if (expected.get("repost-schema-engine-" + version + "-checksums.json") == null) {
            coordinates.add("sh.repost:repost-schema-engine:" + version + ":checksums@json");
        }
        if (expected.get("repost-schema-engine-" + version + "-checksums.sig") == null) {
            coordinates.add("sh.repost:repost-schema-engine:" + version + ":checksums@sig");
        }
        String message = "could not resolve native engine artifacts: " + String.join(", ", coordinates);
        if (offline) {
            message += " from the Gradle cache while offline; run Gradle without --offline once to populate them";
        }
        return new ResolutionException(message);
    }

    @FunctionalInterface
    interface EnginePreparer {
        Path prepare(
            String osName,
            String architectureName,
            String engineVersion,
            Path archive,
            byte[] manifestBytes,
            byte[] signatureBytes,
            List<PublicKey> trustedKeys,
            Path cacheRoot
        );
    }

    static final class ResolutionException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        ResolutionException(String message) {
            super(message);
        }
    }
}
