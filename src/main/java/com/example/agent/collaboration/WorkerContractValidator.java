package com.example.agent.collaboration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.InputFormat;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;

/** Worker 输入输出 JSON Schema 的编译与数据校验。 */
public final class WorkerContractValidator {
    private static final SchemaRegistry REGISTRY = SchemaRegistry.withDefaultDialect(
            SpecificationVersion.DRAFT_2020_12);
    private final ObjectMapper json;

    public WorkerContractValidator(ObjectMapper json) {
        this.json = json;
    }

    public void validateSchema(JsonNode schema) {
        compile(schema);
    }

    public boolean isValidInput(JsonNode schema, JsonNode input) {
        return input != null && compile(schema).validate(input).isEmpty();
    }

    public boolean isValidOutput(JsonNode schema, JsonNode output) {
        return output != null && output.isObject() && compile(schema).validate(output).isEmpty();
    }

    public JsonNode resolveInput(WorkerSpec worker, PlannedTask task, String parentRequest,
                                 java.util.Map<String, ChildTaskRecord> completedDependencies) {
        ObjectNode input = json.createObjectNode();
        java.util.Set<String> targets = new java.util.HashSet<>();
        JsonNode properties = worker.inputSchema().path("properties");
        for (InputBinding binding : task.inputBindings()) {
            if (binding == null || binding.field() == null || binding.field().isBlank()
                    || !properties.has(binding.field()) || !targets.add(binding.field())) {
                String field = binding == null ? "*" : String.valueOf(binding.field());
                throw issue(field, "PLAN", "INVALID_OR_DUPLICATE_INPUT_FIELD");
            }
            JsonNode value;
            if (binding.source() == InputBinding.Source.PARENT_REQUEST) {
                if (binding.dependencyTaskId() != null || binding.jsonPointer() != null) {
                    throw issue(binding.field(), "PARENT_REQUEST", "INVALID_BINDING_SHAPE");
                }
                value = binding.parentValue();
            } else {
                if (binding.source() != InputBinding.Source.DEPENDENCY_OUTPUT
                        || binding.parentValue() != null || binding.dependencyTaskId() == null
                        || !task.dependencies().contains(binding.dependencyTaskId())
                        || binding.jsonPointer() == null
                        || !(binding.jsonPointer().isEmpty() || binding.jsonPointer().startsWith("/"))) {
                    throw issue(binding.field(), "DEPENDENCY_OUTPUT", "INVALID_BINDING_SHAPE");
                }
                ChildTaskRecord dependency = completedDependencies.get(binding.dependencyTaskId());
                if (dependency == null || dependency.status() != TaskStatus.SUCCEEDED || dependency.result() == null) {
                    throw issue(binding.field(), "DEPENDENCY_OUTPUT", "DEPENDENCY_RESULT_UNAVAILABLE");
                }
                try {
                    JsonNode output = json.readTree(dependency.result());
                    value = output.at(binding.jsonPointer());
                } catch (Exception invalidJson) {
                    throw issue(binding.field(), "DEPENDENCY_OUTPUT", "DEPENDENCY_OUTPUT_INVALID_JSON");
                }
                if (value.isMissingNode()) throw issue(binding.field(), "DEPENDENCY_OUTPUT", "JSON_POINTER_NOT_FOUND");
            }
            if (value == null || value.isNull()) throw issue(binding.field(), binding.source().name(), "INPUT_VALUE_EMPTY");
            input.set(binding.field(), value);
        }
        if (!isValidInput(worker.inputSchema(), input)) {
            java.util.List<WorkerContractIssue> issues = new java.util.ArrayList<>();
            worker.inputSchema().path("required").forEach(field -> {
                if (!input.has(field.asText())) issues.add(new WorkerContractIssue(field.asText(), "UNBOUND",
                        "MISSING_REQUIRED_INPUT"));
            });
            if (issues.isEmpty()) issues.add(new WorkerContractIssue("*", "BINDINGS", "INPUT_SCHEMA_MISMATCH"));
            throw new WorkerContractException(issues);
        }
        return input;
    }

    private static WorkerContractException issue(String field, String source, String reason) {
        return new WorkerContractException(java.util.List.of(new WorkerContractIssue(field, source, reason)));
    }

    private static Schema compile(JsonNode schema) {
        if (schema == null || !schema.isObject() || !"object".equals(schema.path("type").asText())) {
            throw new IllegalArgumentException("Worker contract schema must declare an object root");
        }
        rejectExternalReferences(schema);
        return REGISTRY.getSchema(schema.toString(), InputFormat.JSON);
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
            node.forEach(WorkerContractValidator::rejectExternalReferences);
        }
    }
}
