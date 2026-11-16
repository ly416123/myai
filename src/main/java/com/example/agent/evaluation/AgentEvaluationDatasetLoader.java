package com.example.agent.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;

/** Loads a version-controlled evaluation fixture without logging its prompt text. */
public final class AgentEvaluationDatasetLoader {
    private AgentEvaluationDatasetLoader() { }

    public static AgentEvaluationDataset load(Path path, ObjectMapper mapper) {
        return loadSnapshot(path, mapper).dataset();
    }

    public static AgentEvaluationDatasetSnapshot loadSnapshot(Path path, ObjectMapper mapper) {
        if (path == null || mapper == null || !Files.isRegularFile(path)) {
            throw new IllegalArgumentException("Evaluation dataset file is unavailable");
        }
        try {
            var root = mapper.readTree(Files.readAllBytes(path));
            if (!(root instanceof ObjectNode object)) throw new IllegalArgumentException("Evaluation dataset is invalid");
            String datasetId = object.path("datasetId").asText("agent-runtime");
            int formatVersion = object.path("formatVersion").asInt(1);
            if (formatVersion != 1) throw new IllegalArgumentException("Unsupported evaluation dataset format");

            ObjectNode datasetNode = object.deepCopy();
            datasetNode.remove("datasetId");
            datasetNode.remove("formatVersion");
            AgentEvaluationDataset dataset = mapper.treeToValue(datasetNode, AgentEvaluationDataset.class);
            validateEvaluatorIds(dataset);
            var canonicalScenarios = dataset.scenarios().stream().map(scenario -> new AgentEvaluationScenario(
                    scenario.id(), scenario.workflow(), scenario.prompt(), scenario.expectedTools(), scenario.expectedPlanRoute(),
                    scenario.expectedWorkers(), scenario.requiredAnswerPhrases(), scenario.forbiddenAnswerPhrases(),
                    scenario.evaluatorIds(), scenario.expectedFailureCategory(), canonicalize(scenario.responseJsonSchema(), mapper)))
                    .toList();
            dataset = new AgentEvaluationDataset(dataset.version(), canonicalScenarios);
            byte[] canonical = canonicalMapper(mapper).writeValueAsBytes(
                    new CanonicalDataset(datasetId, formatVersion, dataset.version(), dataset.scenarios()));
            return new AgentEvaluationDatasetSnapshot(datasetId, formatVersion, sha256(canonical), dataset);
        } catch (IOException | NoSuchAlgorithmException failure) {
            // Avoid exposing fixture text, file contents, or parser excerpts in process logs.
            throw new IllegalArgumentException("Evaluation dataset file is invalid");
        }
    }

    private static void validateEvaluatorIds(AgentEvaluationDataset dataset) {
        for (AgentEvaluationScenario scenario : dataset.scenarios()) {
            if (scenario.evaluatorIds().stream().anyMatch(id -> !EvaluationEvaluatorIds.isSupported(id))) {
                throw new IllegalArgumentException("Evaluation dataset references an unsupported evaluator");
            }
            if (scenario.responseJsonSchema() != null) EvaluationJsonSchema.compile(scenario.responseJsonSchema());
        }
    }

    private static ObjectMapper canonicalMapper(ObjectMapper mapper) {
        return mapper.copy().configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true)
                .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
    }

    private static com.fasterxml.jackson.databind.JsonNode canonicalize(
            com.fasterxml.jackson.databind.JsonNode node, ObjectMapper mapper) {
        if (node == null) return null;
        if (node.isObject()) {
            var sorted = mapper.createObjectNode();
            var names = new java.util.ArrayList<String>(); node.fieldNames().forEachRemaining(names::add);
            names.sort(String::compareTo);
            names.forEach(name -> sorted.set(name, canonicalize(node.get(name), mapper)));
            return sorted;
        }
        if (node.isArray()) {
            var sorted = mapper.createArrayNode(); node.forEach(item -> sorted.add(canonicalize(item, mapper)));
            return sorted;
        }
        return node.deepCopy();
    }

    private static String sha256(byte[] content) throws NoSuchAlgorithmException {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
    }

    private record CanonicalDataset(String datasetId, int formatVersion, String version,
                                    java.util.List<AgentEvaluationScenario> scenarios) { }
}
