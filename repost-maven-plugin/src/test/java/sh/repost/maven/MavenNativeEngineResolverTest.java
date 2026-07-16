package sh.repost.maven;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.PublicKey;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class MavenNativeEngineResolverTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void resolvesTheExactClassifierManifestAndSignatureBeforePreparing() throws Exception {
        Path archive = write("engine.zip", "archive");
        Path manifest = write("checksums.json", "manifest");
        Path signature = write("checksums.sig", "signature");
        Path cache = temporaryDirectory.resolve("cache");
        Path executable = write("prepared-engine", "engine");
        List<String> requested = new ArrayList<>();

        MavenNativeEngineResolver.ArtifactSource source = coordinate -> {
            requested.add(coordinate.toString());
            if ("zip".equals(coordinate.extension())) {
                return archive;
            }
            if ("json".equals(coordinate.extension())) {
                return manifest;
            }
            return signature;
        };
        MavenNativeEngineResolver.EnginePreparer preparer = (
            osName,
            architectureName,
            version,
            resolvedArchive,
            manifestBytes,
            signatureBytes,
            trustedKeys,
            cacheRoot
        ) -> {
            assertEquals("Mac OS X", osName);
            assertEquals("aarch64", architectureName);
            assertEquals("0.9.0", version);
            assertEquals(archive, resolvedArchive);
            assertArrayEquals("manifest".getBytes(StandardCharsets.UTF_8), manifestBytes);
            assertArrayEquals("signature".getBytes(StandardCharsets.UTF_8), signatureBytes);
            assertEquals(Collections.emptyList(), trustedKeys);
            assertEquals(cache, cacheRoot);
            return executable;
        };

        Path resolved = MavenNativeEngineResolver.resolve(
            "Mac OS X",
            "aarch64",
            "0.9.0",
            source,
            Collections.<PublicKey>emptyList(),
            cache,
            preparer
        );

        assertEquals(executable, resolved);
        assertEquals(List.of(
            "sh.repost:repost-schema-engine:zip:macos-aarch64:0.9.0",
            "sh.repost:repost-schema-engine:json:checksums:0.9.0",
            "sh.repost:repost-schema-engine:sig:checksums:0.9.0"
        ), requested);
    }

    private Path write(String name, String content) throws Exception {
        return Files.write(temporaryDirectory.resolve(name), content.getBytes(StandardCharsets.UTF_8));
    }
}
