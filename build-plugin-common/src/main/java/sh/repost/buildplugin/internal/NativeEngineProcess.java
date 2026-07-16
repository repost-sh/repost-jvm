package sh.repost.buildplugin.internal;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

final class NativeEngineProcess {
    private static final int COPY_BUFFER_BYTES = 8_192;

    private NativeEngineProcess() {
    }

    static GenerationProtocolV1.Response execute(
        Path executable,
        GenerationProtocolV1.Request request,
        Path requestDirectory,
        Duration timeout
    ) {
        return execute(executable, request, requestDirectory, timeout, NativeEngineProcess::startDirectly);
    }

    static GenerationProtocolV1.Response execute(
        Path executable,
        GenerationProtocolV1.Request request,
        Path requestDirectory,
        Duration timeout,
        ProcessStarter starter
    ) {
        Objects.requireNonNull(executable, "executable");
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(requestDirectory, "requestDirectory");
        Objects.requireNonNull(timeout, "timeout");
        Objects.requireNonNull(starter, "starter");
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must be positive");
        }

        Path requestFile = createRequestFile(requestDirectory, request);
        try {
            List<String> command = Collections.unmodifiableList(Arrays.asList(
                executable.toAbsolutePath().normalize().toString(),
                "generate",
                "--request",
                requestFile.toAbsolutePath().normalize().toString()
            ));
            try {
                return GenerationProtocolV1.readResponse(run(command, timeout, starter));
            } catch (EngineProcessException exception) {
                byte[] output = exception.output();
                if (output != null) {
                    try {
                        GenerationProtocolV1.Response response = GenerationProtocolV1.readResponse(output);
                        for (GenerationProtocolV1.Diagnostic diagnostic : response.getDiagnostics()) {
                            if (diagnostic.getSeverity() == GenerationProtocolV1.Severity.ERROR) {
                                return response;
                            }
                        }
                    } catch (GenerationProtocolV1.ProtocolException ignored) {
                        // Preserve the process failure when nonzero output is not a valid diagnostic response.
                    }
                }
                throw exception;
            } catch (GenerationProtocolV1.ProtocolException exception) {
                throw new EngineProcessException("schema engine returned a malformed response");
            }
        } finally {
            deleteRequestFile(requestFile);
        }
    }

    static void verifyCapabilities(
        Path executable,
        String expectedEngineVersion,
        String expectedRuntimeVersion,
        Duration timeout
    ) {
        verifyCapabilities(
            executable,
            expectedEngineVersion,
            expectedRuntimeVersion,
            timeout,
            NativeEngineProcess::startDirectly
        );
    }

    static void verifyCapabilities(
        Path executable,
        String expectedEngineVersion,
        String expectedRuntimeVersion,
        Duration timeout,
        ProcessStarter starter
    ) {
        Objects.requireNonNull(executable, "executable");
        Objects.requireNonNull(expectedEngineVersion, "expectedEngineVersion");
        Objects.requireNonNull(expectedRuntimeVersion, "expectedRuntimeVersion");
        Objects.requireNonNull(timeout, "timeout");
        Objects.requireNonNull(starter, "starter");
        validateTimeout(timeout);
        List<String> command = Collections.unmodifiableList(Arrays.asList(
            executable.toAbsolutePath().normalize().toString(),
            "capabilities",
            "--json"
        ));
        try {
            EngineCapabilitiesV1.verify(
                run(command, timeout, starter),
                expectedEngineVersion,
                expectedRuntimeVersion
            );
        } catch (EngineCapabilitiesV1.CapabilityException exception) {
            throw new EngineProcessException(exception.getMessage());
        }
    }

    private static byte[] run(
        List<String> command,
        Duration timeout,
        ProcessStarter starter
    ) {
        final Process process;
        try {
            process = starter.start(command);
        } catch (IOException | RuntimeException exception) {
            throw new EngineProcessException("schema engine could not be started");
        }
        if (process == null) {
            throw new EngineProcessException("schema engine process starter returned null");
        }

        closeQuietly(process.getOutputStream());
        OutputBudget outputBudget = new OutputBudget(GenerationProtocolV1.MAX_RESPONSE_BYTES);
        BoundedCapture stdout = new BoundedCapture(
            process.getInputStream(),
            GenerationProtocolV1.MAX_RESPONSE_BYTES,
            outputBudget
        );
        BoundedCapture stderr = new BoundedCapture(
            process.getErrorStream(),
            0,
            outputBudget
        );
        Thread stdoutThread = readerThread("repost-engine-stdout", stdout);
        Thread stderrThread = readerThread("repost-engine-stderr", stderr);
        stdoutThread.start();
        stderrThread.start();

        long timeoutNanos;
        try {
            timeoutNanos = timeout.toNanos();
        } catch (ArithmeticException exception) {
            terminate(process);
            throw new IllegalArgumentException("timeout is too large");
        }
        long startNanos = System.nanoTime();
        try {
            if (!process.waitFor(timeoutNanos, TimeUnit.NANOSECONDS)) {
                terminate(process);
                throw new EngineProcessException("schema engine execution timed out");
            }
            joinReader(stdoutThread, remainingNanos(startNanos, timeoutNanos));
            joinReader(stderrThread, remainingNanos(startNanos, timeoutNanos));
        } catch (InterruptedException exception) {
            terminate(process);
            Thread.currentThread().interrupt();
            throw new EngineProcessException("schema engine execution was interrupted");
        } finally {
            closeQuietly(process.getInputStream());
            closeQuietly(process.getErrorStream());
        }

        if (stdout.failed() || stderr.failed()) {
            throw new EngineProcessException("schema engine output could not be read");
        }
        if (outputBudget.exceededLimit()) {
            throw new EngineProcessException("schema engine output exceeds 1048576 bytes");
        }
        int exitCode = process.exitValue();
        if (exitCode != 0) {
            throw new EngineProcessException("schema engine exited unsuccessfully", stdout.bytes());
        }
        return stdout.bytes();
    }

    private static void validateTimeout(Duration timeout) {
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
    }

    private static Process startDirectly(List<String> command) throws IOException {
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.environment().clear();
        builder.redirectErrorStream(false);
        return builder.start();
    }

    private static Path createRequestFile(
        Path requestDirectory,
        GenerationProtocolV1.Request request
    ) {
        Path requestFile = null;
        try {
            requestFile = Files.createTempFile(requestDirectory, ".repost-generation-", ".json");
            restrictPermissions(requestFile);
            Files.write(
                requestFile,
                GenerationProtocolV1.writeRequest(request).getBytes(StandardCharsets.UTF_8),
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE
            );
        } catch (IOException | RuntimeException exception) {
            deleteIncompleteRequestFile(requestFile);
            throw new EngineProcessException("schema engine request file could not be created");
        }
        return requestFile;
    }

    private static void deleteIncompleteRequestFile(Path requestFile) {
        if (requestFile == null) {
            return;
        }
        try {
            Files.deleteIfExists(requestFile);
        } catch (IOException ignored) {
            // The stable build failure below remains the only public error surface.
        }
    }

    private static void restrictPermissions(Path requestFile) throws IOException {
        if (Files.getFileStore(requestFile).supportsFileAttributeView("posix")) {
            Files.setPosixFilePermissions(
                requestFile,
                EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)
            );
        }
    }

    private static void deleteRequestFile(Path requestFile) {
        try {
            Files.deleteIfExists(requestFile);
        } catch (IOException exception) {
            throw new EngineProcessException("schema engine request file could not be deleted");
        }
    }

    private static Thread readerThread(String name, Runnable reader) {
        Thread thread = new Thread(reader, name);
        thread.setDaemon(true);
        thread.setContextClassLoader(null);
        return thread;
    }

    private static void joinReader(Thread thread, long remainingNanos) throws InterruptedException {
        if (!thread.isAlive()) {
            return;
        }
        if (remainingNanos <= 0L) {
            throw new EngineProcessException("schema engine output did not terminate in time");
        }
        long millis = TimeUnit.NANOSECONDS.toMillis(remainingNanos);
        int nanos = (int) (remainingNanos - TimeUnit.MILLISECONDS.toNanos(millis));
        thread.join(millis, nanos);
        if (thread.isAlive()) {
            throw new EngineProcessException("schema engine output did not terminate in time");
        }
    }

    private static long remainingNanos(long startNanos, long timeoutNanos) {
        long elapsed = System.nanoTime() - startNanos;
        return elapsed >= timeoutNanos ? 0L : timeoutNanos - elapsed;
    }

    private static void terminate(Process process) {
        List<ProcessHandle> descendants = new ArrayList<>();
        try {
            process.descendants().forEach(descendants::add);
        } catch (RuntimeException ignored) {
            // The root process is still terminated below.
        }
        Collections.reverse(descendants);
        for (ProcessHandle descendant : descendants) {
            descendant.destroy();
        }
        process.destroy();
        for (ProcessHandle descendant : descendants) {
            if (descendant.isAlive()) {
                descendant.destroyForcibly();
            }
        }
        if (process.isAlive()) {
            process.destroyForcibly();
        }
        closeQuietly(process.getOutputStream());
        closeQuietly(process.getInputStream());
        closeQuietly(process.getErrorStream());
        awaitTermination(process, descendants);
    }

    private static void awaitTermination(Process process, List<ProcessHandle> descendants) {
        ArrayList<java.util.concurrent.CompletableFuture<ProcessHandle>> exits = new ArrayList<>();
        for (ProcessHandle descendant : descendants) {
            if (descendant.isAlive()) {
                exits.add(descendant.onExit());
            }
        }
        if (process.isAlive()) {
            try {
                exits.add(process.toHandle().onExit());
            } catch (UnsupportedOperationException ignored) {
                // A custom Process implementation may not expose a ProcessHandle.
            }
        }
        if (exits.isEmpty()) {
            return;
        }
        boolean interrupted = false;
        try {
            java.util.concurrent.CompletableFuture.allOf(
                exits.toArray(new java.util.concurrent.CompletableFuture<?>[0])
            ).get(1L, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            interrupted = true;
        } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException ignored) {
            // Destruction is best effort after the bounded process timeout.
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static void closeQuietly(java.io.Closeable closeable) {
        try {
            closeable.close();
        } catch (IOException ignored) {
            // Cleanup is best effort; no raw process exception crosses the build-plugin boundary.
        }
    }

    @FunctionalInterface
    interface ProcessStarter {
        Process start(List<String> command) throws IOException;
    }

    static final class EngineProcessException extends IllegalStateException {
        private static final long serialVersionUID = 1L;
        private final byte[] output;

        EngineProcessException(String message) {
            this(message, null);
        }

        private EngineProcessException(String message, byte[] output) {
            super(message);
            this.output = output == null ? null : Arrays.copyOf(output, output.length);
        }

        private byte[] output() {
            return output == null ? null : Arrays.copyOf(output, output.length);
        }
    }

    private static final class BoundedCapture implements Runnable {
        private final InputStream input;
        private final int retainLimit;
        private final OutputBudget outputBudget;
        private final ByteArrayOutputStream retained;
        private volatile boolean failed;

        private BoundedCapture(InputStream input, int retainLimit, OutputBudget outputBudget) {
            this.input = Objects.requireNonNull(input, "input");
            this.retainLimit = retainLimit;
            this.outputBudget = outputBudget;
            this.retained = new ByteArrayOutputStream(Math.min(retainLimit, COPY_BUFFER_BYTES));
        }

        @Override
        public void run() {
            byte[] buffer = new byte[COPY_BUFFER_BYTES];
            try {
                int count;
                while ((count = input.read(buffer)) != -1) {
                    outputBudget.add(count);
                    int remaining = retainLimit - retained.size();
                    if (remaining > 0) {
                        retained.write(buffer, 0, Math.min(count, remaining));
                    }
                }
            } catch (IOException exception) {
                failed = true;
            }
        }

        private byte[] bytes() {
            return retained.toByteArray();
        }

        private boolean failed() {
            return failed;
        }
    }

    private static final class OutputBudget {
        private final long limit;
        private final AtomicLong bytes = new AtomicLong();

        private OutputBudget(long limit) {
            this.limit = limit;
        }

        private void add(int count) {
            bytes.updateAndGet(previous -> previous > limit ? previous : previous + count);
        }

        private boolean exceededLimit() {
            return bytes.get() > limit;
        }
    }
}
