package com.example.agent.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class AgentEvaluationDatasetLoaderTest {
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void snapshotUsesStableIdentityAndCanonicalContentHash(@TempDir Path directory) throws Exception {
        Path compact = directory.resolve("compact.json");
        Path formatted = directory.resolve("formatted.json");
        Files.writeString(compact, "{\"version\":\"v-test\",\"scenarios\":[{\"id\":\"case-a\","
                + "\"workflow\":\"SINGLE_AGENT\",\"prompt\":\"synthetic\",\"expectedTools\":[]}]}");
        Files.writeString(formatted, "{\n  \"version\": \"v-test\",\n  \"scenarios\": [\n    {"
                + "\"id\": \"case-a\", \"workflow\": \"SINGLE_AGENT\", \"prompt\": \"synthetic\","
                + " \"expectedTools\": []}\n  ]\n}");

        var first = AgentEvaluationDatasetLoader.loadSnapshot(compact, mapper);
        var second = AgentEvaluationDatasetLoader.loadSnapshot(formatted, mapper);

        assertEquals("agent-runtime", first.datasetId());
        assertEquals(1, first.formatVersion());
        assertEquals("v-test", first.datasetVersion());
        assertEquals(first.contentSha256(), second.contentSha256());
        assertEquals(64, first.contentSha256().length());
    }

    @Test
    void rejectsUnsupportedDatasetFormatBeforeEvaluation(@TempDir Path directory) throws Exception {
        Path path = directory.resolve("unsupported.json");
        Files.writeString(path, "{\"datasetId\":\"agent-runtime\",\"formatVersion\":99,"
                + "\"version\":\"v1\",\"scenarios\":[{\"id\":\"case-a\","
                + "\"workflow\":\"SINGLE_AGENT\",\"prompt\":\"synthetic\",\"expectedTools\":[]}]}");

        assertThrows(IllegalArgumentException.class, () -> AgentEvaluationDatasetLoader.loadSnapshot(path, mapper));
    }

    @Test
    void rejectsUnknownEvaluatorBeforeAnyRunCanStart(@TempDir Path directory) throws Exception {
        Path path = directory.resolve("unknown-evaluator.json");
        Files.writeString(path, "{\"datasetId\":\"agent-runtime\",\"formatVersion\":1,"
                + "\"version\":\"v1\",\"scenarios\":[{\"id\":\"case-a\","
                + "\"workflow\":\"SINGLE_AGENT\",\"prompt\":\"synthetic\",\"expectedTools\":[],"
                + "\"evaluatorIds\":[\"not-registered\"]}]}");

        assertThrows(IllegalArgumentException.class, () -> AgentEvaluationDatasetLoader.loadSnapshot(path, mapper));
    }

    @Test
    void rejectsDuplicateCaseIdsDuringDatasetLoading(@TempDir Path directory) throws Exception {
        Path path = directory.resolve("duplicate.json");
        Files.writeString(path, "{\"version\":\"v1\",\"scenarios\":["
                + "{\"id\":\"same\",\"workflow\":\"SINGLE_AGENT\",\"prompt\":\"one\",\"expectedTools\":[]},"
                + "{\"id\":\"same\",\"workflow\":\"SINGLE_AGENT\",\"prompt\":\"two\",\"expectedTools\":[]}]} ");

        assertThrows(IllegalArgumentException.class, () -> AgentEvaluationDatasetLoader.loadSnapshot(path, mapper));
    }

    @Test
    void validatesStructuredOutputSchemaAndRejectsRemoteReferences(@TempDir Path directory) throws Exception {
        Path valid = directory.resolve("valid-schema.json");
        Files.writeString(valid, "{\"version\":\"v1\",\"scenarios\":[{\"id\":\"structured\","
                + "\"workflow\":\"SINGLE_AGENT\",\"prompt\":\"synthetic\",\"expectedTools\":[],"
                + "\"responseJsonSchema\":{\"type\":\"object\",\"required\":[\"ok\"]}}]}");
        assertNotNull(AgentEvaluationDatasetLoader.loadSnapshot(valid, mapper).dataset().scenarios().getFirst().responseJsonSchema());
        Path remote = directory.resolve("remote-schema.json");
        Files.writeString(remote, "{\"version\":\"v1\",\"scenarios\":[{\"id\":\"structured\","
                + "\"workflow\":\"SINGLE_AGENT\",\"prompt\":\"synthetic\",\"expectedTools\":[],"
                + "\"responseJsonSchema\":{\"$ref\":\"https://example.invalid/schema.json\"}}]}");
        assertThrows(IllegalArgumentException.class, () -> AgentEvaluationDatasetLoader.loadSnapshot(remote, mapper));
    }

    @Test
    void normalizesSchemaObjectPropertyOrderForDatasetHash(@TempDir Path directory) throws Exception {
        Path first = directory.resolve("schema-a.json");
        Path second = directory.resolve("schema-b.json");
        Files.writeString(first, "{\"version\":\"v1\",\"scenarios\":[{\"id\":\"case-a\","
                + "\"workflow\":\"SINGLE_AGENT\",\"prompt\":\"synthetic\",\"expectedTools\":[],"
                + "\"responseJsonSchema\":{\"type\":\"object\",\"additionalProperties\":false}}]}");
        Files.writeString(second, "{\"version\":\"v1\",\"scenarios\":[{\"id\":\"case-a\","
                + "\"workflow\":\"SINGLE_AGENT\",\"prompt\":\"synthetic\",\"expectedTools\":[],"
                + "\"responseJsonSchema\":{\"additionalProperties\":false,\"type\":\"object\"}}]}");
        assertEquals(AgentEvaluationDatasetLoader.loadSnapshot(first, mapper).contentSha256(),
                AgentEvaluationDatasetLoader.loadSnapshot(second, mapper).contentSha256());
    }
}
