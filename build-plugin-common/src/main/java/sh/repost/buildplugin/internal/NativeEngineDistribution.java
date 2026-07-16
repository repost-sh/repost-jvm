package sh.repost.buildplugin.internal;

import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.security.Signature;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Shared native schema-engine classifier, integrity, and extraction boundary. */
final class NativeEngineDistribution {
    private static final int MANIFEST_FORMAT_VERSION = 1;
    private static final int MAX_MANIFEST_BYTES = 1_048_576;
    private static final Pattern VERSION = Pattern.compile("[0-9]+\\.[0-9]+\\.[0-9]+");
    private static final Pattern SHA_256 = Pattern.compile("sha256:[0-9a-f]{64}");
    private static final List<String> MANIFEST_FIELDS = Collections.unmodifiableList(Arrays.asList(
        "formatVersion",
        "engineVersion",
        "archives"
    ));
    private static final List<String> ARCHIVE_FIELDS = Collections.unmodifiableList(Arrays.asList(
        "classifier",
        "os",
        "architecture",
        "rustTarget",
        "libcStrategy",
        "executableSha256",
        "archiveSha256"
    ));
    private static final String CACHE_MARKER = ".repost-engine-manifest.sha256";
    private static final Object[] LOCAL_LOCKS = localLocks();

    private NativeEngineDistribution() {
    }

    static PreparedEngine prepare(
        Platform platform,
        String engineVersion,
        Path archive,
        byte[] manifestBytes,
        byte[] signatureBytes,
        List<PublicKey> trustedKeys,
        Path cacheRoot
    ) {
        Objects.requireNonNull(platform, "platform");
        Objects.requireNonNull(engineVersion, "engineVersion");
        Objects.requireNonNull(archive, "archive");
        Objects.requireNonNull(manifestBytes, "manifestBytes");
        Objects.requireNonNull(signatureBytes, "signatureBytes");
        Objects.requireNonNull(trustedKeys, "trustedKeys");
        Objects.requireNonNull(cacheRoot, "cacheRoot");
        if (!VERSION.matcher(engineVersion).matches()) {
            throw new DistributionException("engine version is not a pinned semantic version");
        }
        if (manifestBytes.length > MAX_MANIFEST_BYTES) {
            throw new DistributionException("native engine checksum manifest exceeds 1048576 bytes");
        }
        verifySignature(manifestBytes, signatureBytes, trustedKeys);
        ArchiveRecord record = manifestRecord(manifestBytes, engineVersion, platform);
        final String actualArchiveHash;
        try {
            actualArchiveHash = sha256(archive);
        } catch (IOException exception) {
            throw new DistributionException("could not hash native engine archive", exception);
        }
        if (!record.archiveSha256.equals(actualArchiveHash)) {
            throw new DistributionException("native engine archive checksum mismatch for " + platform.classifier);
        }

        final Path canonicalCacheRoot;
        try {
            Files.createDirectories(cacheRoot);
            canonicalCacheRoot = cacheRoot.toRealPath();
        } catch (IOException exception) {
            throw new DistributionException("could not resolve native engine cache root", exception);
        }
        Path versionRoot = canonicalCacheRoot.resolve(engineVersion);
        Path target = versionRoot.resolve(platform.classifier);
        Path lockDirectory = canonicalCacheRoot.resolve(".locks");
        Path lockPath = lockDirectory.resolve(engineVersion + "-" + platform.classifier + ".lock");
        try {
            rejectSymlink(lockDirectory, "native engine lock directory");
            rejectSymlink(versionRoot, "native engine version cache");
            rejectSymlink(target, "native engine classifier cache");
            rejectSymlink(lockPath, "native engine lock file");
            Files.createDirectories(lockDirectory);
            int localLockIndex = Math.floorMod(
                lockPath.toAbsolutePath().normalize().hashCode(),
                LOCAL_LOCKS.length
            );
            Object localLock = LOCAL_LOCKS[localLockIndex];
            synchronized (localLock) {
                try (
                    FileChannel channel = FileChannel.open(
                        lockPath,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.WRITE
                    );
                    FileLock ignored = channel.lock()
                ) {
                    Path executable = target.resolve(executableName(engineVersion, platform));
                    String manifestHash = sha256(manifestBytes);
                    if (validCache(target, executable, record.executableSha256, manifestHash)) {
                        return new PreparedEngine(platform, executable);
                    }
                    Files.createDirectories(versionRoot);
                    Path staging = versionRoot.resolve(platform.classifier + ".staging-" + UUID.randomUUID());
                    try {
                        extractExactArchive(archive, staging, engineVersion, platform, record.executableSha256);
                        Files.write(
                            staging.resolve(CACHE_MARKER),
                            (manifestHash + "\n").getBytes(java.nio.charset.StandardCharsets.US_ASCII),
                            StandardOpenOption.CREATE_NEW,
                            StandardOpenOption.WRITE
                        );
                        deleteTree(target);
                        moveDirectory(staging, target);
                    } finally {
                        deleteTree(staging);
                    }
                    return new PreparedEngine(platform, target.resolve(executableName(engineVersion, platform)));
                }
            }
        } catch (IOException exception) {
            throw new DistributionException("could not prepare native engine cache for " + platform.classifier, exception);
        }
    }

    private static boolean validCache(
        Path target,
        Path executable,
        String executableHash,
        String manifestHash
    ) throws IOException {
        if (!Files.isExecutable(executable)
            || !Files.isRegularFile(executable, LinkOption.NOFOLLOW_LINKS)
            || !Files.isRegularFile(target.resolve("LICENSE"), LinkOption.NOFOLLOW_LINKS)
            || !Files.isRegularFile(target.resolve("NOTICE"), LinkOption.NOFOLLOW_LINKS)
            || !Files.isRegularFile(target.resolve(CACHE_MARKER), LinkOption.NOFOLLOW_LINKS)
            || !hasExactCacheEntries(target, executable.getFileName().toString())) {
            return false;
        }
        String marker = new String(
            Files.readAllBytes(target.resolve(CACHE_MARKER)),
            java.nio.charset.StandardCharsets.US_ASCII
        );
        return marker.equals(manifestHash + "\n") && sha256(executable).equals(executableHash);
    }

    private static boolean hasExactCacheEntries(Path target, String executableName) throws IOException {
        Set<String> actual = new HashSet<>();
        try (java.util.stream.Stream<Path> entries = Files.list(target)) {
            entries.forEach(path -> actual.add(path.getFileName().toString()));
        }
        return actual.equals(new HashSet<>(Arrays.asList(
            CACHE_MARKER,
            "LICENSE",
            "NOTICE",
            executableName
        )));
    }

    private static void rejectSymlink(Path path, String description) {
        if (Files.isSymbolicLink(path)) {
            throw new DistributionException(description + " must not be a symbolic link");
        }
    }

    private static void extractExactArchive(
        Path archive,
        Path staging,
        String engineVersion,
        Platform platform,
        String executableHash
    ) throws IOException {
        Files.createDirectory(staging);
        String executableName = executableName(engineVersion, platform);
        Set<String> expected = new HashSet<>(Arrays.asList(executableName, "LICENSE", "NOTICE"));
        Set<String> seen = new HashSet<>();
        try (
            InputStream file = Files.newInputStream(archive);
            ZipInputStream zip = new ZipInputStream(file)
        ) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                String name = entry.getName();
                if (entry.isDirectory() || !expected.contains(name) || !seen.add(name)) {
                    throw new DistributionException("native engine archive contains an unexpected or duplicate path");
                }
                Path destination = staging.resolve(name);
                Files.copy(zip, destination, StandardCopyOption.REPLACE_EXISTING);
                zip.closeEntry();
            }
        }
        if (!seen.equals(expected)) {
            throw new DistributionException("native engine archive is missing its executable, LICENSE, or NOTICE");
        }
        Path executable = staging.resolve(executableName);
        if (!sha256(executable).equals(executableHash)) {
            throw new DistributionException("native engine executable checksum mismatch for " + platform.classifier);
        }
        setExecutable(executable);
    }

    private static void setExecutable(Path executable) throws IOException {
        try {
            Files.setPosixFilePermissions(executable, new HashSet<>(Arrays.asList(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE,
                PosixFilePermission.GROUP_READ,
                PosixFilePermission.GROUP_EXECUTE,
                PosixFilePermission.OTHERS_READ,
                PosixFilePermission.OTHERS_EXECUTE
            )));
        } catch (UnsupportedOperationException ignored) {
            if (!executable.toFile().setExecutable(true, false)) {
                throw new IOException("could not set native engine executable permission");
            }
        }
    }

    private static void moveDirectory(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(source, target);
        }
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try (java.util.stream.Stream<Path> paths = Files.walk(root)) {
            List<Path> ordered = new ArrayList<>();
            paths.forEach(ordered::add);
            Collections.reverse(ordered);
            for (Path path : ordered) {
                Files.deleteIfExists(path);
            }
        }
    }

    private static String executableName(String version, Platform platform) {
        return "repost-schema-engine-" + version + (platform.os.equals("windows") ? ".exe" : "");
    }

    private static Object[] localLocks() {
        Object[] locks = new Object[64];
        for (int index = 0; index < locks.length; index++) {
            locks[index] = new Object();
        }
        return locks;
    }

    private static void verifySignature(byte[] manifest, byte[] signature, List<PublicKey> trustedKeys) {
        if (trustedKeys.isEmpty()) {
            throw new DistributionException("native engine checksum manifest has no configured trusted signing key");
        }
        for (PublicKey key : trustedKeys) {
            if (key == null) {
                continue;
            }
            try {
                String algorithm = signatureAlgorithm(key);
                if (algorithm == null) {
                    continue;
                }
                Signature verifier = Signature.getInstance(algorithm);
                verifier.initVerify(key);
                verifier.update(manifest);
                if (verifier.verify(signature)) {
                    return;
                }
            } catch (GeneralSecurityException exception) {
                throw new DistributionException("could not verify native engine checksum manifest signature", exception);
            }
        }
        throw new DistributionException("native engine checksum manifest signature is not trusted");
    }

    private static String signatureAlgorithm(PublicKey key) {
        if ("RSA".equalsIgnoreCase(key.getAlgorithm())) {
            return "SHA256withRSA";
        }
        if ("EC".equalsIgnoreCase(key.getAlgorithm())) {
            return "SHA256withECDSA";
        }
        return null;
    }

    private static ArchiveRecord manifestRecord(byte[] manifestBytes, String engineVersion, Platform platform) {
        Map<String, Object> root = object(StrictJson.parse(manifestBytes), "checksum manifest");
        exactFields(root, MANIFEST_FIELDS, "checksum manifest");
        exactInteger(root.get("formatVersion"), MANIFEST_FORMAT_VERSION, "formatVersion");
        if (!engineVersion.equals(nonEmptyString(root.get("engineVersion"), "engineVersion"))) {
            throw new DistributionException("native engine checksum manifest version does not match the requested version");
        }
        List<Object> archives = array(root.get("archives"), "archives");
        ArchiveRecord selected = null;
        Set<String> classifiers = new HashSet<>();
        for (int index = 0; index < archives.size(); index++) {
            String location = "archives[" + index + "]";
            Map<String, Object> value = object(archives.get(index), location);
            exactFields(value, ARCHIVE_FIELDS, location);
            ArchiveRecord record = new ArchiveRecord(
                nonEmptyString(value.get("classifier"), location + ".classifier"),
                nonEmptyString(value.get("os"), location + ".os"),
                nonEmptyString(value.get("architecture"), location + ".architecture"),
                nonEmptyString(value.get("rustTarget"), location + ".rustTarget"),
                nonEmptyString(value.get("libcStrategy"), location + ".libcStrategy"),
                hash(value.get("executableSha256"), location + ".executableSha256"),
                hash(value.get("archiveSha256"), location + ".archiveSha256")
            );
            if (!classifiers.add(record.classifier)) {
                throw new DistributionException("native engine checksum manifest has a duplicate classifier");
            }
            if (platform.classifier.equals(record.classifier)) {
                selected = record;
            }
        }
        if (selected == null) {
            throw new DistributionException("native engine checksum manifest has no entry for " + platform.classifier);
        }
        if (!platform.os.equals(selected.os)
            || !platform.architecture.equals(selected.architecture)
            || !platform.rustTarget.equals(selected.rustTarget)
            || !platform.libcStrategy.equals(selected.libcStrategy)) {
            throw new DistributionException("native engine checksum manifest target metadata does not match " + platform.classifier);
        }
        return selected;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object value, String location) {
        if (!(value instanceof Map)) {
            throw new DistributionException(location + " must be an object");
        }
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> array(Object value, String location) {
        if (!(value instanceof List)) {
            throw new DistributionException(location + " must be an array");
        }
        return (List<Object>) value;
    }

    private static void exactFields(Map<String, Object> value, List<String> expected, String location) {
        if (!new ArrayList<>(value.keySet()).equals(expected)) {
            throw new DistributionException(location + " has unknown, missing, or reordered fields");
        }
    }

    private static void exactInteger(Object value, long expected, String location) {
        if (!(value instanceof Long) || ((Long) value).longValue() != expected) {
            throw new DistributionException(location + " must equal " + expected);
        }
    }

    private static String nonEmptyString(Object value, String location) {
        if (!(value instanceof String) || ((String) value).isEmpty()) {
            throw new DistributionException(location + " must be a non-empty string");
        }
        return (String) value;
    }

    private static String hash(Object value, String location) {
        String hash = nonEmptyString(value, location);
        if (!SHA_256.matcher(hash).matches()) {
            throw new DistributionException(location + " must be a canonical SHA-256 digest");
        }
        return hash;
    }

    static String sha256(byte[] bytes) {
        MessageDigest digest = newDigest();
        return "sha256:" + hex(digest.digest(bytes));
    }

    private static String sha256(Path path) throws IOException {
        MessageDigest digest = newDigest();
        byte[] buffer = new byte[16 * 1024];
        try (InputStream input = Files.newInputStream(path)) {
            int read;
            while ((read = input.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
        }
        return "sha256:" + hex(digest.digest());
    }

    private static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new AssertionError("Java must provide SHA-256", exception);
        }
    }

    private static String hex(byte[] bytes) {
        char[] result = new char[bytes.length * 2];
        char[] alphabet = "0123456789abcdef".toCharArray();
        for (int index = 0; index < bytes.length; index++) {
            int value = bytes[index] & 0xff;
            result[index * 2] = alphabet[value >>> 4];
            result[index * 2 + 1] = alphabet[value & 0x0f];
        }
        return new String(result);
    }

    static final class Platform {
        private final String classifier;
        private final String os;
        private final String architecture;
        private final String rustTarget;
        private final String libcStrategy;

        private Platform(
            String classifier,
            String os,
            String architecture,
            String rustTarget,
            String libcStrategy
        ) {
            this.classifier = classifier;
            this.os = os;
            this.architecture = architecture;
            this.rustTarget = rustTarget;
            this.libcStrategy = libcStrategy;
        }

        static Platform resolve(String osName, String architectureName) {
            String os = normalizeOs(osName);
            String architecture = normalizeArchitecture(architectureName);
            if ("linux".equals(os) && "x86_64".equals(architecture)) {
                return new Platform("linux-x86_64", os, architecture, "x86_64-unknown-linux-musl", "static-musl");
            }
            if ("linux".equals(os) && "aarch64".equals(architecture)) {
                return new Platform("linux-aarch64", os, architecture, "aarch64-unknown-linux-musl", "static-musl");
            }
            if ("macos".equals(os) && "x86_64".equals(architecture)) {
                return new Platform("macos-x86_64", os, architecture, "x86_64-apple-darwin", "system");
            }
            if ("macos".equals(os) && "aarch64".equals(architecture)) {
                return new Platform("macos-aarch64", os, architecture, "aarch64-apple-darwin", "system");
            }
            if ("windows".equals(os) && "x86_64".equals(architecture)) {
                return new Platform("windows-x86_64", os, architecture, "x86_64-pc-windows-msvc", "msvc");
            }
            throw new DistributionException(
                "no Repost schema engine classifier supports os=" + osName + ", architecture=" + architectureName
            );
        }

        private static String normalizeOs(String value) {
            String normalized = Objects.requireNonNull(value, "osName").toLowerCase(Locale.ROOT);
            if (normalized.contains("linux")) {
                return "linux";
            }
            if (normalized.contains("mac") || normalized.contains("darwin")) {
                return "macos";
            }
            if (normalized.contains("windows")) {
                return "windows";
            }
            return normalized;
        }

        private static String normalizeArchitecture(String value) {
            String normalized = Objects.requireNonNull(value, "architectureName").toLowerCase(Locale.ROOT);
            if ("amd64".equals(normalized) || "x86_64".equals(normalized)) {
                return "x86_64";
            }
            if ("arm64".equals(normalized) || "aarch64".equals(normalized)) {
                return "aarch64";
            }
            return normalized;
        }

        String classifier() {
            return classifier;
        }

        String os() {
            return os;
        }

        String architecture() {
            return architecture;
        }

        String rustTarget() {
            return rustTarget;
        }

        String libcStrategy() {
            return libcStrategy;
        }
    }

    static final class PreparedEngine {
        private final Platform platform;
        private final Path executable;

        private PreparedEngine(Platform platform, Path executable) {
            this.platform = platform;
            this.executable = executable;
        }

        Platform platform() {
            return platform;
        }

        Path executable() {
            return executable;
        }
    }

    private static final class ArchiveRecord {
        private final String classifier;
        private final String os;
        private final String architecture;
        private final String rustTarget;
        private final String libcStrategy;
        private final String executableSha256;
        private final String archiveSha256;

        private ArchiveRecord(
            String classifier,
            String os,
            String architecture,
            String rustTarget,
            String libcStrategy,
            String executableSha256,
            String archiveSha256
        ) {
            this.classifier = classifier;
            this.os = os;
            this.architecture = architecture;
            this.rustTarget = rustTarget;
            this.libcStrategy = libcStrategy;
            this.executableSha256 = executableSha256;
            this.archiveSha256 = archiveSha256;
        }
    }

    static final class DistributionException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        DistributionException(String message) {
            super(message);
        }

        DistributionException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
