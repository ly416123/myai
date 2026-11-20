package com.example.agent.configuration;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AgentConfigurationDiffTest {
    @Test
    void reportsChangedPathsWithoutEmittingPromptOrCredentialReferences() {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        AgentConfigStore store = mock(AgentConfigStore.class);
        var first = revision("private prompt A", "secret://provider/credential-a", mapper);
        var second = revision("private prompt B", "secret://provider/credential-b", mapper);
        when(store.findRevision(first.revisionId())).thenReturn(java.util.Optional.of(first));
        when(store.findRevision(second.revisionId())).thenReturn(java.util.Optional.of(second));
        var service = new AgentConfigurationService(store, mock(EvaluationReleaseGate.class), mapper);

        var changes = service.diff(first.revisionId(), second.revisionId());

        assertTrue(changes.stream().anyMatch(change -> change.path().equals("systemPrompt")
                && change.before().equals("<redacted>")));
        assertTrue(changes.stream().anyMatch(change -> change.path().equals("model.credentialRef")
                && change.after().equals("<redacted>")));
        assertFalse(changes.toString().contains("private prompt"));
        assertFalse(changes.toString().contains("credential-a"));
    }

    private static AgentConfigRevision revision(String prompt, String credentialRef, ObjectMapper mapper) {
        return AgentConfigRevision.create(new AgentConfigDocument(1, prompt, List.of(),
                new ModelSettings("deepseek", "deepseek-chat", Map.of(), credentialRef), Set.of(), Map.of(),
                new RuntimeSettings(5, 5, 1, 30_000, 5, 2)), mapper);
    }
}
