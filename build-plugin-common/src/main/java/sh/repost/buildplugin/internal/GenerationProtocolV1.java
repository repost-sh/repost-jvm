package sh.repost.buildplugin.internal;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.regex.Pattern;

/** Shared closed JSON protocol used by the Repost Maven and Gradle plugins. */
final class GenerationProtocolV1 {
    /** Maximum captured stdout accepted from the schema engine. */
    static final int MAX_RESPONSE_BYTES = 1_048_576;

    private static final int PROTOCOL_VERSION = 1;
    private static final int DESCRIPTOR_VERSION = 2;
    private static final Pattern GENERATOR_ID = Pattern.compile("[0-9a-f]{16}");
    private static final Pattern SHA_256 = Pattern.compile("sha256:[0-9a-f]{64}");
    private static final List<String> RESPONSE_FIELDS = Collections.unmodifiableList(Arrays.asList(
        "protocolVersion",
        "engineVersion",
        "runtimeVersion",
        "descriptorVersion",
        "generators",
        "diagnostics"
    ));
    private static final List<String> GENERATOR_RESPONSE_FIELDS = Collections.unmodifiableList(Arrays.asList(
        "generatorName",
        "generatorId",
        "sourceRoot",
        "resourceRoot",
        "sourceTreeHash",
        "resourceTreeHash"
    ));

    private GenerationProtocolV1() {
    }

    /** Serializes one validated request in canonical protocol field order. */
    static String writeRequest(Request request) {
        Objects.requireNonNull(request, "request");
        StringBuilder output = new StringBuilder();
        output.append("{\"protocolVersion\":").append(PROTOCOL_VERSION);
        stringField(output, "pluginVersion", request.pluginVersion);
        stringField(output, "engineVersion", request.engineVersion);
        stringField(output, "runtimeVersion", request.runtimeVersion);
        output.append(",\"descriptorVersion\":").append(DESCRIPTOR_VERSION);
        stringField(output, "schemaPath", request.schemaPath);
        output.append(",\"generators\":[");
        for (int index = 0; index < request.generators.size(); index++) {
            if (index != 0) {
                output.append(',');
            }
            writeGeneratorRequest(output, request.generators.get(index));
        }
        output.append("],\"environmentInputs\":{");
        int environmentIndex = 0;
        for (Map.Entry<String, String> entry : request.environmentInputs.entrySet()) {
            if (environmentIndex++ != 0) {
                output.append(',');
            }
            string(output, entry.getKey());
            output.append(':');
            string(output, entry.getValue());
        }
        output.append("},\"buildIdentity\":");
        writeBuildIdentity(output, request.buildIdentity);
        stringField(output, "checkMode", request.checkMode.name());
        return output.append('}').toString();
    }

    /** Parses and validates one bounded, strict UTF-8 engine response. */
    static Response readResponse(byte[] bytes) {
        Objects.requireNonNull(bytes, "bytes");
        if (bytes.length > MAX_RESPONSE_BYTES) {
            throw new ProtocolException("response exceeds 1048576 bytes");
        }
        Object parsed = StrictJson.parse(bytes);
        Map<String, Object> root = object(parsed, "response");
        exactFields(root, RESPONSE_FIELDS, "response");
        exactInteger(root.get("protocolVersion"), PROTOCOL_VERSION, "protocolVersion");
        String engineVersion = nonEmptyString(root.get("engineVersion"), "engineVersion");
        String runtimeVersion = nonEmptyString(root.get("runtimeVersion"), "runtimeVersion");
        exactInteger(root.get("descriptorVersion"), DESCRIPTOR_VERSION, "descriptorVersion");
        List<GeneratorResult> generators = generatorResults(root.get("generators"));
        List<Diagnostic> diagnostics = diagnostics(root.get("diagnostics"));
        return new Response(engineVersion, runtimeVersion, generators, diagnostics);
    }

    private static void writeGeneratorRequest(StringBuilder output, GeneratorRequest generator) {
        output.append('{');
        firstStringField(output, "generatorName", generator.generatorName);
        stringField(output, "sourceOutputDirectory", generator.sourceOutputDirectory);
        stringField(output, "resourceOutputDirectory", generator.resourceOutputDirectory);
        stringField(output, "sourceControlDirectory", generator.sourceControlDirectory);
        stringField(output, "resourceControlDirectory", generator.resourceControlDirectory);
        output.append('}');
    }

    private static void writeBuildIdentity(StringBuilder output, BuildIdentity identity) {
        output.append('{');
        firstStringField(output, "kind", identity.kind.name());
        if (identity.kind == BuildKind.MAVEN) {
            stringField(output, "groupId", identity.group);
            stringField(output, "artifactId", identity.name);
        } else {
            stringField(output, "group", identity.group);
            stringField(output, "rootProjectName", identity.name);
            stringField(output, "projectPath", identity.projectPath);
        }
        output.append('}');
    }

    private static void firstStringField(StringBuilder output, String name, String value) {
        string(output, name);
        output.append(':');
        string(output, value);
    }

    private static void stringField(StringBuilder output, String name, String value) {
        output.append(',');
        firstStringField(output, name, value);
    }

    private static void string(StringBuilder output, String value) {
        output.append('"');
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            switch (current) {
                case '"':
                    output.append("\\\"");
                    break;
                case '\\':
                    output.append("\\\\");
                    break;
                case '\b':
                    output.append("\\b");
                    break;
                case '\f':
                    output.append("\\f");
                    break;
                case '\n':
                    output.append("\\n");
                    break;
                case '\r':
                    output.append("\\r");
                    break;
                case '\t':
                    output.append("\\t");
                    break;
                default:
                    if (current < 0x20) {
                        output.append(String.format("\\u%04x", (int) current));
                    } else if (Character.isHighSurrogate(current)) {
                        if (index + 1 == value.length() || !Character.isLowSurrogate(value.charAt(index + 1))) {
                            throw new IllegalArgumentException("protocol string contains an unpaired surrogate");
                        }
                        output.append(current).append(value.charAt(++index));
                    } else if (Character.isLowSurrogate(current)) {
                        throw new IllegalArgumentException("protocol string contains an unpaired surrogate");
                    } else {
                        output.append(current);
                    }
            }
        }
        output.append('"');
    }

    private static List<GeneratorResult> generatorResults(Object value) {
        List<Object> values = array(value, "generators");
        List<GeneratorResult> results = new ArrayList<>(values.size());
        for (int index = 0; index < values.size(); index++) {
            String location = "generators[" + index + "]";
            Map<String, Object> item = object(values.get(index), location);
            exactFields(item, GENERATOR_RESPONSE_FIELDS, location);
            String generatorId = matchingString(item.get("generatorId"), GENERATOR_ID, location + ".generatorId");
            String sourceTreeHash = matchingString(
                item.get("sourceTreeHash"),
                SHA_256,
                location + ".sourceTreeHash"
            );
            String resourceTreeHash = matchingString(
                item.get("resourceTreeHash"),
                SHA_256,
                location + ".resourceTreeHash"
            );
            results.add(new GeneratorResult(
                nonEmptyString(item.get("generatorName"), location + ".generatorName"),
                generatorId,
                nonEmptyString(item.get("sourceRoot"), location + ".sourceRoot"),
                nonEmptyString(item.get("resourceRoot"), location + ".resourceRoot"),
                sourceTreeHash,
                resourceTreeHash
            ));
        }
        return Collections.unmodifiableList(results);
    }

    private static List<Diagnostic> diagnostics(Object value) {
        List<Object> values = array(value, "diagnostics");
        List<Diagnostic> results = new ArrayList<>(values.size());
        for (int index = 0; index < values.size(); index++) {
            String location = "diagnostics[" + index + "]";
            Map<String, Object> item = object(values.get(index), location);
            List<String> fields = item.containsKey("source")
                ? Arrays.asList("severity", "code", "message", "source")
                : Arrays.asList("severity", "code", "message");
            exactFields(item, fields, location);
            Severity severity;
            try {
                severity = Severity.valueOf(nonEmptyString(item.get("severity"), location + ".severity"));
            } catch (IllegalArgumentException ignored) {
                throw new ProtocolException(location + ".severity is not supported");
            }
            SourceSpan source = item.containsKey("source") ? sourceSpan(item.get("source"), location + ".source") : null;
            results.add(new Diagnostic(
                severity,
                nonEmptyString(item.get("code"), location + ".code"),
                stringValue(item.get("message"), location + ".message"),
                source
            ));
        }
        return Collections.unmodifiableList(results);
    }

    private static SourceSpan sourceSpan(Object value, String location) {
        Map<String, Object> item = object(value, location);
        exactFields(item, Arrays.asList("path", "startByte", "endByte"), location);
        long start = nonNegativeInteger(item.get("startByte"), location + ".startByte");
        long end = nonNegativeInteger(item.get("endByte"), location + ".endByte");
        if (end < start) {
            throw new ProtocolException(location + ".endByte precedes startByte");
        }
        return new SourceSpan(nonEmptyString(item.get("path"), location + ".path"), start, end);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object value, String location) {
        if (!(value instanceof Map)) {
            throw new ProtocolException(location + " must be an object");
        }
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> array(Object value, String location) {
        if (!(value instanceof List)) {
            throw new ProtocolException(location + " must be an array");
        }
        return (List<Object>) value;
    }

    private static void exactFields(Map<String, Object> object, List<String> fields, String location) {
        if (!new ArrayList<>(object.keySet()).equals(fields)) {
            throw new ProtocolException(location + " fields or canonical order do not match protocol v1");
        }
    }

    private static void exactInteger(Object value, long expected, String location) {
        if (!(value instanceof Long) || ((Long) value).longValue() != expected) {
            throw new ProtocolException(location + " does not match protocol v1");
        }
    }

    private static long nonNegativeInteger(Object value, String location) {
        if (!(value instanceof Long) || ((Long) value).longValue() < 0) {
            throw new ProtocolException(location + " must be a non-negative integer");
        }
        return ((Long) value).longValue();
    }

    private static String matchingString(Object value, Pattern pattern, String location) {
        String result = nonEmptyString(value, location);
        if (!pattern.matcher(result).matches()) {
            throw new ProtocolException(location + " has invalid format");
        }
        return result;
    }

    private static String nonEmptyString(Object value, String location) {
        String result = stringValue(value, location);
        if (result.isEmpty()) {
            throw new ProtocolException(location + " must not be empty");
        }
        return result;
    }

    private static String stringValue(Object value, String location) {
        if (!(value instanceof String)) {
            throw new ProtocolException(location + " must be a string");
        }
        return (String) value;
    }

    private static String required(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be empty");
        }
        return value;
    }

    /** Generate or check execution mode. */
    enum CheckMode {
        /** Generate into configured roots. */
        GENERATE,
        /** Generate into isolated roots and compare. */
        CHECK,
    }

    /** Build system identity kind. */
    enum BuildKind {
        /** Maven group and artifact identity. */
        MAVEN,
        /** Gradle group, root name, and project path identity. */
        GRADLE,
    }

    /** Diagnostic severity emitted by the engine. */
    enum Severity {
        /** Generation cannot complete. */
        ERROR,
        /** Generation completed with an actionable warning. */
        WARNING,
        /** Informational generation status. */
        INFO,
    }

    /** Immutable protocol request. */
    static final class Request {
        private final String pluginVersion;
        private final String engineVersion;
        private final String runtimeVersion;
        private final String schemaPath;
        private final List<GeneratorRequest> generators;
        private final Map<String, String> environmentInputs;
        private final BuildIdentity buildIdentity;
        private final CheckMode checkMode;

        /** Constructs one closed protocol request. */
        Request(
            String pluginVersion,
            String engineVersion,
            String runtimeVersion,
            String schemaPath,
            List<GeneratorRequest> generators,
            Map<String, String> environmentInputs,
            BuildIdentity buildIdentity,
            CheckMode checkMode
        ) {
            this.pluginVersion = required(pluginVersion, "pluginVersion");
            this.engineVersion = required(engineVersion, "engineVersion");
            this.runtimeVersion = required(runtimeVersion, "runtimeVersion");
            this.schemaPath = required(schemaPath, "schemaPath");
            Objects.requireNonNull(generators, "generators");
            if (generators.isEmpty()) {
                throw new IllegalArgumentException("generators must not be empty");
            }
            List<GeneratorRequest> generatorCopy = new ArrayList<>(generators.size());
            for (GeneratorRequest generator : generators) {
                generatorCopy.add(Objects.requireNonNull(generator, "generator"));
            }
            this.generators = Collections.unmodifiableList(generatorCopy);
            Objects.requireNonNull(environmentInputs, "environmentInputs");
            TreeMap<String, String> sortedEnvironment = new TreeMap<>();
            for (Map.Entry<String, String> entry : environmentInputs.entrySet()) {
                String name = required(entry.getKey(), "environment input name");
                sortedEnvironment.put(name, Objects.requireNonNull(entry.getValue(), "environment input value"));
            }
            this.environmentInputs = Collections.unmodifiableMap(new LinkedHashMap<>(sortedEnvironment));
            this.buildIdentity = Objects.requireNonNull(buildIdentity, "buildIdentity");
            this.checkMode = Objects.requireNonNull(checkMode, "checkMode");
        }
    }

    /** One selected generator and its dedicated output and control roots. */
    static final class GeneratorRequest {
        private final String generatorName;
        private final String sourceOutputDirectory;
        private final String resourceOutputDirectory;
        private final String sourceControlDirectory;
        private final String resourceControlDirectory;

        /** Constructs one generator request. */
        GeneratorRequest(
            String generatorName,
            String sourceOutputDirectory,
            String resourceOutputDirectory,
            String sourceControlDirectory,
            String resourceControlDirectory
        ) {
            this.generatorName = required(generatorName, "generatorName");
            this.sourceOutputDirectory = required(sourceOutputDirectory, "sourceOutputDirectory");
            this.resourceOutputDirectory = required(resourceOutputDirectory, "resourceOutputDirectory");
            this.sourceControlDirectory = required(sourceControlDirectory, "sourceControlDirectory");
            this.resourceControlDirectory = required(resourceControlDirectory, "resourceControlDirectory");
        }
    }

    /** Closed Maven or Gradle build identity. */
    static final class BuildIdentity {
        private final BuildKind kind;
        private final String group;
        private final String name;
        private final String projectPath;

        private BuildIdentity(BuildKind kind, String group, String name, String projectPath) {
            this.kind = kind;
            this.group = Objects.requireNonNull(group, "group");
            this.name = required(name, "name");
            this.projectPath = projectPath;
        }

        /** Creates a Maven identity. */
        static BuildIdentity maven(String groupId, String artifactId) {
            return new BuildIdentity(BuildKind.MAVEN, groupId, artifactId, null);
        }

        /** Creates a Gradle identity. */
        static BuildIdentity gradle(String group, String rootProjectName, String projectPath) {
            String path = required(projectPath, "projectPath");
            if (!path.matches(":(?:[^:]+(?::[^:]+)*)?")) {
                throw new IllegalArgumentException("projectPath must be a canonical Gradle project path");
            }
            return new BuildIdentity(BuildKind.GRADLE, group, rootProjectName, path);
        }
    }

    /** Immutable validated response. */
    static final class Response {
        private final String engineVersion;
        private final String runtimeVersion;
        private final List<GeneratorResult> generators;
        private final List<Diagnostic> diagnostics;

        private Response(
            String engineVersion,
            String runtimeVersion,
            List<GeneratorResult> generators,
            List<Diagnostic> diagnostics
        ) {
            this.engineVersion = engineVersion;
            this.runtimeVersion = runtimeVersion;
            this.generators = generators;
            this.diagnostics = diagnostics;
        }

        /** Returns the engine version echoed by the executable. */
        String getEngineVersion() {
            return engineVersion;
        }

        /** Returns the runtime family version emitted by the executable. */
        String getRuntimeVersion() {
            return runtimeVersion;
        }

        /** Returns immutable generator results. */
        List<GeneratorResult> getGenerators() {
            return generators;
        }

        /** Returns immutable bounded diagnostics. */
        List<Diagnostic> getDiagnostics() {
            return diagnostics;
        }
    }

    /** One generated output result. */
    static final class GeneratorResult {
        private final String generatorName;
        private final String generatorId;
        private final String sourceRoot;
        private final String resourceRoot;
        private final String sourceTreeHash;
        private final String resourceTreeHash;

        private GeneratorResult(
            String generatorName,
            String generatorId,
            String sourceRoot,
            String resourceRoot,
            String sourceTreeHash,
            String resourceTreeHash
        ) {
            this.generatorName = generatorName;
            this.generatorId = generatorId;
            this.sourceRoot = sourceRoot;
            this.resourceRoot = resourceRoot;
            this.sourceTreeHash = sourceTreeHash;
            this.resourceTreeHash = resourceTreeHash;
        }

        /** Returns the selected generator name. */
        String getGeneratorName() {
            return generatorName;
        }

        /** Returns the stable generator identifier. */
        String getGeneratorId() {
            return generatorId;
        }

        /** Returns the canonical generated-source root. */
        String getSourceRoot() {
            return sourceRoot;
        }

        /** Returns the canonical generated-resource root. */
        String getResourceRoot() {
            return resourceRoot;
        }

        /** Returns the generated-source tree hash. */
        String getSourceTreeHash() {
            return sourceTreeHash;
        }

        /** Returns the generated-resource tree hash. */
        String getResourceTreeHash() {
            return resourceTreeHash;
        }
    }

    /** One sanitized engine diagnostic. */
    static final class Diagnostic {
        private final Severity severity;
        private final String code;
        private final String message;
        private final SourceSpan source;

        private Diagnostic(Severity severity, String code, String message, SourceSpan source) {
            this.severity = severity;
            this.code = code;
            this.message = message;
            this.source = source;
        }

        /** Returns the closed severity. */
        Severity getSeverity() {
            return severity;
        }

        /** Returns the stable diagnostic code. */
        String getCode() {
            return code;
        }

        /** Returns the bounded diagnostic message. */
        String getMessage() {
            return message;
        }

        /** Returns the optional schema source span, or {@code null}. */
        SourceSpan getSource() {
            return source;
        }
    }

    /** Byte-oriented source span in the canonical schema file. */
    static final class SourceSpan {
        private final String path;
        private final long startByte;
        private final long endByte;

        private SourceSpan(String path, long startByte, long endByte) {
            this.path = path;
            this.startByte = startByte;
            this.endByte = endByte;
        }

        /** Returns the canonical schema path. */
        String getPath() {
            return path;
        }

        /** Returns the inclusive start byte. */
        long getStartByte() {
            return startByte;
        }

        /** Returns the exclusive end byte. */
        long getEndByte() {
            return endByte;
        }
    }

    /** Sanitized malformed-protocol failure with no response content. */
    static final class ProtocolException extends IllegalArgumentException {
        private static final long serialVersionUID = 1L;

        /** Constructs a sanitized protocol failure. */
        ProtocolException(String message) {
            super(message);
        }

        /** Constructs a sanitized protocol failure with an internal cause. */
        ProtocolException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
