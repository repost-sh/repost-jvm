package sh.repost.buildplugin.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class NativeEngineProcessTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void invokesTheExecutableDirectlyAndDeletesTheRequestFile() {
        GenerationProtocolV1.Request request = request();
        CapturingStarter starter = new CapturingStarter(validResponse(), 0);

        GenerationProtocolV1.Response response = NativeEngineProcess.execute(
            Path.of("/opt/repost/repost-schema-engine"),
            request,
            temporaryDirectory,
            Duration.ofSeconds(5),
            starter
        );

        assertEquals("0.9.0", response.getEngineVersion());
        assertEquals(4, starter.command.size());
        assertEquals(
            Path.of("/opt/repost/repost-schema-engine").toAbsolutePath().normalize().toString(),
            starter.command.get(0)
        );
        assertEquals("generate", starter.command.get(1));
        assertEquals("--request", starter.command.get(2));
        Path requestFile = Path.of(starter.command.get(3));
        assertTrue(starter.requestExistedAtStart);
        assertTrue(starter.requestContents.contains("\"environmentInputs\":{}"));
        assertFalse(Files.exists(requestFile));
    }

    @Test
    void verifiesExactEngineCapabilitiesBeforeGeneration() {
        CapabilityStarter starter = new CapabilityStarter(validCapabilities(), 0);

        NativeEngineProcess.verifyCapabilities(
            Path.of("/opt/repost/repost-schema-engine"),
            "0.9.0",
            "1.0.0",
            Duration.ofSeconds(5),
            starter
        );

        assertEquals(
            List.of(
                Path.of("/opt/repost/repost-schema-engine").toAbsolutePath().normalize().toString(),
                "capabilities",
                "--json"
            ),
            starter.command
        );
    }

    @Test
    void rejectsEveryCapabilitiesVersionSkewBeforeGeneration() {
        for (String skewed : List.of(
            validCapabilities().replace("\"protocolVersion\":1", "\"protocolVersion\":2"),
            validCapabilities().replace("\"engineVersion\":\"0.9.0\"", "\"engineVersion\":\"0.9.1\""),
            validCapabilities().replace("\"runtimeVersion\":\"1.0.0\"", "\"runtimeVersion\":\"2.0.0\""),
            validCapabilities().replace("\"descriptorVersion\":2", "\"descriptorVersion\":3")
        )) {
            assertThrows(
                NativeEngineProcess.EngineProcessException.class,
                () -> NativeEngineProcess.verifyCapabilities(
                    Path.of("/opt/repost/repost-schema-engine"),
                    "0.9.0",
                    "1.0.0",
                    Duration.ofSeconds(5),
                    new CapabilityStarter(skewed, 0)
                )
            );
        }
    }

    @Test
    void deletesTheRequestOnMalformedNonzeroAndOversizedOutput() {
        assertProcessFailure(new CapturingStarter("not-json", 0));
        assertProcessFailure(new CapturingStarter(validResponse(), 7));
        assertProcessFailure(new CapturingStarter(" ".repeat(GenerationProtocolV1.MAX_RESPONSE_BYTES + 1), 0));
    }

    @Test
    void preservesStructuredErrorDiagnosticsFromNonzeroGeneration() {
        GenerationProtocolV1.Response response = NativeEngineProcess.execute(
            Path.of("/opt/repost/repost-schema-engine"),
            request(),
            temporaryDirectory,
            Duration.ofSeconds(5),
            new CapturingStarter(errorResponse(), 1)
        );

        assertEquals(1, response.getDiagnostics().size());
        assertEquals(GenerationProtocolV1.Severity.ERROR, response.getDiagnostics().get(0).getSeverity());
        assertEquals("TARGET_COLLISION", response.getDiagnostics().get(0).getCode());
    }

    @Test
    void deletesTheRequestWhenProcessStartFailsOrTimesOut() throws java.io.IOException {
        assertThrows(
            NativeEngineProcess.EngineProcessException.class,
            () -> NativeEngineProcess.execute(
                Path.of("/opt/repost/repost-schema-engine"),
                request(),
                temporaryDirectory,
                Duration.ofSeconds(5),
                command -> { throw new java.io.IOException("sentinel-start-failure"); }
            )
        );
        try (java.util.stream.Stream<Path> files = Files.list(temporaryDirectory)) {
            assertEquals(0L, files.count());
        }

        NeverCompletingProcess process = new NeverCompletingProcess();
        CapturingProcessStarter starter = new CapturingProcessStarter(process);
        assertThrows(
            NativeEngineProcess.EngineProcessException.class,
            () -> NativeEngineProcess.execute(
                Path.of("/opt/repost/repost-schema-engine"),
                request(),
                temporaryDirectory,
                Duration.ofNanos(1),
                starter
            )
        );
        assertTrue(process.destroyed);
        assertFalse(Files.exists(Path.of(starter.command.get(3))));
    }

    @Test
    void deletesAnIncompleteRequestWhenCanonicalEncodingFails() throws java.io.IOException {
        GenerationProtocolV1.Request invalid = new GenerationProtocolV1.Request(
            "1.0.0",
            "0.9.0",
            "1.0.0",
            "invalid-\ud800-path",
            requestGenerators(),
            Collections.emptyMap(),
            GenerationProtocolV1.BuildIdentity.maven("com.acme", "orders"),
            GenerationProtocolV1.CheckMode.GENERATE
        );
        assertThrows(
            NativeEngineProcess.EngineProcessException.class,
            () -> NativeEngineProcess.execute(
                Path.of("/opt/repost/repost-schema-engine"),
                invalid,
                temporaryDirectory,
                Duration.ofSeconds(5),
                command -> { throw new AssertionError("process must not start"); }
            )
        );
        try (java.util.stream.Stream<Path> files = Files.list(temporaryDirectory)) {
            assertEquals(0L, files.count());
        }
    }

    private void assertProcessFailure(CapturingStarter starter) {
        assertThrows(
            NativeEngineProcess.EngineProcessException.class,
            () -> NativeEngineProcess.execute(
                Path.of("/opt/repost/repost-schema-engine"),
                request(),
                temporaryDirectory,
                Duration.ofSeconds(5),
                starter
            )
        );
        assertFalse(Files.exists(Path.of(starter.command.get(3))));
    }

    private static GenerationProtocolV1.Request request() {
        return new GenerationProtocolV1.Request(
            "1.0.0",
            "0.9.0",
            "1.0.0",
            "/workspace/repost/schema.repost",
            requestGenerators(),
            Collections.emptyMap(),
            GenerationProtocolV1.BuildIdentity.gradle("com.acme", "orders", ":app"),
            GenerationProtocolV1.CheckMode.GENERATE
        );
    }

    private static List<GenerationProtocolV1.GeneratorRequest> requestGenerators() {
        return Collections.singletonList(new GenerationProtocolV1.GeneratorRequest(
            "javaSdk",
            "/workspace/generated/java",
            "/workspace/generated/resources",
            "/workspace/state/java",
            "/workspace/state/resources"
        ));
    }

    private static String validResponse() {
        return "{\"protocolVersion\":1,\"engineVersion\":\"0.9.0\"," +
            "\"runtimeVersion\":\"1.0.0\",\"descriptorVersion\":2," +
            "\"generators\":[],\"diagnostics\":[]}";
    }

    private static String errorResponse() {
        return "{\"protocolVersion\":1,\"engineVersion\":\"0.9.0\"," +
            "\"runtimeVersion\":\"1.0.0\",\"descriptorVersion\":2," +
            "\"generators\":[],\"diagnostics\":[{\"severity\":\"ERROR\"," +
            "\"code\":\"TARGET_COLLISION\",\"message\":\"duplicate generated client FQCN; " +
            "set a unique packageName or clientName\"}]}";
    }

    private static String validCapabilities() {
        return "{\"protocolVersion\":1,\"engineVersion\":\"0.9.0\"," +
            "\"runtimeVersion\":\"1.0.0\",\"descriptorVersion\":2}";
    }

    private static final class CapabilityStarter implements NativeEngineProcess.ProcessStarter {
        private final byte[] stdout;
        private final int exitCode;
        private List<String> command = Collections.emptyList();

        private CapabilityStarter(String stdout, int exitCode) {
            this.stdout = stdout.getBytes(StandardCharsets.UTF_8);
            this.exitCode = exitCode;
        }

        @Override
        public Process start(List<String> command) {
            this.command = List.copyOf(command);
            return new CompletedProcess(stdout, exitCode);
        }
    }

    private static final class CapturingStarter implements NativeEngineProcess.ProcessStarter {
        private final byte[] stdout;
        private final int exitCode;
        private List<String> command = Collections.emptyList();
        private boolean requestExistedAtStart;
        private String requestContents = "";

        private CapturingStarter(String stdout, int exitCode) {
            this.stdout = stdout.getBytes(StandardCharsets.UTF_8);
            this.exitCode = exitCode;
        }

        @Override
        public Process start(List<String> command) throws java.io.IOException {
            this.command = List.copyOf(command);
            Path requestFile = Path.of(command.get(3));
            requestExistedAtStart = Files.isRegularFile(requestFile);
            requestContents = Files.readString(requestFile, StandardCharsets.UTF_8);
            return new CompletedProcess(stdout, exitCode);
        }
    }

    private static final class CompletedProcess extends Process {
        private final InputStream stdout;
        private final int exitCode;

        private CompletedProcess(byte[] stdout, int exitCode) {
            this.stdout = new ByteArrayInputStream(stdout);
            this.exitCode = exitCode;
        }

        @Override public OutputStream getOutputStream() { return new ByteArrayOutputStream(); }
        @Override public InputStream getInputStream() { return stdout; }
        @Override public InputStream getErrorStream() { return new ByteArrayInputStream(new byte[0]); }
        @Override public int waitFor() { return exitCode; }
        @Override public boolean waitFor(long timeout, java.util.concurrent.TimeUnit unit) { return true; }
        @Override public int exitValue() { return exitCode; }
        @Override public void destroy() { }
    }

    private static final class CapturingProcessStarter implements NativeEngineProcess.ProcessStarter {
        private final Process process;
        private List<String> command = Collections.emptyList();

        private CapturingProcessStarter(Process process) {
            this.process = process;
        }

        @Override
        public Process start(List<String> command) {
            this.command = List.copyOf(command);
            return process;
        }
    }

    private static final class NeverCompletingProcess extends Process {
        private boolean destroyed;

        @Override public OutputStream getOutputStream() { return new ByteArrayOutputStream(); }
        @Override public InputStream getInputStream() { return new ByteArrayInputStream(new byte[0]); }
        @Override public InputStream getErrorStream() { return new ByteArrayInputStream(new byte[0]); }
        @Override public int waitFor() { return 0; }
        @Override public boolean waitFor(long timeout, java.util.concurrent.TimeUnit unit) { return false; }
        @Override public int exitValue() { throw new IllegalThreadStateException("still running"); }
        @Override public void destroy() { destroyed = true; }
        @Override public boolean isAlive() { return !destroyed; }
        @Override public Process destroyForcibly() { destroyed = true; return this; }
    }
}
