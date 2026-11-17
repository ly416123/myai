package com.example.agent.configuration;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ActiveConfigurationProviderTest {
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();

    @Test
    void newRunPinsResolvedActiveReleaseAndDoesNotConsultFutureChanges() {
        AgentConfigStore store = mock(AgentConfigStore.class);
        var document = document("prompt");
        var revision = AgentConfigRevision.create(document, JSON);
        var release = release("release-active", revision);
        when(store.assignment("prod", "assistant"))
                .thenReturn(java.util.Optional.of(assignment("release-active", null, 0)));
        when(store.findRelease("release-active")).thenReturn(java.util.Optional.of(release));
        when(store.findRevision(revision.revisionId())).thenReturn(java.util.Optional.of(revision));

        var snapshot = new ActiveConfigurationProvider(store).resolve("prod", "assistant", "run-1");

        assertEquals("release-active", snapshot.releaseId());
        assertEquals(revision.contentHash(), snapshot.contentHash());
        assertEquals(document, snapshot.document());
        verify(store).assignment("prod", "assistant");
    }

    @Test
    void stableRunIdChoosesSameGrayReleaseAndMissingAssignmentFailsClosed() {
        var assignment = assignment("stable", "candidate", 50);
        assertEquals(ActiveConfigurationProvider.selectRelease(assignment, "run-42"),
                ActiveConfigurationProvider.selectRelease(assignment, "run-42"));
        AgentConfigStore store = mock(AgentConfigStore.class);
        when(store.assignment("prod", "assistant")).thenReturn(java.util.Optional.empty());
        assertThrows(IllegalStateException.class,
                () -> new ActiveConfigurationProvider(store).resolve("prod", "assistant", "run-42"));
    }

    @Test
    void grayAssignmentIsStableAcrossResolversAndProducesObservableBuckets() {
        var assignment = assignment("stable", "candidate", 50);
        var selected = new java.util.ArrayList<String>();
        for (int index = 0; index < 1000; index++) {
            String runId = "sample-run-" + index;
            String first = ActiveConfigurationProvider.selectRelease(assignment, runId);
            String second = ActiveConfigurationProvider.selectRelease(assignment, runId);
            assertEquals(first, second);
            selected.add(first);
        }
        long candidates = selected.stream().filter("candidate"::equals).count();
        assertTrue(candidates >= 400 && candidates <= 600, "candidate buckets=" + candidates);
    }

    private static ActiveConfigAssignment assignment(String active, String candidate, int percent) {
        return new ActiveConfigAssignment("prod", "assistant", active, candidate, percent, 1, Instant.now());
    }

    private static AgentConfigDocument document(String prompt) {
        return new AgentConfigDocument(1, prompt, List.of(),
                new ModelSettings("deepseek", "deepseek-chat", Map.of(), "secret://deepseek/api-key"), Set.of(),
                Map.of(), new RuntimeSettings(10, 10, 1, 60_000, 10, 2));
    }

    private static AgentConfigRelease release(String id, AgentConfigRevision revision) {
        return new AgentConfigRelease(id, revision.revisionId(), revision.contentHash(), "eval-1", null,
                JSON.createObjectNode().put("policy", "test"), null, "test", "test", Instant.now());
    }
}
