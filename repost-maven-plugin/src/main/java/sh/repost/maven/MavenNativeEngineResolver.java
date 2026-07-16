package sh.repost.maven;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.PublicKey;
import java.util.List;
import java.util.Objects;
import sh.repost.buildplugin.internal.NativeEngineArtifacts;

/** Maven-facing coordinate selection around the shared verified native-engine preparation path. */
final class MavenNativeEngineResolver {
    private static final String GROUP = "sh.repost";
    private static final String ARTIFACT = "repost-schema-engine";
    private static final int MAX_MANIFEST_BYTES = 1_048_576;
    private static final int MAX_SIGNATURE_BYTES = 65_536;

    private MavenNativeEngineResolver() {
    }

    static Path resolve(
        String osName,
        String architectureName,
        String engineVersion,
        ArtifactSource source,
        List<PublicKey> trustedKeys,
        Path cacheRoot
    ) {
        try {
            return resolve(
                osName,
                architectureName,
                engineVersion,
                source,
                trustedKeys,
                cacheRoot,
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
        ArtifactSource source,
        List<PublicKey> trustedKeys,
        Path cacheRoot,
        EnginePreparer preparer
    ) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(trustedKeys, "trustedKeys");
        Objects.requireNonNull(cacheRoot, "cacheRoot");
        Objects.requireNonNull(preparer, "preparer");
        String classifier = NativeEngineArtifacts.classifier(osName, architectureName);
        Path archive = source.resolve(new Coordinate("zip", classifier, engineVersion));
        Path manifest = source.resolve(new Coordinate("json", "checksums", engineVersion));
        Path signature = source.resolve(new Coordinate("sig", "checksums", engineVersion));
        return preparer.prepare(
            osName,
            architectureName,
            engineVersion,
            requiredArtifact(archive),
            readBounded(manifest, MAX_MANIFEST_BYTES, "checksum manifest"),
            readBounded(signature, MAX_SIGNATURE_BYTES, "checksum signature"),
            trustedKeys,
            cacheRoot
        );
    }

    private static Path requiredArtifact(Path path) {
        if (path == null || !Files.isRegularFile(path)) {
            throw new ResolutionException("resolved native engine artifact is not a regular file");
        }
        return path;
    }

    private static byte[] readBounded(Path path, int maximum, String description) {
        requiredArtifact(path);
        try {
            long size = Files.size(path);
            if (size > maximum) {
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

    /** Resolves one exact Maven artifact from configured repositories/cache. */
    @FunctionalInterface
    interface ArtifactSource {
        Path resolve(Coordinate coordinate);
    }

    /** Shared preparation seam used only to isolate Maven-coordinate tests from cryptography. */
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

    /** Exact native-engine Maven coordinate. */
    static final class Coordinate {
        private final String extension;
        private final String classifier;
        private final String version;

        Coordinate(String extension, String classifier, String version) {
            this.extension = requirePart(extension, "extension");
            this.classifier = requirePart(classifier, "classifier");
            this.version = requirePart(version, "version");
        }

        String extension() {
            return extension;
        }

        String classifier() {
            return classifier;
        }

        String version() {
            return version;
        }

        @Override
        public String toString() {
            return GROUP + ":" + ARTIFACT + ":" + extension + ":" + classifier + ":" + version;
        }

        private static String requirePart(String value, String name) {
            if (value == null || value.isEmpty()) {
                throw new ResolutionException("native engine artifact " + name + " is empty");
            }
            return value;
        }
    }

    /** Sanitized Maven artifact selection/read failure. */
    static final class ResolutionException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        ResolutionException(String message) {
            super(message);
        }
    }
}
