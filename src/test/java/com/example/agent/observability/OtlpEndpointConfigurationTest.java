package com.example.agent.observability;

import org.junit.jupiter.api.Test;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.boot.env.YamlPropertySourceLoader;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class OtlpEndpointConfigurationTest {
    @Test
    void exposesIndependentMetricAndTraceCollectorEndpoints() throws Exception {
        var environment = new StandardEnvironment();
        var yaml = new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yml"));
        environment.getPropertySources().addFirst(yaml.getFirst());
        environment.getPropertySources().addFirst(new MapPropertySource("test-env", Map.of(
                "AGENT_OTLP_METRICS_ENDPOINT", "http://collector.example:4318/v1/metrics",
                "AGENT_OTLP_TRACES_ENDPOINT", "http://collector.example:4318/v1/traces",
                "AGENT_OTLP_METRICS_ENABLED", "true",
                "AGENT_OTLP_TRACES_ENABLED", "true")));

        assertEquals("http://collector.example:4318/v1/metrics",
                environment.getProperty("management.otlp.metrics.export.url"));
        assertEquals("http://collector.example:4318/v1/traces",
                environment.getProperty("management.otlp.tracing.endpoint"));
        assertEquals(true, environment.getProperty("management.otlp.metrics.export.enabled", Boolean.class));
        assertEquals(true, environment.getProperty("management.otlp.tracing.export.enabled", Boolean.class));
    }
}
