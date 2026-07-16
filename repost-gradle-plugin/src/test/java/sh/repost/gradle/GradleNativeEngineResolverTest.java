package sh.repost.gradle;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.PublicKey;
import java.util.Arrays;
import java.util.Collections;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class GradleNativeEngineResolverTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void selectsExactlyTheResolvedClassifierManifestAndSignature() throws Exception {
        Path archive = write("repost-schema-engine-0.9.0-macos-aarch64.zip", "archive");
        Path manifest = write("repost-schema-engine-0.9.0-checksums.json", "manifest");
        Path signature = write("repost-schema-engine-0.9.0-checksums.sig", "signature");
        Path executable = write("prepared-engine", "engine");
        Path cache = temporaryDirectory.resolve("cache");

        Path actual = GradleNativeEngineResolver.resolve(
            "Mac OS X",
            "aarch64",
            "0.9.0",
            Arrays.asList(signature, archive, manifest),
            Collections.<PublicKey>emptyList(),
            cache,
            (os, architecture, version, selectedArchive, manifestBytes, signatureBytes, keys, root) -> {
                assertEquals(archive, selectedArchive);
                assertArrayEquals("manifest".getBytes(StandardCharsets.UTF_8), manifestBytes);
                assertArrayEquals("signature".getBytes(StandardCharsets.UTF_8), signatureBytes);
                assertEquals(cache, root);
                return executable;
            }
        );

        assertEquals(executable, actual);
    }

    @Test
    void rejectsAnyExtraResolvedArtifactBeforePreparation() throws Exception {
        Path archive = write("repost-schema-engine-0.9.0-macos-aarch64.zip", "archive");
        Path manifest = write("repost-schema-engine-0.9.0-checksums.json", "manifest");
        Path signature = write("repost-schema-engine-0.9.0-checksums.sig", "signature");
        Path extra = write("unexpected.txt", "extra");

        GradleNativeEngineResolver.ResolutionException failure = assertThrows(
            GradleNativeEngineResolver.ResolutionException.class,
            () -> GradleNativeEngineResolver.resolve(
                "Mac OS X",
                "aarch64",
                "0.9.0",
                Arrays.asList(archive, manifest, signature, extra),
                Collections.<PublicKey>emptyList(),
                temporaryDirectory.resolve("cache"),
                (os, architecture, version, selectedArchive, manifestBytes, signatureBytes, keys, root) ->
                    selectedArchive
            )
        );

        assertEquals("resolved native engine artifact set does not match the exact selected coordinates", failure.getMessage());
    }

    @Test
    void offlineMissNamesEveryExactMissingCoordinateAndRemediation() {
        GradleNativeEngineResolver.ResolutionException failure = assertThrows(
            GradleNativeEngineResolver.ResolutionException.class,
            () -> GradleNativeEngineResolver.resolve(
                "Mac OS X",
                "aarch64",
                "0.9.0",
                Collections.emptyList(),
                Collections.<PublicKey>emptyList(),
                temporaryDirectory.resolve("cache"),
                true,
                (os, architecture, version, selectedArchive, manifestBytes, signatureBytes, keys, root) ->
                    selectedArchive
            )
        );

        assertEquals(
            "could not resolve native engine artifacts: " +
                "sh.repost:repost-schema-engine:0.9.0:macos-aarch64@zip, " +
                "sh.repost:repost-schema-engine:0.9.0:checksums@json, " +
                "sh.repost:repost-schema-engine:0.9.0:checksums@sig from the Gradle cache while offline; " +
                "run Gradle without --offline once to populate them",
            failure.getMessage()
        );
    }

    private Path write(String name, String content) throws Exception {
        return Files.write(temporaryDirectory.resolve(name), content.getBytes(StandardCharsets.UTF_8));
    }
}
