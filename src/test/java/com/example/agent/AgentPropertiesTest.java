package com.example.agent;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AgentPropertiesTest {
    @Test
    void providerEndpointDefaultsToDeepSeekAndCanBeOverridden() {
        var properties = new AgentProperties();

        assertEquals("https://api.deepseek.com", properties.getModelBaseUrl());
        properties.setModelBaseUrl("http://127.0.0.1:8080/v1");
        assertEquals("http://127.0.0.1:8080/v1", properties.getModelBaseUrl());
    }

    @Test
    void evaluationReportsDoNotUseMavenCleanOutputDirectory() {
        assertEquals(Path.of("var/agent-evaluations"), new AgentProperties().getEvaluationReportDirectory());
    }

    @Test
    void parentRunLeaseAndRenewalDefaultsAreShorterThanTaskDeadline() {
        var properties = new AgentProperties();

        assertEquals(Duration.ofSeconds(30), properties.getRunLeaseDuration());
        assertEquals(Duration.ofSeconds(10), properties.getRunLeaseRenewalInterval());
    }
}
