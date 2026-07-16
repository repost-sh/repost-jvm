package sh.repost.buildplugin.internal;

import java.nio.file.Path;
import java.security.PublicKey;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** Shared classifier and verified extraction facade used by both build plugins. */
public final class NativeEngineArtifacts {
    private NativeEngineArtifacts() {
    }

    /**
     * Resolves one approved classifier without probing the host libc.
     *
     * @param osName operating-system name
     * @param architectureName architecture name
     * @return exact published classifier
     */
    public static String classifier(String osName, String architectureName) {
        try {
            return NativeEngineDistribution.Platform.resolve(osName, architectureName).classifier();
        } catch (NativeEngineDistribution.DistributionException exception) {
            throw new ArtifactException(exception.getMessage());
        }
    }

    /**
     * Verifies and extracts one already-resolved native engine archive.
     *
     * @param osName operating-system name
     * @param architectureName architecture name
     * @param engineVersion exact engine version
     * @param archive resolved classifier ZIP
     * @param manifestBytes signed checksum manifest bytes
     * @param signatureBytes detached manifest signature bytes
     * @param trustedKeys pinned trusted public keys
     * @param cacheRoot private build-tool cache root
     * @return verified prepared executable
     */
    public static Prepared prepare(
        String osName,
        String architectureName,
        String engineVersion,
        Path archive,
        byte[] manifestBytes,
        byte[] signatureBytes,
        List<PublicKey> trustedKeys,
        Path cacheRoot
    ) {
        Objects.requireNonNull(manifestBytes, "manifestBytes");
        Objects.requireNonNull(signatureBytes, "signatureBytes");
        Objects.requireNonNull(trustedKeys, "trustedKeys");
        List<PublicKey> keyCopy = new ArrayList<>(trustedKeys.size());
        for (PublicKey key : trustedKeys) {
            keyCopy.add(key);
        }
        try {
            NativeEngineDistribution.PreparedEngine prepared = NativeEngineDistribution.prepare(
                NativeEngineDistribution.Platform.resolve(osName, architectureName),
                engineVersion,
                archive,
                Arrays.copyOf(manifestBytes, manifestBytes.length),
                Arrays.copyOf(signatureBytes, signatureBytes.length),
                Collections.unmodifiableList(keyCopy),
                cacheRoot
            );
            return new Prepared(prepared.platform().classifier(), prepared.executable());
        } catch (NativeEngineDistribution.DistributionException exception) {
            throw new ArtifactException(exception.getMessage());
        }
    }

    /** One verified native executable and its exact classifier. */
    public static final class Prepared {
        private final String classifier;
        private final Path executable;

        private Prepared(String classifier, Path executable) {
            this.classifier = classifier;
            this.executable = executable;
        }

        /** Returns the resolved published classifier. */
        public String getClassifier() {
            return classifier;
        }

        /** Returns the verified executable in the private cache. */
        public Path getExecutable() {
            return executable;
        }
    }

    /** Sanitized native artifact selection, integrity, or extraction failure. */
    public static final class ArtifactException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        private ArtifactException(String message) {
            super(message);
        }
    }
}
