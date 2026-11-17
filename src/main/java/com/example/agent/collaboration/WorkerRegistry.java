package com.example.agent.collaboration;

import com.fasterxml.jackson.databind.JsonNode;
import com.networknt.schema.InputFormat;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** 只允许应用启动时静态注册的 Worker。 */
public final class WorkerRegistry {
    private static final SchemaRegistry SCHEMAS = SchemaRegistry.withDefaultDialect(
            SpecificationVersion.DRAFT_2020_12);
    private final Map<String, WorkerSpec> workers = new LinkedHashMap<>();

    public synchronized void register(WorkerSpec worker) {
        if (worker == null) throw new IllegalArgumentException("Worker is required");
        validateContract(worker);
        if (workers.putIfAbsent(worker.workerId(), worker) != null) {
            throw new IllegalArgumentException("Worker already registered");
        }
    }

    public synchronized void register(String id, String responsibility, String prompt, Set<String> tools) {
        register(new WorkerSpec(id, responsibility, prompt, tools.stream().sorted().toList(), 3, 8));
    }

    public synchronized Optional<WorkerSpec> find(String workerId) {
        return Optional.ofNullable(workers.get(workerId));
    }

    public synchronized List<WorkerSpec> all() { return List.copyOf(workers.values()); }

    private static void validateContract(WorkerSpec worker) {
        if (worker.version() == null) return; // legacy specs are migrated with static configuration in a later step
        compile(worker.inputSchema());
        compile(worker.outputSchema());
    }

    private static void compile(JsonNode schema) {
        rejectExternalReferences(schema);
        JsonNode required = schema.get("required");
        if (required != null) {
            JsonNode properties = schema.path("properties");
            if (!required.isArray()) throw new IllegalArgumentException("Worker schema required must be an array");
            required.forEach(field -> {
                if (!field.isTextual() || field.asText().isBlank() || !properties.has(field.asText())) {
                    throw new IllegalArgumentException("Worker schema required field is not declared");
                }
            });
        }
        SCHEMAS.getSchema(schema.toString(), InputFormat.JSON);
    }

    private static void rejectExternalReferences(JsonNode node) {
        if (node.isObject()) {
            node.fields().forEachRemaining(field -> {
                if (field.getKey().equals("$ref") || field.getKey().equals("$dynamicRef")
                        || field.getKey().equals("$recursiveRef")) {
                    JsonNode reference = field.getValue();
                    if (!reference.isTextual() || !reference.asText().startsWith("#")) {
                        throw new IllegalArgumentException("External worker schema references are disabled");
                    }
                }
                rejectExternalReferences(field.getValue());
            });
        } else if (node.isArray()) {
            node.forEach(WorkerRegistry::rejectExternalReferences);
        }
    }
}
