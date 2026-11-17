package com.example.agent.collaboration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class WorkerRegistryContractTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void registersVersionedWorkerWithInputAndOutputSchemas() throws Exception {
        WorkerRegistry registry = new WorkerRegistry();
        ObjectNode input = (ObjectNode) mapper.readTree("""
                {"type":"object","properties":{"city":{"type":"string"}},"required":["city"]}
                """);
        ObjectNode output = (ObjectNode) mapper.readTree("""
                {"type":"object","properties":{"forecast":{"type":"string"}},"required":["forecast"]}
                """);
        var worker = new WorkerSpec("weather", "Weather lookup", "prompt", List.<String>of(), 3, 4,
                "v1", "Weather query", input, "Weather query input", "Forecast result", output);

        registry.register(worker);

        WorkerSpec registered = registry.find("weather").orElseThrow();
        assertEquals("v1", registered.version());
        assertEquals(input, registered.inputSchema());
        assertEquals(output, registered.outputSchema());
        input.put("unexpected", true);
        assertFalse(registered.inputSchema().has("unexpected"), "registry must not retain caller's mutable Schema");
    }

    @Test
    void rejectsMissingVersionMalformedSchemaAndExternalReferences() throws Exception {
        WorkerRegistry registry = new WorkerRegistry();
        ObjectNode valid = (ObjectNode) mapper.readTree("{\"type\":\"object\"}");
        assertThrows(IllegalArgumentException.class, () -> registry.register(worker("", "v1", valid, valid)));
        assertThrows(IllegalArgumentException.class, () -> registry.register(worker("worker", "v1", mapper.createObjectNode(), valid)));
        assertThrows(IllegalArgumentException.class, () -> registry.register(worker("worker", "v1",
                mapper.readTree("{\"type\":\"not-a-json-schema-type\"}"), valid)));
        assertThrows(IllegalArgumentException.class, () -> registry.register(worker("worker", "v1",
                mapper.readTree("{\"type\":\"object\",\"required\":[\"missing\"]}"), valid)));
        assertThrows(IllegalArgumentException.class, () -> registry.register(worker("v1",
                mapper.readTree("{\"$ref\":\"https://example.invalid/schema.json\"}"), valid)));
        assertThrows(IllegalArgumentException.class, () -> registry.register(worker("worker", " ", valid, valid)));
    }

    @Test
    void rejectsDuplicateWorkerIds() throws Exception {
        WorkerRegistry registry = new WorkerRegistry();
        ObjectNode schema = (ObjectNode) mapper.readTree("{\"type\":\"object\"}");
        registry.register(worker("worker", "v1", schema, schema));
        assertThrows(IllegalArgumentException.class, () -> registry.register(worker("worker", "v2", schema, schema)));
    }

    private WorkerSpec worker(String version, com.fasterxml.jackson.databind.JsonNode input,
                              com.fasterxml.jackson.databind.JsonNode output) {
        return worker("worker", version, input, output);
    }

    private WorkerSpec worker(String id, String version, com.fasterxml.jackson.databind.JsonNode input,
                              com.fasterxml.jackson.databind.JsonNode output) {
        return new WorkerSpec(id, "test", "prompt", List.<String>of(), 2, 2,
                version, "summary", input, "input", "output", output);
    }
}
