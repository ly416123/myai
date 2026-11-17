package com.example.agent.configuration;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class AgentConfigRevisionTest {
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void sameConfigurationProducesStableRevisionIdAndHashRegardlessOfMapInsertionOrder() {
        var firstParameters = new LinkedHashMap<String, Object>();
        firstParameters.put("top_p", new BigDecimal("0.90"));
        firstParameters.put("temperature", new BigDecimal("0.20"));
        var secondParameters = new LinkedHashMap<String, Object>();
        secondParameters.put("temperature", new BigDecimal("0.20"));
        secondParameters.put("top_p", new BigDecimal("0.90"));

        var first = AgentConfigRevision.create(document(firstParameters), mapper);
        var second = AgentConfigRevision.create(document(secondParameters), mapper);

        assertEquals(first.contentHash(), second.contentHash());
        assertEquals(first.revisionId(), second.revisionId());
        assertEquals(64, first.contentHash().length());
    }

    @Test
    void anyRuntimeRelevantChangeProducesANewRevision() {
        var original = AgentConfigRevision.create(document(Map.of("temperature", new BigDecimal("0.2"))), mapper);
        var changed = AgentConfigRevision.create(new AgentConfigDocument(1, "New prompt",
                List.of(new SkillDefinition("travel", "Use confirmed travel facts.")),
                new ModelSettings("deepseek", "deepseek-flash", Map.of("temperature", new BigDecimal("0.2")),
                        "secret://providers/deepseek"),
                Set.of("calculator", "weather"), Map.of("travel-worker", "v2"),
                new RuntimeSettings(10, 20, 2, Duration.ofMinutes(5).toMillis(), 8, 2)), mapper);

        assertNotEquals(original.contentHash(), changed.contentHash());
        assertNotEquals(original.revisionId(), changed.revisionId());
    }

    @Test
    void modelConfigurationAcceptsOnlySecretReferencesAndKnownNonSensitiveParameters() {
        assertThrows(IllegalArgumentException.class, () -> new ModelSettings("deepseek", "deepseek-flash",
                Map.of("api_key", "sk-real-secret"), "secret://providers/deepseek"));
        assertThrows(IllegalArgumentException.class, () -> new ModelSettings("deepseek", "deepseek-flash",
                Map.of(), "sk-real-secret"));
    }

    @Test
    void rejectsObviousCredentialsInPromptAndSkills() {
        assertThrows(IllegalArgumentException.class, () -> new AgentConfigDocument(1,
                "Use key=sk-1234567890abcdef", List.of(),
                new ModelSettings("deepseek", "deepseek-flash", Map.of(), "secret://providers/deepseek"),
                Set.of(), Map.of(), new RuntimeSettings(10, 20, 2, 300_000, 10, 8, 2)));
        assertThrows(IllegalArgumentException.class, () -> new AgentConfigDocument(1, "Safe prompt",
                List.of(new SkillDefinition("credential", "Bearer abcdefghijklmnop")),
                new ModelSettings("deepseek", "deepseek-flash", Map.of(), "secret://providers/deepseek"),
                Set.of(), Map.of(), new RuntimeSettings(10, 20, 2, 300_000, 10, 8, 2)));
    }

    private static AgentConfigDocument document(Map<String, Object> parameters) {
        return new AgentConfigDocument(1, "Be concise.",
                List.of(new SkillDefinition("travel", "Use confirmed travel facts.")),
                new ModelSettings("deepseek", "deepseek-flash", parameters, "secret://providers/deepseek"),
                Set.of("weather", "calculator"), Map.of("travel-worker", "v2"),
                new RuntimeSettings(10, 20, 2, Duration.ofMinutes(5).toMillis(), 8, 2));
    }
}
