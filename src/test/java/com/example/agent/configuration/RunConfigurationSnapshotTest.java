package com.example.agent.configuration;

import com.example.agent.workflow.RunRecord;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class RunConfigurationSnapshotTest {
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void runCheckpointRetainsImmutableConfigurationSnapshotAcrossSerialization() throws Exception {
        AgentConfigDocument document = new AgentConfigDocument(1, "system", List.of(),
                new ModelSettings("deepseek", "deepseek-chat", Map.of(), "secret://deepseek/api-key"), Set.of("calculator"),
                Map.of(), new RuntimeSettings(10, 8, 2, 60_000, 10, 2));
        AgentConfigRevision revision = AgentConfigRevision.create(document, mapper);
        RunConfigurationSnapshot snapshot = new RunConfigurationSnapshot("release-1", revision.revisionId(),
                revision.contentHash(), document);
        RunRecord run = RunRecord.create("snapshot-run", "question", Duration.ofMinutes(2));
        run.setConfigurationSnapshot(snapshot);

        RunRecord loaded = mapper.readValue(mapper.writeValueAsBytes(run), RunRecord.class);

        assertEquals(snapshot, loaded.configurationSnapshot());
        assertThrows(UnsupportedOperationException.class,
                () -> loaded.configurationSnapshot().document().allowedTools().add("weather"));
    }

    @Test
    void rejectsSnapshotWhenDocumentDoesNotMatchContentHash() {
        AgentConfigDocument document = new AgentConfigDocument(1, "system", List.of(),
                new ModelSettings("deepseek", "deepseek-chat", Map.of(), "secret://deepseek/api-key"), Set.of("calculator"),
                Map.of(), new RuntimeSettings(10, 8, 2, 60_000, 10, 2));
        assertThrows(IllegalArgumentException.class,
                () -> new RunConfigurationSnapshot("release-1", "rev-" + "a".repeat(64), "a".repeat(64), document));
    }

    @Test
    void runCannotSilentlySwitchPinnedConfiguration() {
        AgentConfigDocument first = new AgentConfigDocument(1, "first", List.of(),
                new ModelSettings("deepseek", "deepseek-chat", Map.of(), "secret://deepseek/api-key"), Set.of(),
                Map.of(), new RuntimeSettings(10, 8, 2, 60_000, 10, 2));
        AgentConfigDocument second = new AgentConfigDocument(1, "second", List.of(),
                new ModelSettings("deepseek", "deepseek-chat", Map.of(), "secret://deepseek/api-key"), Set.of(),
                Map.of(), new RuntimeSettings(10, 8, 2, 60_000, 10, 2));
        var firstRevision = AgentConfigRevision.create(first, mapper);
        var secondRevision = AgentConfigRevision.create(second, mapper);
        RunRecord run = RunRecord.create("pinned", "question", Duration.ofMinutes(1));
        run.setConfigurationSnapshot(new RunConfigurationSnapshot("release-1", firstRevision.revisionId(),
                firstRevision.contentHash(), first));
        assertThrows(IllegalStateException.class, () -> run.setConfigurationSnapshot(new RunConfigurationSnapshot(
                "release-2", secondRevision.revisionId(), secondRevision.contentHash(), second)));
    }
}
