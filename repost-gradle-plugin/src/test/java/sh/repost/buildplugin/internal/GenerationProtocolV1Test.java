package sh.repost.buildplugin.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class GenerationProtocolV1Test {
    @Test
    void writesCanonicalRequestAndReadsClosedResponse() {
        Map<String, String> environment = new LinkedHashMap<>();
        environment.put("ZETA", "line\nvalue");
        environment.put("ALPHA", "quoted \"value\"");
        GenerationProtocolV1.Request request = new GenerationProtocolV1.Request(
            "1.0.0",
            "0.9.0",
            "1.0.0",
            "/workspace/repost/schema.repost",
            Collections.singletonList(new GenerationProtocolV1.GeneratorRequest(
                "javaSdk",
                "/workspace/generated/java",
                "/workspace/generated/resources",
                "/workspace/state/java",
                "/workspace/state/resources"
            )),
            environment,
            GenerationProtocolV1.BuildIdentity.maven("com.acme", "orders"),
            GenerationProtocolV1.CheckMode.GENERATE
        );

        assertEquals(
            "{\"protocolVersion\":1,\"pluginVersion\":\"1.0.0\",\"engineVersion\":\"0.9.0\"," +
                "\"runtimeVersion\":\"1.0.0\",\"descriptorVersion\":2," +
                "\"schemaPath\":\"/workspace/repost/schema.repost\",\"generators\":[{" +
                "\"generatorName\":\"javaSdk\",\"sourceOutputDirectory\":\"/workspace/generated/java\"," +
                "\"resourceOutputDirectory\":\"/workspace/generated/resources\"," +
                "\"sourceControlDirectory\":\"/workspace/state/java\"," +
                "\"resourceControlDirectory\":\"/workspace/state/resources\"}]," +
                "\"environmentInputs\":{\"ALPHA\":\"quoted \\\"value\\\"\",\"ZETA\":\"line\\nvalue\"}," +
                "\"buildIdentity\":{\"kind\":\"MAVEN\",\"groupId\":\"com.acme\",\"artifactId\":\"orders\"}," +
                "\"checkMode\":\"GENERATE\"}",
            GenerationProtocolV1.writeRequest(request)
        );

        GenerationProtocolV1.Response response = GenerationProtocolV1.readResponse(
            validResponseJson().getBytes(StandardCharsets.UTF_8)
        );
        assertEquals("0.9.0", response.getEngineVersion());
        assertEquals("0123456789abcdef", response.getGenerators().get(0).getGeneratorId());
        assertEquals("GENERATED", response.getDiagnostics().get(0).getCode());
        assertEquals(9, response.getDiagnostics().get(0).getSource().getEndByte());
    }

    @Test
    void rejectsMalformedUnknownDuplicateAndOversizedResponses() {
        assertThrows(
            GenerationProtocolV1.ProtocolException.class,
            () -> GenerationProtocolV1.readResponse("{}".getBytes(StandardCharsets.UTF_8))
        );
        assertThrows(
            GenerationProtocolV1.ProtocolException.class,
            () -> GenerationProtocolV1.readResponse(
                "{\"protocolVersion\":1,\"protocolVersion\":1}".getBytes(StandardCharsets.UTF_8)
            )
        );
        byte[] oversized = new byte[GenerationProtocolV1.MAX_RESPONSE_BYTES + 1];
        Arrays.fill(oversized, (byte) ' ');
        assertThrows(
            GenerationProtocolV1.ProtocolException.class,
            () -> GenerationProtocolV1.readResponse(oversized)
        );
    }

    @Test
    void rejectsUnknownFieldsInvalidUtf8AndRemoteValuesWithoutRetainingThem() {
        String unknown = validResponseJson().replace(
            "\"diagnostics\":[",
            "\"unexpected\":true,\"diagnostics\":["
        );
        assertThrows(
            GenerationProtocolV1.ProtocolException.class,
            () -> GenerationProtocolV1.readResponse(unknown.getBytes(StandardCharsets.UTF_8))
        );
        assertThrows(
            GenerationProtocolV1.ProtocolException.class,
            () -> GenerationProtocolV1.readResponse(new byte[] {(byte) 0xc3, (byte) 0x28})
        );

        String remoteValue = "secret-payload-value";
        String invalidSeverity = validResponseJson().replace("\"severity\":\"INFO\"", "\"severity\":\"" + remoteValue + "\"");
        GenerationProtocolV1.ProtocolException failure = assertThrows(
            GenerationProtocolV1.ProtocolException.class,
            () -> GenerationProtocolV1.readResponse(invalidSeverity.getBytes(StandardCharsets.UTF_8))
        );
        assertFalse(failure.getMessage().contains(remoteValue));
        assertNull(failure.getCause());
    }

    private static String validResponseJson() {
        return "{\"protocolVersion\":1,\"engineVersion\":\"0.9.0\"," +
            "\"runtimeVersion\":\"1.0.0\",\"descriptorVersion\":2,\"generators\":[{" +
            "\"generatorName\":\"javaSdk\",\"generatorId\":\"0123456789abcdef\"," +
            "\"sourceRoot\":\"/workspace/generated/java\",\"resourceRoot\":\"/workspace/generated/resources\"," +
            "\"sourceTreeHash\":\"sha256:" + "a".repeat(64) + "\"," +
            "\"resourceTreeHash\":\"sha256:" + "b".repeat(64) + "\"}]," +
            "\"diagnostics\":[{\"severity\":\"INFO\",\"code\":\"GENERATED\"," +
            "\"message\":\"complete\",\"source\":{\"path\":\"/workspace/repost/schema.repost\"," +
            "\"startByte\":0,\"endByte\":9}}]}";
    }
}
