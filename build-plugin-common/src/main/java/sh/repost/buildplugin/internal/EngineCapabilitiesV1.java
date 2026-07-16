package sh.repost.buildplugin.internal;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/** Closed native schema-engine compatibility handshake. */
final class EngineCapabilitiesV1 {
    private static final long PROTOCOL_VERSION = 1L;
    private static final long DESCRIPTOR_VERSION = 2L;
    private static final List<String> FIELDS = Arrays.asList(
        "protocolVersion",
        "engineVersion",
        "runtimeVersion",
        "descriptorVersion"
    );

    private EngineCapabilitiesV1() {
    }

    static void verify(byte[] response, String expectedEngineVersion, String expectedRuntimeVersion) {
        final Object parsed;
        try {
            parsed = StrictJson.parse(response);
        } catch (GenerationProtocolV1.ProtocolException exception) {
            throw new CapabilityException("schema engine capabilities are malformed");
        }
        if (!(parsed instanceof Map)) {
            throw new CapabilityException("schema engine capabilities are malformed");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> root = (Map<String, Object>) parsed;
        if (!new ArrayList<>(root.keySet()).equals(FIELDS)) {
            throw new CapabilityException("schema engine capabilities are malformed");
        }
        if (!Long.valueOf(PROTOCOL_VERSION).equals(root.get("protocolVersion"))) {
            throw new CapabilityException("schema engine protocol version does not match the plugin");
        }
        if (!expectedEngineVersion.equals(root.get("engineVersion"))) {
            throw new CapabilityException("schema engine version does not match the requested version");
        }
        if (!expectedRuntimeVersion.equals(root.get("runtimeVersion"))) {
            throw new CapabilityException("schema engine runtime version does not match the project runtime");
        }
        if (!Long.valueOf(DESCRIPTOR_VERSION).equals(root.get("descriptorVersion"))) {
            throw new CapabilityException("schema engine descriptor version does not match the runtime");
        }
    }

    static final class CapabilityException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        CapabilityException(String message) {
            super(message);
        }
    }
}
