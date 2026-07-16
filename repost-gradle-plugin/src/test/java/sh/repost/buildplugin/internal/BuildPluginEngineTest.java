package sh.repost.buildplugin.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class BuildPluginEngineTest {
    @TempDir
    Path temporary;

    @Test
    void verifiesCapabilitiesThenExecutesTheClosedGenerationRequest() {
        BuildPluginEngine.Request request = request();
        SequenceStarter starter = new SequenceStarter(validGenerationResponse());

        BuildPluginEngine.execute(request, starter);

        assertEquals(2, starter.commands.size());
        assertEquals(
            List.of(
                Path.of("/opt/repost/repost-schema-engine").toAbsolutePath().normalize().toString(),
                "capabilities",
                "--json"
            ),
            starter.commands.get(0)
        );
        assertEquals("generate", starter.commands.get(1).get(1));
        assertEquals("--request", starter.commands.get(1).get(2));
        assertTrue(starter.requestContents.contains("\"buildIdentity\":{\"kind\":\"GRADLE\""));
        assertTrue(starter.requestContents.contains("\"environmentInputs\":{\"PUBLIC_NAME\":\"orders\"}"));
        assertFalse(Files.exists(Path.of(starter.commands.get(1).get(3))));
    }

    @Test
    void rejectsGenerationResponseSkewAndUnexpectedOutputRoots() {
        for (String response : List.of(
            validGenerationResponse().replace("\"engineVersion\":\"0.9.0\"", "\"engineVersion\":\"0.9.1\""),
            validGenerationResponse().replace("\"runtimeVersion\":\"1.0.0\"", "\"runtimeVersion\":\"2.0.0\""),
            validGenerationResponse().replace("/workspace/build/generated/java", "/remote/redirected/source")
        )) {
            assertThrows(
                BuildPluginEngine.EngineException.class,
                () -> BuildPluginEngine.execute(request(), new SequenceStarter(response))
            );
        }
    }

    @Test
    void preservesStructuredSchemaDiagnosticsFromNonzeroGeneration() {
        BuildPluginEngine.EngineException failure = assertThrows(
            BuildPluginEngine.EngineException.class,
            () -> BuildPluginEngine.execute(request(), new SequenceStarter(errorGenerationResponse(), 1))
        );

        assertTrue(failure.getMessage().contains("TARGET_COLLISION"));
        assertTrue(failure.getMessage().contains("duplicate generated client FQCN"));
    }

    @Test
    void carriesClosedMavenAndGradleBuildIdentitiesThroughTheSharedExecutor() {
        SequenceStarter gradle = new SequenceStarter(validGenerationResponse());
        BuildPluginEngine.execute(request(BuildPluginEngine.BuildIdentity.gradle("com.acme", "orders", ":app")), gradle);
        assertTrue(gradle.requestContents.contains(
            "\"buildIdentity\":{\"kind\":\"GRADLE\",\"group\":\"com.acme\"," +
                "\"rootProjectName\":\"orders\",\"projectPath\":\":app\"}"
        ));

        SequenceStarter maven = new SequenceStarter(validGenerationResponse());
        BuildPluginEngine.execute(request(BuildPluginEngine.BuildIdentity.maven("com.acme", "orders")), maven);
        assertTrue(maven.requestContents.contains(
            "\"buildIdentity\":{\"kind\":\"MAVEN\",\"groupId\":\"com.acme\"," +
                "\"artifactId\":\"orders\"}"
        ));
        assertFalse(maven.requestContents.contains("\"projectPath\""));
    }

    @Test
    void checkExecutesTwiceAndRequiresIdenticalMarkersAndTreeHashes() {
        BuildPluginEngine.Request request = checkRequest();
        DeterminismStarter starter = new DeterminismStarter(false, false);

        BuildPluginEngine.executeDeterminismCheck(request, starter);

        assertEquals(3, starter.commands.size());
        assertEquals(2, starter.requests.size());
        assertTrue(starter.requests.get(0).contains("\"checkMode\":\"CHECK\""));
        assertTrue(starter.requests.get(1).contains("\"checkMode\":\"CHECK\""));
        assertFalse(starter.sourceRoots.get(0).equals(starter.sourceRoots.get(1)));
        assertFalse(starter.resourceRoots.get(0).equals(starter.resourceRoots.get(1)));
        assertFalse(Files.exists(temporary.resolve("requests/determinism-replica")));
        assertTrue(Files.isRegularFile(starter.sourceMarker(starter.sourceRoots.get(0))));
        assertTrue(Files.isRegularFile(starter.resourceMarker(starter.resourceRoots.get(0))));
    }

    @Test
    void checkRejectsTreeHashOrMarkerDriftWithOneSanitizedDiagnostic() {
        for (DeterminismStarter starter : List.of(
            new DeterminismStarter(true, false),
            new DeterminismStarter(false, true)
        )) {
            BuildPluginEngine.EngineException failure = assertThrows(
                BuildPluginEngine.EngineException.class,
                () -> BuildPluginEngine.executeDeterminismCheck(checkRequest(), starter)
            );

            assertEquals("Repost generation is nondeterministic for generator javaSdk", failure.getMessage());
            assertFalse(Files.exists(temporary.resolve("requests/determinism-replica")));
        }
    }

    @Test
    void checkRejectsUnsafeMarkerPackagePathsWithoutEscapingTheOutputRoot() {
        BuildPluginEngine.EngineException failure = assertThrows(
            BuildPluginEngine.EngineException.class,
            () -> BuildPluginEngine.executeDeterminismCheck(
                checkRequest(),
                new DeterminismStarter(false, false, true)
            )
        );

        assertEquals("Repost deterministic check could not read generated markers", failure.getMessage());
        assertFalse(Files.exists(temporary.resolve("requests/determinism-replica")));
    }

    @Test
    void verifiesRestoredMarkersAndTreesWithoutChangingMtimesOrTrustingStaleFiles() throws Exception {
        BuildPluginEngine.Generator generator = cachedGenerator();
        Path sourceFile = Path.of(generator.getSourceOutputDirectory())
            .resolve("com/acme/repost/Order.java");
        Path resourceFile = Path.of(generator.getResourceOutputDirectory())
            .resolve("META-INF/repost/generated-clients/v1/0123456789abcdef/client.json");
        writeManagedTree(generator, sourceFile, resourceFile);
        FileTime sourceMtime = Files.getLastModifiedTime(sourceFile);
        FileTime resourceMtime = Files.getLastModifiedTime(resourceFile);

        BuildPluginEngine.verifyGeneratedOutputs(Collections.singletonList(generator));

        assertEquals(sourceMtime, Files.getLastModifiedTime(sourceFile));
        assertEquals(resourceMtime, Files.getLastModifiedTime(resourceFile));

        Path stale = sourceFile.resolveSibling("Stale.java");
        Files.writeString(stale, "stale\n", StandardCharsets.UTF_8);
        BuildPluginEngine.EngineException staleFailure = assertThrows(
            BuildPluginEngine.EngineException.class,
            () -> BuildPluginEngine.verifyGeneratedOutputs(Collections.singletonList(generator))
        );
        assertEquals("Repost generated output contains files not listed by its marker", staleFailure.getMessage());

        Files.delete(stale);
        Files.writeString(sourceFile, "tampered\n", StandardCharsets.UTF_8);
        BuildPluginEngine.EngineException hashFailure = assertThrows(
            BuildPluginEngine.EngineException.class,
            () -> BuildPluginEngine.verifyGeneratedOutputs(Collections.singletonList(generator))
        );
        assertEquals("Repost generated output tree hash does not match its marker", hashFailure.getMessage());
    }

    private BuildPluginEngine.Request request() {
        return request(BuildPluginEngine.BuildIdentity.gradle("com.acme", "orders", ":app"));
    }

    private BuildPluginEngine.Request request(BuildPluginEngine.BuildIdentity buildIdentity) {
        return new BuildPluginEngine.Request(
            Path.of("/opt/repost/repost-schema-engine"),
            "1.0.0",
            "0.9.0",
            "1.0.0",
            "/workspace/repost/schema.repost",
            Collections.singletonList(new BuildPluginEngine.Generator(
                "javaSdk",
                "/workspace/build/generated/java",
                "/workspace/build/generated/resources",
                "/workspace/build/state/java-source",
                "/workspace/build/state/java-resource"
            )),
            Collections.singletonMap("PUBLIC_NAME", "orders"),
            buildIdentity,
            false,
            temporary,
            Duration.ofSeconds(5)
        );
    }

    private BuildPluginEngine.Request checkRequest() {
        return new BuildPluginEngine.Request(
            Path.of("/opt/repost/repost-schema-engine"),
            "1.0.0",
            "0.9.0",
            "1.0.0",
            "/workspace/repost/schema.repost",
            Collections.singletonList(new BuildPluginEngine.Generator(
                "javaSdk",
                temporary.resolve("check-output/source").toString(),
                temporary.resolve("check-output/resource").toString(),
                temporary.resolve("check-control/source").toString(),
                temporary.resolve("check-control/resource").toString()
            )),
            Collections.singletonMap("PUBLIC_NAME", "orders"),
            BuildPluginEngine.BuildIdentity.gradle("com.acme", "orders", ":app"),
            true,
            temporary.resolve("requests"),
            Duration.ofSeconds(5)
        );
    }

    private BuildPluginEngine.Generator cachedGenerator() {
        return new BuildPluginEngine.Generator(
            "javaSdk",
            temporary.resolve("cached/source").toString(),
            temporary.resolve("cached/resource").toString(),
            temporary.resolve("state/source").toString(),
            temporary.resolve("state/resource").toString()
        );
    }

    private static void writeManagedTree(
        BuildPluginEngine.Generator generator,
        Path sourceFile,
        Path resourceFile
    ) throws Exception {
        Files.createDirectories(sourceFile.getParent());
        Files.createDirectories(resourceFile.getParent());
        String source = "final class Order {}\n";
        String resource = "{}\n";
        Files.writeString(sourceFile, source, StandardCharsets.UTF_8);
        Files.writeString(resourceFile, resource, StandardCharsets.UTF_8);
        Files.writeString(
            sourceFile.getParent().resolve(".repost-client.json"),
            marker("source", "Order.java", source),
            StandardCharsets.UTF_8
        );
        Files.writeString(
            resourceFile.getParent().resolve(".repost-client.json"),
            marker("registry", "client.json", resource),
            StandardCharsets.UTF_8
        );
    }

    private static String marker(String outputKind, String file, String contents) {
        return "{\n" +
            "  \"formatVersion\": 2,\n" +
            "  \"language\": \"java\",\n" +
            "  \"engineVersion\": \"0.9.0\",\n" +
            "  \"generator\": \"javaSdk\",\n" +
            "  \"outputKind\": \"" + outputKind + "\",\n" +
            "  \"generatorId\": \"0123456789abcdef\",\n" +
            "  \"packageName\": \"com.acme.repost\",\n" +
            "  \"schemaHash\": \"sha256:" + "c".repeat(64) + "\",\n" +
            "  \"runtimeVersion\": \"1.0.0\",\n" +
            "  \"descriptorVersion\": 2,\n" +
            "  \"clientName\": \"RepostClient\",\n" +
            "  \"regenerateCommand\": \"repost schema generate\",\n" +
            "  \"treeHash\": \"" + treeHash(file, contents) + "\",\n" +
            "  \"files\": [\n" +
            "    \"" + file + "\"\n" +
            "  ]\n" +
            "}\n";
    }

    private static String treeHash(String file, String contents) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            hashPart(digest, file.getBytes(StandardCharsets.UTF_8));
            hashPart(digest, contents.getBytes(StandardCharsets.UTF_8));
            StringBuilder hash = new StringBuilder("sha256:");
            for (byte value : digest.digest()) {
                hash.append(String.format("%02x", value & 0xff));
            }
            return hash.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static void hashPart(MessageDigest digest, byte[] bytes) {
        digest.update(ByteBuffer.allocate(8).putLong(bytes.length).array());
        digest.update(bytes);
    }

    private static String validCapabilities() {
        return "{\"protocolVersion\":1,\"engineVersion\":\"0.9.0\"," +
            "\"runtimeVersion\":\"1.0.0\",\"descriptorVersion\":2}";
    }

    private static String validGenerationResponse() {
        return "{\"protocolVersion\":1,\"engineVersion\":\"0.9.0\"," +
            "\"runtimeVersion\":\"1.0.0\",\"descriptorVersion\":2,\"generators\":[{" +
            "\"generatorName\":\"javaSdk\",\"generatorId\":\"0123456789abcdef\"," +
            "\"sourceRoot\":\"/workspace/build/generated/java\"," +
            "\"resourceRoot\":\"/workspace/build/generated/resources\"," +
            "\"sourceTreeHash\":\"sha256:" + "a".repeat(64) + "\"," +
            "\"resourceTreeHash\":\"sha256:" + "b".repeat(64) + "\"}],\"diagnostics\":[]}";
    }

    private static String errorGenerationResponse() {
        return "{\"protocolVersion\":1,\"engineVersion\":\"0.9.0\"," +
            "\"runtimeVersion\":\"1.0.0\",\"descriptorVersion\":2," +
            "\"generators\":[],\"diagnostics\":[{\"severity\":\"ERROR\"," +
            "\"code\":\"TARGET_COLLISION\",\"message\":\"duplicate generated client FQCN; " +
            "set a unique packageName or clientName\"}]}";
    }

    private static String generationResponse(Path source, Path resource, String sourceHash) {
        return "{\"protocolVersion\":1,\"engineVersion\":\"0.9.0\"," +
            "\"runtimeVersion\":\"1.0.0\",\"descriptorVersion\":2,\"generators\":[{" +
            "\"generatorName\":\"javaSdk\",\"generatorId\":\"0123456789abcdef\"," +
            "\"sourceRoot\":\"" + json(source) + "\"," +
            "\"resourceRoot\":\"" + json(resource) + "\"," +
            "\"sourceTreeHash\":\"sha256:" + sourceHash + "\"," +
            "\"resourceTreeHash\":\"sha256:" + "b".repeat(64) + "\"}],\"diagnostics\":[]}";
    }

    private static String json(Path path) {
        return path.toString().replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static final class SequenceStarter implements NativeEngineProcess.ProcessStarter {
        private final String generationResponse;
        private final int generationExitCode;
        private final java.util.ArrayList<List<String>> commands = new java.util.ArrayList<>();
        private String requestContents = "";

        private SequenceStarter(String generationResponse) {
            this(generationResponse, 0);
        }

        private SequenceStarter(String generationResponse, int generationExitCode) {
            this.generationResponse = generationResponse;
            this.generationExitCode = generationExitCode;
        }

        @Override
        public Process start(List<String> command) throws java.io.IOException {
            commands.add(List.copyOf(command));
            if ("capabilities".equals(command.get(1))) {
                return new CompletedProcess(validCapabilities());
            }
            Path requestFile = Path.of(command.get(3));
            requestContents = Files.readString(requestFile, StandardCharsets.UTF_8);
            return new CompletedProcess(generationResponse, generationExitCode);
        }
    }

    private static final class DeterminismStarter implements NativeEngineProcess.ProcessStarter {
        private static final Pattern SOURCE = Pattern.compile("\\\"sourceOutputDirectory\\\":\\\"([^\\\"]+)\\\"");
        private static final Pattern RESOURCE = Pattern.compile(
            "\\\"resourceOutputDirectory\\\":\\\"([^\\\"]+)\\\""
        );

        private final boolean driftHash;
        private final boolean driftMarker;
        private final boolean unsafePackage;
        private final ArrayList<List<String>> commands = new ArrayList<>();
        private final ArrayList<String> requests = new ArrayList<>();
        private final ArrayList<Path> sourceRoots = new ArrayList<>();
        private final ArrayList<Path> resourceRoots = new ArrayList<>();

        private DeterminismStarter(boolean driftHash, boolean driftMarker) {
            this(driftHash, driftMarker, false);
        }

        private DeterminismStarter(boolean driftHash, boolean driftMarker, boolean unsafePackage) {
            this.driftHash = driftHash;
            this.driftMarker = driftMarker;
            this.unsafePackage = unsafePackage;
        }

        @Override
        public Process start(List<String> command) throws java.io.IOException {
            commands.add(List.copyOf(command));
            if ("capabilities".equals(command.get(1))) {
                return new CompletedProcess(validCapabilities());
            }
            String request = Files.readString(Path.of(command.get(3)), StandardCharsets.UTF_8);
            requests.add(request);
            Path source = capturedPath(SOURCE, request);
            Path resource = capturedPath(RESOURCE, request);
            sourceRoots.add(source);
            resourceRoots.add(resource);
            Path sourceMarker = sourceMarker(source);
            Path resourceMarker = resourceMarker(resource);
            Files.createDirectories(sourceMarker.getParent());
            Files.createDirectories(resourceMarker.getParent());
            int run = requests.size();
            Files.writeString(
                sourceMarker,
                driftMarker && run == 2
                    ? "{\"packageName\":\"com.acme.changed\"}"
                    : "{\"packageName\":\"com.acme.repost\"}",
                StandardCharsets.UTF_8
            );
            Files.writeString(
                resourceMarker,
                unsafePackage
                    ? "{\"packageName\":\"../escaped\"}"
                    : "{\"packageName\":\"com.acme.repost\"}",
                StandardCharsets.UTF_8
            );
            String sourceHash = driftHash && run == 2 ? "c".repeat(64) : "a".repeat(64);
            return new CompletedProcess(generationResponse(source, resource, sourceHash));
        }

        private static Path capturedPath(Pattern pattern, String request) {
            Matcher matcher = pattern.matcher(request);
            if (!matcher.find()) {
                throw new AssertionError("missing output root in request");
            }
            return Path.of(matcher.group(1));
        }

        private static Path sourceMarker(Path root) {
            return root.resolve("com/acme/repost/.repost-client.json");
        }

        private static Path resourceMarker(Path root) {
            return root.resolve(
                "META-INF/repost/generated-clients/v1/0123456789abcdef/.repost-client.json"
            );
        }
    }

    private static final class CompletedProcess extends Process {
        private final byte[] stdout;
        private final int exitCode;

        private CompletedProcess(String stdout) {
            this(stdout, 0);
        }

        private CompletedProcess(String stdout, int exitCode) {
            this.stdout = stdout.getBytes(StandardCharsets.UTF_8);
            this.exitCode = exitCode;
        }

        @Override public OutputStream getOutputStream() { return new ByteArrayOutputStream(); }
        @Override public InputStream getInputStream() { return new ByteArrayInputStream(stdout); }
        @Override public InputStream getErrorStream() { return new ByteArrayInputStream(new byte[0]); }
        @Override public int waitFor() { return exitCode; }
        @Override public boolean waitFor(long timeout, java.util.concurrent.TimeUnit unit) { return true; }
        @Override public int exitValue() { return exitCode; }
        @Override public void destroy() { }
    }
}
