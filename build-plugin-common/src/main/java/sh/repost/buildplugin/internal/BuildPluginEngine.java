package sh.repost.buildplugin.internal;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Shared verified native-engine execution path used by both build plugins. */
public final class BuildPluginEngine {
    private static final String OUTPUT_MARKER = ".repost-client.json";

    private BuildPluginEngine() {
    }

    /** Verifies generated outputs restored from a build cache without mutating them. */
    public static void verifyGeneratedOutputs(List<Generator> generators) {
        Objects.requireNonNull(generators, "generators");
        for (Generator generator : generators) {
            GeneratedOutputVerifier.verify(Objects.requireNonNull(generator, "generator"));
        }
    }

    /** Verifies the executable handshake and executes one closed generation request. */
    public static void execute(Request request) {
        Objects.requireNonNull(request, "request");
        try {
            NativeEngineProcess.verifyCapabilities(
                request.executable,
                request.engineVersion,
                request.runtimeVersion,
                request.timeout
            );
            validateResponse(
                request,
                NativeEngineProcess.execute(
                    request.executable,
                    request.protocolRequest(),
                    request.requestDirectory,
                    request.timeout
                )
            );
        } catch (NativeEngineProcess.EngineProcessException exception) {
            throw new EngineException(exception.getMessage());
        }
    }

    static void execute(Request request, NativeEngineProcess.ProcessStarter starter) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(starter, "starter");
        try {
            NativeEngineProcess.verifyCapabilities(
                request.executable,
                request.engineVersion,
                request.runtimeVersion,
                request.timeout,
                starter
            );
            validateResponse(
                request,
                NativeEngineProcess.execute(
                    request.executable,
                    request.protocolRequest(),
                    request.requestDirectory,
                    request.timeout,
                    starter
                )
            );
        } catch (NativeEngineProcess.EngineProcessException exception) {
            throw new EngineException(exception.getMessage());
        }
    }

    /** Executes two isolated CHECK requests and rejects any marker or tree-hash drift. */
    public static void executeDeterminismCheck(Request request) {
        executeDeterminismCheck(request, null);
    }

    static void executeDeterminismCheck(Request request, NativeEngineProcess.ProcessStarter starter) {
        Objects.requireNonNull(request, "request");
        if (!request.check) {
            throw new IllegalArgumentException("determinism check requires CHECK mode");
        }
        Path replicaRoot = request.requestDirectory.resolve("determinism-replica")
            .toAbsolutePath().normalize();
        RuntimeException failure = null;
        try {
            deleteTree(replicaRoot);
            Files.createDirectories(request.requestDirectory);
            Request replica = request.determinismReplica(replicaRoot);
            Files.createDirectories(replica.requestDirectory);
            verifyCapabilities(request, starter);
            List<OutputFingerprint> first = executeAndFingerprint(request, starter);
            List<OutputFingerprint> second = executeAndFingerprint(replica, starter);
            compareFingerprints(first, second);
        } catch (IOException exception) {
            failure = new EngineException("Repost deterministic check local state could not be created");
            throw failure;
        } catch (NativeEngineProcess.EngineProcessException exception) {
            failure = new EngineException(exception.getMessage());
            throw failure;
        } catch (RuntimeException exception) {
            failure = exception;
            throw exception;
        } finally {
            try {
                deleteTree(replicaRoot);
            } catch (EngineException cleanupFailure) {
                if (failure == null) {
                    throw cleanupFailure;
                }
            }
        }
    }

    private static void verifyCapabilities(Request request, NativeEngineProcess.ProcessStarter starter) {
        if (starter == null) {
            NativeEngineProcess.verifyCapabilities(
                request.executable,
                request.engineVersion,
                request.runtimeVersion,
                request.timeout
            );
        } else {
            NativeEngineProcess.verifyCapabilities(
                request.executable,
                request.engineVersion,
                request.runtimeVersion,
                request.timeout,
                starter
            );
        }
    }

    private static List<OutputFingerprint> executeAndFingerprint(
        Request request,
        NativeEngineProcess.ProcessStarter starter
    ) {
        GenerationProtocolV1.Response response = starter == null
            ? NativeEngineProcess.execute(
                request.executable,
                request.protocolRequest(),
                request.requestDirectory,
                request.timeout
            )
            : NativeEngineProcess.execute(
                request.executable,
                request.protocolRequest(),
                request.requestDirectory,
                request.timeout,
                starter
            );
        return validateResponse(request, response, true);
    }

    private static void validateResponse(Request request, GenerationProtocolV1.Response response) {
        validateResponse(request, response, false);
    }

    private static List<OutputFingerprint> validateResponse(
        Request request,
        GenerationProtocolV1.Response response,
        boolean captureMarkers
    ) {
        if (!request.engineVersion.equals(response.getEngineVersion())) {
            throw new EngineException("schema engine generation response has an engine version mismatch");
        }
        if (!request.runtimeVersion.equals(response.getRuntimeVersion())) {
            throw new EngineException("schema engine generation response has a runtime version mismatch");
        }
        for (GenerationProtocolV1.Diagnostic diagnostic : response.getDiagnostics()) {
            if (diagnostic.getSeverity() == GenerationProtocolV1.Severity.ERROR) {
                GenerationProtocolV1.SourceSpan source = diagnostic.getSource();
                String location = source == null
                    ? ""
                    : " at " + source.getPath() + ":" + source.getStartByte() + "-" + source.getEndByte();
                throw new EngineException(
                    "Repost schema diagnostic ERROR " + diagnostic.getCode() + location + ": " +
                        diagnostic.getMessage()
                );
            }
        }
        List<GenerationProtocolV1.GeneratorResult> results = response.getGenerators();
        if (results.size() != request.generators.size()) {
            throw new EngineException("schema engine generation response has an unexpected generator set");
        }
        List<OutputFingerprint> fingerprints = new ArrayList<>(results.size());
        for (int index = 0; index < results.size(); index++) {
            Generator expected = request.generators.get(index);
            GenerationProtocolV1.GeneratorResult actual = results.get(index);
            if (!expected.name.equals(actual.getGeneratorName())) {
                throw new EngineException("schema engine generation response has an unexpected generator set");
            }
            if (!expected.sourceOutputDirectory.equals(actual.getSourceRoot())
                || !expected.resourceOutputDirectory.equals(actual.getResourceRoot())) {
                throw new EngineException("schema engine generation response has an unexpected output root");
            }
            if (captureMarkers) {
                MarkerPair markers = readMarkers(expected, actual);
                fingerprints.add(new OutputFingerprint(
                    actual.getGeneratorName(),
                    actual.getGeneratorId(),
                    actual.getSourceTreeHash(),
                    actual.getResourceTreeHash(),
                    markers.source,
                    markers.resource
                ));
            }
        }
        return fingerprints;
    }

    private static MarkerPair readMarkers(
        Generator expected,
        GenerationProtocolV1.GeneratorResult actual
    ) {
        Path resourceMarker = Path.of(expected.resourceOutputDirectory)
            .resolve("META-INF/repost/generated-clients/v1")
            .resolve(actual.getGeneratorId())
            .resolve(OUTPUT_MARKER);
        byte[] resource = readMarker(resourceMarker);
        String packageName = markerPackageName(resource);
        Path sourceRoot = Path.of(expected.sourceOutputDirectory).toAbsolutePath().normalize();
        Path sourceMarker = sourceRoot;
        for (String segment : packageName.split("\\.", -1)) {
            if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment)
                || segment.indexOf('/') >= 0 || segment.indexOf('\\') >= 0) {
                throw new EngineException("Repost deterministic check could not read generated markers");
            }
            sourceMarker = sourceMarker.resolve(segment);
        }
        sourceMarker = sourceMarker.resolve(OUTPUT_MARKER).normalize();
        if (!sourceMarker.startsWith(sourceRoot)) {
            throw new EngineException("Repost deterministic check could not read generated markers");
        }
        return new MarkerPair(readMarker(sourceMarker), resource);
    }

    private static byte[] readMarker(Path marker) {
        try {
            if (!Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS)) {
                throw new EngineException("Repost deterministic check could not read generated markers");
            }
            return Files.readAllBytes(marker);
        } catch (IOException exception) {
            throw new EngineException("Repost deterministic check could not read generated markers");
        }
    }

    private static String markerPackageName(byte[] marker) {
        try {
            Object parsed = StrictJson.parse(marker);
            if (!(parsed instanceof Map)) {
                throw new EngineException("Repost deterministic check could not read generated markers");
            }
            Object packageName = ((Map<?, ?>) parsed).get("packageName");
            if (!(packageName instanceof String) || ((String) packageName).isEmpty()) {
                throw new EngineException("Repost deterministic check could not read generated markers");
            }
            return (String) packageName;
        } catch (GenerationProtocolV1.ProtocolException exception) {
            throw new EngineException("Repost deterministic check could not read generated markers");
        }
    }

    private static void compareFingerprints(
        List<OutputFingerprint> first,
        List<OutputFingerprint> second
    ) {
        for (int index = 0; index < first.size(); index++) {
            OutputFingerprint left = first.get(index);
            OutputFingerprint right = second.get(index);
            if (!left.sameAs(right)) {
                throw new EngineException(
                    "Repost generation is nondeterministic for generator " + left.generatorName
                );
            }
        }
    }

    private static void deleteTree(Path root) {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                    Files.delete(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path directory, IOException failure) throws IOException {
                    if (failure != null) {
                        throw failure;
                    }
                    Files.delete(directory);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException exception) {
            throw new EngineException("Repost deterministic check local state could not be cleaned");
        }
    }

    private static final class OutputFingerprint {
        private final String generatorName;
        private final String generatorId;
        private final String sourceTreeHash;
        private final String resourceTreeHash;
        private final byte[] sourceMarker;
        private final byte[] resourceMarker;

        private OutputFingerprint(
            String generatorName,
            String generatorId,
            String sourceTreeHash,
            String resourceTreeHash,
            byte[] sourceMarker,
            byte[] resourceMarker
        ) {
            this.generatorName = generatorName;
            this.generatorId = generatorId;
            this.sourceTreeHash = sourceTreeHash;
            this.resourceTreeHash = resourceTreeHash;
            this.sourceMarker = sourceMarker;
            this.resourceMarker = resourceMarker;
        }

        private boolean sameAs(OutputFingerprint other) {
            return generatorName.equals(other.generatorName)
                && generatorId.equals(other.generatorId)
                && sourceTreeHash.equals(other.sourceTreeHash)
                && resourceTreeHash.equals(other.resourceTreeHash)
                && Arrays.equals(sourceMarker, other.sourceMarker)
                && Arrays.equals(resourceMarker, other.resourceMarker);
        }
    }

    private static final class MarkerPair {
        private final byte[] source;
        private final byte[] resource;

        private MarkerPair(byte[] source, byte[] resource) {
            this.source = source;
            this.resource = resource;
        }
    }

    /** Immutable shared build-plugin request. */
    public static final class Request {
        private final Path executable;
        private final String pluginVersion;
        private final String engineVersion;
        private final String runtimeVersion;
        private final String schemaPath;
        private final List<Generator> generators;
        private final Map<String, String> environmentInputs;
        private final BuildIdentity buildIdentity;
        private final boolean check;
        private final Path requestDirectory;
        private final Duration timeout;

        /** Creates one Maven or Gradle build-plugin request. */
        public Request(
            Path executable,
            String pluginVersion,
            String engineVersion,
            String runtimeVersion,
            String schemaPath,
            List<Generator> generators,
            Map<String, String> environmentInputs,
            BuildIdentity buildIdentity,
            boolean check,
            Path requestDirectory,
            Duration timeout
        ) {
            this.executable = Objects.requireNonNull(executable, "executable");
            this.pluginVersion = required(pluginVersion, "pluginVersion");
            this.engineVersion = required(engineVersion, "engineVersion");
            this.runtimeVersion = required(runtimeVersion, "runtimeVersion");
            this.schemaPath = required(schemaPath, "schemaPath");
            Objects.requireNonNull(generators, "generators");
            List<Generator> generatorCopy = new ArrayList<>(generators.size());
            for (Generator generator : generators) {
                generatorCopy.add(Objects.requireNonNull(generator, "generator"));
            }
            this.generators = Collections.unmodifiableList(generatorCopy);
            Objects.requireNonNull(environmentInputs, "environmentInputs");
            this.environmentInputs = Collections.unmodifiableMap(new LinkedHashMap<>(environmentInputs));
            this.buildIdentity = Objects.requireNonNull(buildIdentity, "buildIdentity");
            this.check = check;
            this.requestDirectory = Objects.requireNonNull(requestDirectory, "requestDirectory");
            this.timeout = Objects.requireNonNull(timeout, "timeout");
        }

        private GenerationProtocolV1.Request protocolRequest() {
            List<GenerationProtocolV1.GeneratorRequest> requests = new ArrayList<>(generators.size());
            for (Generator generator : generators) {
                requests.add(new GenerationProtocolV1.GeneratorRequest(
                    generator.name,
                    generator.sourceOutputDirectory,
                    generator.resourceOutputDirectory,
                    generator.sourceControlDirectory,
                    generator.resourceControlDirectory
                ));
            }
            return new GenerationProtocolV1.Request(
                pluginVersion,
                engineVersion,
                runtimeVersion,
                schemaPath,
                requests,
                environmentInputs,
                buildIdentity.protocolIdentity,
                check ? GenerationProtocolV1.CheckMode.CHECK : GenerationProtocolV1.CheckMode.GENERATE
            );
        }

        private Request determinismReplica(Path root) {
            List<Generator> replicas = new ArrayList<>(generators.size());
            for (int index = 0; index < generators.size(); index++) {
                Generator generator = generators.get(index);
                Path generatorRoot = root.resolve("outputs").resolve(Integer.toString(index));
                Path controlRoot = root.resolve("control").resolve(Integer.toString(index));
                replicas.add(new Generator(
                    generator.name,
                    generatorRoot.resolve("source").toString(),
                    generatorRoot.resolve("resource").toString(),
                    controlRoot.resolve("source").toString(),
                    controlRoot.resolve("resource").toString()
                ));
            }
            return new Request(
                executable,
                pluginVersion,
                engineVersion,
                runtimeVersion,
                schemaPath,
                replicas,
                environmentInputs,
                buildIdentity,
                true,
                root.resolve("requests"),
                timeout
            );
        }

        /** Returns the plugin version sent to the engine. */
        public String getPluginVersion() {
            return pluginVersion;
        }

        /** Returns the expected engine version. */
        public String getEngineVersion() {
            return engineVersion;
        }

        /** Returns the expected runtime family version. */
        public String getRuntimeVersion() {
            return runtimeVersion;
        }

        /** Returns the canonical schema path. */
        public String getSchemaPath() {
            return schemaPath;
        }

        /** Returns the explicit declared generation environment inputs. */
        public Map<String, String> getEnvironment() {
            return environmentInputs;
        }

        /** Returns the selected generators in request order. */
        public List<Generator> getGenerators() {
            return generators;
        }

        /** Returns selected generator names in request order. */
        public List<String> getGeneratorNames() {
            List<String> names = new ArrayList<>(generators.size());
            for (Generator generator : generators) {
                names.add(generator.name);
            }
            return Collections.unmodifiableList(names);
        }

        /** Returns the build-tool kind. */
        public String getBuildKind() {
            return buildIdentity.kind;
        }

        /** Returns the build group or Maven group ID. */
        public String getBuildGroup() {
            return buildIdentity.group;
        }

        /** Returns the root project name or Maven artifact ID. */
        public String getBuildName() {
            return buildIdentity.name;
        }

        /** Returns the Gradle project path, or {@code null} for Maven. */
        public String getBuildProjectPath() {
            return buildIdentity.projectPath;
        }

        /** Returns {@code GENERATE} or {@code CHECK}. */
        public String getCheckMode() {
            return check ? "CHECK" : "GENERATE";
        }

        /** Returns the private local-state directory used for ephemeral request files. */
        public Path getRequestDirectory() {
            return requestDirectory;
        }
    }

    /** Closed Maven or Gradle build identity for the shared engine request. */
    public static final class BuildIdentity {
        private final GenerationProtocolV1.BuildIdentity protocolIdentity;
        private final String kind;
        private final String group;
        private final String name;
        private final String projectPath;

        private BuildIdentity(
            GenerationProtocolV1.BuildIdentity protocolIdentity,
            String kind,
            String group,
            String name,
            String projectPath
        ) {
            this.protocolIdentity = protocolIdentity;
            this.kind = kind;
            this.group = group;
            this.name = name;
            this.projectPath = projectPath;
        }

        /** Creates a Maven build identity. */
        public static BuildIdentity maven(String groupId, String artifactId) {
            return new BuildIdentity(
                GenerationProtocolV1.BuildIdentity.maven(groupId, artifactId),
                "MAVEN",
                groupId,
                artifactId,
                null
            );
        }

        /** Creates a Gradle build identity. */
        public static BuildIdentity gradle(String group, String rootProjectName, String projectPath) {
            return new BuildIdentity(
                GenerationProtocolV1.BuildIdentity.gradle(group, rootProjectName, projectPath),
                "GRADLE",
                group,
                rootProjectName,
                projectPath
            );
        }
    }

    /** One selected generator with explicit dedicated output and control roots. */
    public static final class Generator {
        private final String name;
        private final String sourceOutputDirectory;
        private final String resourceOutputDirectory;
        private final String sourceControlDirectory;
        private final String resourceControlDirectory;

        /** Creates one selected generator request. */
        public Generator(
            String name,
            String sourceOutputDirectory,
            String resourceOutputDirectory,
            String sourceControlDirectory,
            String resourceControlDirectory
        ) {
            this.name = required(name, "name");
            this.sourceOutputDirectory = required(sourceOutputDirectory, "sourceOutputDirectory");
            this.resourceOutputDirectory = required(resourceOutputDirectory, "resourceOutputDirectory");
            this.sourceControlDirectory = required(sourceControlDirectory, "sourceControlDirectory");
            this.resourceControlDirectory = required(resourceControlDirectory, "resourceControlDirectory");
        }

        /** Returns the generator name. */
        public String getName() {
            return name;
        }

        /** Returns the dedicated source output directory. */
        public String getSourceOutputDirectory() {
            return sourceOutputDirectory;
        }

        /** Returns the dedicated resource output directory. */
        public String getResourceOutputDirectory() {
            return resourceOutputDirectory;
        }

        /** Returns the separate source control directory. */
        public String getSourceControlDirectory() {
            return sourceControlDirectory;
        }

        /** Returns the separate resource control directory. */
        public String getResourceControlDirectory() {
            return resourceControlDirectory;
        }
    }

    private static String required(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be empty");
        }
        return value;
    }

    /** Sanitized build-plugin execution failure. */
    public static final class EngineException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        EngineException(String message) {
            super(message);
        }
    }
}
