package sh.repost.buildplugin.internal;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.security.Signature;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class NativeEngineDistributionTest {
    @TempDir
    Path temporary;

    @Test
    void selectsOnlyTheFiveApprovedClassifiers() {
        assertPlatform("Linux", "amd64", "linux-x86_64", "x86_64-unknown-linux-musl", "static-musl");
        assertPlatform("linux", "aarch64", "linux-aarch64", "aarch64-unknown-linux-musl", "static-musl");
        assertPlatform("Mac OS X", "x86_64", "macos-x86_64", "x86_64-apple-darwin", "system");
        assertPlatform("Darwin", "arm64", "macos-aarch64", "aarch64-apple-darwin", "system");
        assertPlatform("Windows 11", "amd64", "windows-x86_64", "x86_64-pc-windows-msvc", "msvc");

        assertThrows(
            NativeEngineDistribution.DistributionException.class,
            () -> NativeEngineDistribution.Platform.resolve("Linux", "riscv64")
        );
        assertThrows(
            NativeEngineDistribution.DistributionException.class,
            () -> NativeEngineDistribution.Platform.resolve("Windows 11", "arm64")
        );
    }

    @Test
    void verifiesSignatureAndHashesBeforeExactArchiveExtraction() throws Exception {
        NativeEngineDistribution.Platform platform =
            NativeEngineDistribution.Platform.resolve("Linux", "amd64");
        byte[] executable = "native-engine".getBytes(StandardCharsets.UTF_8);
        Path archive = temporary.resolve("engine.zip");
        Files.write(archive, archive("repost-schema-engine-0.9.0", executable, false));
        byte[] manifest = manifest(platform, executable, Files.readAllBytes(archive));
        KeyPair signingKey = KeyPairGenerator.getInstance("RSA").generateKeyPair();

        NativeEngineDistribution.PreparedEngine prepared = NativeEngineDistribution.prepare(
            platform,
            "0.9.0",
            archive,
            manifest,
            sign(manifest, signingKey),
            Collections.singletonList(signingKey.getPublic()),
            temporary.resolve("cache")
        );

        assertEquals(platform.classifier(), prepared.platform().classifier());
        assertArrayEquals(executable, Files.readAllBytes(prepared.executable()));
        assertTrue(Files.isExecutable(prepared.executable()));
        assertTrue(prepared.executable().startsWith(temporary.resolve("cache").toRealPath()));
        long modified = Files.getLastModifiedTime(prepared.executable()).toMillis();

        NativeEngineDistribution.PreparedEngine repeated = NativeEngineDistribution.prepare(
            platform,
            "0.9.0",
            archive,
            manifest,
            sign(manifest, signingKey),
            Collections.singletonList(signingKey.getPublic()),
            temporary.resolve("cache")
        );
        assertEquals(prepared.executable(), repeated.executable());
        assertEquals(modified, Files.getLastModifiedTime(repeated.executable()).toMillis());
    }

    @Test
    void exposesOneSharedBuildToolFacadeForClassifierAndPreparedExecutable() throws Exception {
        assertEquals("macos-aarch64", NativeEngineArtifacts.classifier("Mac OS X", "arm64"));
        NativeEngineDistribution.Platform platform =
            NativeEngineDistribution.Platform.resolve("Linux", "amd64");
        byte[] executable = "shared-native-engine".getBytes(StandardCharsets.UTF_8);
        Path archive = temporary.resolve("shared-engine.zip");
        Files.write(archive, archive("repost-schema-engine-0.9.0", executable, false));
        byte[] manifest = manifest(platform, executable, Files.readAllBytes(archive));
        KeyPair signingKey = KeyPairGenerator.getInstance("RSA").generateKeyPair();

        NativeEngineArtifacts.Prepared prepared = NativeEngineArtifacts.prepare(
            "Linux",
            "amd64",
            "0.9.0",
            archive,
            manifest,
            sign(manifest, signingKey),
            Collections.singletonList(signingKey.getPublic()),
            temporary.resolve("shared-cache")
        );

        assertEquals("linux-x86_64", prepared.getClassifier());
        assertArrayEquals(executable, Files.readAllBytes(prepared.getExecutable()));
    }

    @Test
    void skipsUnsupportedRotatedKeysWhenALaterTrustedKeyVerifies() throws Exception {
        NativeEngineDistribution.Platform platform =
            NativeEngineDistribution.Platform.resolve("Linux", "amd64");
        byte[] executable = "native-engine".getBytes(StandardCharsets.UTF_8);
        Path archive = temporary.resolve("rotated-key.zip");
        Files.write(archive, archive("repost-schema-engine-0.9.0", executable, false));
        byte[] manifest = manifest(platform, executable, Files.readAllBytes(archive));
        KeyPair signingKey = KeyPairGenerator.getInstance("RSA").generateKeyPair();
        PublicKey retiredAlgorithm = new PublicKey() {
            @Override
            public String getAlgorithm() {
                return "retired";
            }

            @Override
            public String getFormat() {
                return "none";
            }

            @Override
            public byte[] getEncoded() {
                return new byte[0];
            }
        };

        NativeEngineDistribution.PreparedEngine prepared = NativeEngineDistribution.prepare(
            platform,
            "0.9.0",
            archive,
            manifest,
            sign(manifest, signingKey),
            Arrays.asList(retiredAlgorithm, signingKey.getPublic()),
            temporary.resolve("rotated-key-cache")
        );

        assertArrayEquals(executable, Files.readAllBytes(prepared.executable()));
    }

    @Test
    void rejectsBadSignatureHashMetadataAndExtraArchivePathsBeforePublishingCache() throws Exception {
        NativeEngineDistribution.Platform platform =
            NativeEngineDistribution.Platform.resolve("Linux", "amd64");
        byte[] executable = "native-engine".getBytes(StandardCharsets.UTF_8);
        KeyPair signingKey = KeyPairGenerator.getInstance("RSA").generateKeyPair();
        KeyPair wrongKey = KeyPairGenerator.getInstance("RSA").generateKeyPair();

        Path validArchive = temporary.resolve("valid.zip");
        Files.write(validArchive, archive("repost-schema-engine-0.9.0", executable, false));
        byte[] validManifest = manifest(platform, executable, Files.readAllBytes(validArchive));
        assertRejected(platform, validArchive, validManifest, sign(validManifest, wrongKey), signingKey);

        byte[] wrongTriple = new String(validManifest, StandardCharsets.UTF_8)
            .replace("x86_64-unknown-linux-musl", "x86_64-unknown-linux-gnu")
            .getBytes(StandardCharsets.UTF_8);
        assertRejected(platform, validArchive, wrongTriple, sign(wrongTriple, signingKey), signingKey);

        Path extraArchive = temporary.resolve("extra.zip");
        Files.write(extraArchive, archive("repost-schema-engine-0.9.0", executable, true));
        byte[] extraManifest = manifest(platform, executable, Files.readAllBytes(extraArchive));
        assertRejected(platform, extraArchive, extraManifest, sign(extraManifest, signingKey), signingKey);

        assertFalse(Files.exists(temporary.resolve("rejected-cache/0.9.0/linux-x86_64")));
    }

    @Test
    void serializesConcurrentPreparationWithinOneBuildJvm() throws Exception {
        NativeEngineDistribution.Platform platform =
            NativeEngineDistribution.Platform.resolve("Linux", "amd64");
        byte[] executable = "native-engine".getBytes(StandardCharsets.UTF_8);
        Path archive = temporary.resolve("parallel.zip");
        Files.write(archive, archive("repost-schema-engine-0.9.0", executable, false));
        byte[] manifest = manifest(platform, executable, Files.readAllBytes(archive));
        KeyPair signingKey = KeyPairGenerator.getInstance("RSA").generateKeyPair();
        byte[] signature = sign(manifest, signingKey);
        Path cache = temporary.resolve("parallel-cache");
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            java.util.concurrent.Callable<Path> prepare = () -> {
                start.await();
                return NativeEngineDistribution.prepare(
                    platform,
                    "0.9.0",
                    archive,
                    manifest,
                    signature,
                    Collections.singletonList(signingKey.getPublic()),
                    cache
                ).executable();
            };
            Future<Path> first = workers.submit(prepare);
            Future<Path> second = workers.submit(prepare);
            start.countDown();
            assertEquals(first.get(), second.get());
            assertArrayEquals(executable, Files.readAllBytes(first.get()));
        } finally {
            workers.shutdownNow();
        }
    }

    @Test
    void rejectsAClassifierCacheThatIsReplacedByASymbolicLink() throws Exception {
        NativeEngineDistribution.Platform platform =
            NativeEngineDistribution.Platform.resolve("Linux", "amd64");
        byte[] executable = "native-engine".getBytes(StandardCharsets.UTF_8);
        Path archive = temporary.resolve("linked-cache.zip");
        Files.write(archive, archive("repost-schema-engine-0.9.0", executable, false));
        byte[] manifest = manifest(platform, executable, Files.readAllBytes(archive));
        KeyPair signingKey = KeyPairGenerator.getInstance("RSA").generateKeyPair();
        byte[] signature = sign(manifest, signingKey);
        Path trustedCache = temporary.resolve("trusted-cache");
        Path prepared = NativeEngineDistribution.prepare(
            platform,
            "0.9.0",
            archive,
            manifest,
            signature,
            Collections.singletonList(signingKey.getPublic()),
            trustedCache
        ).executable();
        Path linkedCache = temporary.resolve("linked-cache-root");
        Path versionRoot = linkedCache.resolve("0.9.0");
        Files.createDirectories(versionRoot);
        Files.createSymbolicLink(versionRoot.resolve("linux-x86_64"), prepared.getParent());

        assertThrows(
            NativeEngineDistribution.DistributionException.class,
            () -> NativeEngineDistribution.prepare(
                platform,
                "0.9.0",
                archive,
                manifest,
                signature,
                Collections.singletonList(signingKey.getPublic()),
                linkedCache
            )
        );
    }

    private void assertRejected(
        NativeEngineDistribution.Platform platform,
        Path archive,
        byte[] manifest,
        byte[] signature,
        KeyPair trusted
    ) {
        assertThrows(
            NativeEngineDistribution.DistributionException.class,
            () -> NativeEngineDistribution.prepare(
                platform,
                "0.9.0",
                archive,
                manifest,
                signature,
                Collections.singletonList(trusted.getPublic()),
                temporary.resolve("rejected-cache")
            )
        );
    }

    private static void assertPlatform(
        String os,
        String architecture,
        String classifier,
        String rustTarget,
        String libcStrategy
    ) {
        NativeEngineDistribution.Platform platform = NativeEngineDistribution.Platform.resolve(os, architecture);
        assertEquals(classifier, platform.classifier());
        assertEquals(rustTarget, platform.rustTarget());
        assertEquals(libcStrategy, platform.libcStrategy());
    }

    private static byte[] archive(String executableName, byte[] executable, boolean extra) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            entry(zip, executableName, executable);
            entry(zip, "LICENSE", "license".getBytes(StandardCharsets.UTF_8));
            entry(zip, "NOTICE", "notice".getBytes(StandardCharsets.UTF_8));
            if (extra) {
                entry(zip, "unexpected", new byte[] {1});
            }
        }
        return bytes.toByteArray();
    }

    private static void entry(ZipOutputStream zip, String name, byte[] contents) throws IOException {
        ZipEntry entry = new ZipEntry(name);
        entry.setTime(0L);
        zip.putNextEntry(entry);
        zip.write(contents);
        zip.closeEntry();
    }

    private static byte[] manifest(
        NativeEngineDistribution.Platform platform,
        byte[] executable,
        byte[] archive
    ) {
        String json = "{\"formatVersion\":1,\"engineVersion\":\"0.9.0\",\"archives\":[{"
            + "\"classifier\":\"" + platform.classifier() + "\","
            + "\"os\":\"" + platform.os() + "\","
            + "\"architecture\":\"" + platform.architecture() + "\","
            + "\"rustTarget\":\"" + platform.rustTarget() + "\","
            + "\"libcStrategy\":\"" + platform.libcStrategy() + "\","
            + "\"executableSha256\":\"" + NativeEngineDistribution.sha256(executable) + "\","
            + "\"archiveSha256\":\"" + NativeEngineDistribution.sha256(archive) + "\"}]}";
        return json.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] sign(byte[] manifest, KeyPair keyPair) throws Exception {
        Signature signer = Signature.getInstance("SHA256withRSA");
        signer.initSign(keyPair.getPrivate());
        signer.update(manifest);
        return signer.sign();
    }
}
