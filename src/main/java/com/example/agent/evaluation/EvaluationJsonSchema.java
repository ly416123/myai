package com.example.agent.evaluation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.InputFormat;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;

/** Compiles Draft 2020-12 output schemas and rejects remote references before evaluation begins. */
final class EvaluationJsonSchema {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final SchemaRegistry REGISTRY = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12);
    private EvaluationJsonSchema() { }
    static Schema compile(JsonNode schema) {
        if (schema == null || !schema.isObject()) throw new IllegalArgumentException("Response JSON Schema must be an object");
        rejectExternalReferences(schema);
        return REGISTRY.getSchema(schema.toString(), InputFormat.JSON);
    }
    static boolean matches(JsonNode schema, String answer) {
        if (answer == null) return false;
        JsonNode instance;
        try { instance = JSON.readTree(answer); }
        catch (Exception invalidJson) { return false; }
        return compile(schema).validate(instance).isEmpty();
    }
    private static void rejectExternalReferences(JsonNode node) {
        if (node.isObject()) node.fields().forEachRemaining(field -> {
            if (field.getKey().equals("$ref") || field.getKey().equals("$dynamicRef")
                    || field.getKey().equals("$recursiveRef")) {
                if (!field.getValue().isTextual() || !field.getValue().asText().startsWith("#"))
                    throw new IllegalArgumentException("Remote evaluation schema references are disabled");
            }
            rejectExternalReferences(field.getValue());
        });
        else if (node.isArray()) node.forEach(EvaluationJsonSchema::rejectExternalReferences);
    }
}
