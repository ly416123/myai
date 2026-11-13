package com.example.agent.observability;

import com.example.agent.workflow.RunQueueSnapshot;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.annotation.DirtiesContext;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@Testcontainers(disabledWithoutDocker = true)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(classes = OtlpExporterIntegrationTest.TelemetryTestApplication.class, properties = {
        "spring.main.web-application-type=none",
        "AGENT_OTLP_METRICS_ENABLED=true",
        "AGENT_OTLP_TRACES_ENABLED=true",
        "AGENT_TRACE_SAMPLING=1",
        "management.otlp.metrics.export.step=1s",
        "management.tracing.opentelemetry.export.schedule-delay=1s"
})
class OtlpExporterIntegrationTest {
    private static final Set<String> EXPECTED_ALERT_METRICS = Set.of(
            "myai_agent_worker_queue_oldest_seconds",
            "myai_agent_worker_lease_expired_leases",
            "myai_agent_worker_run_failed_recent_runs",
            "myai_hikaricp_connections_active",
            "myai_hikaricp_connections_max");
    private static final Set<String> SUPPORTED_PROMQL_FUNCTIONS = Set.of("max", "sum");

    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    private static final GenericContainer<?> COLLECTOR = new GenericContainer<>(
            DockerImageName.parse("otel/opentelemetry-collector-contrib:0.165.0"))
            .withCopyToContainer(MountableFile.forClasspathResource("otel/collector-config.yaml"),
                    "/etc/otelcol-contrib/config.yaml")
            .withCommand("--config=/etc/otelcol-contrib/config.yaml")
            .withExposedPorts(4318, 8889)
            .waitingFor(org.testcontainers.containers.wait.strategy.Wait.forHttp("/metrics")
                    .forPort(8889).forStatusCode(200).withStartupTimeout(Duration.ofSeconds(45)));

    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @Autowired
    private MeterRegistry meters;

    @Autowired
    private AgentTelemetry telemetry;

    @Autowired
    private ObservationRegistry observations;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry properties) {
        properties.add("AGENT_OTLP_METRICS_ENDPOINT", () -> collectorEndpoint("/v1/metrics"));
        properties.add("AGENT_OTLP_TRACES_ENDPOINT", () -> collectorEndpoint("/v1/traces"));
    }

    @SpringBootConfiguration(proxyBeanMethods = false)
    @EnableAutoConfiguration(exclude = FlywayAutoConfiguration.class)
    static class TelemetryTestApplication {
        @Bean
        AgentTelemetry agentTelemetry(MeterRegistry meters, ObservationRegistry observations) {
            return new AgentTelemetry(meters, observations);
        }

        @Bean(destroyMethod = "close")
        HikariDataSource dataSource() {
            HikariConfig config = new HikariConfig();
            config.setJdbcUrl(POSTGRES.getJdbcUrl());
            config.setUsername(POSTGRES.getUsername());
            config.setPassword(POSTGRES.getPassword());
            config.setPoolName("otlp-export-test");
            config.setMinimumIdle(1);
            config.setMaximumPoolSize(2);
            return new HikariDataSource(config);
        }
    }

    @Test
    void exportsTelemetryAndAlertMetricNamesThroughOtlpCollector() throws Exception {
        telemetry.recordQueueSnapshot(new RunQueueSnapshot(7, 2, 3, Duration.ofSeconds(45)));
        assertNotNull(meters.find("hikaricp.connections.active").gauge(), "Hikari pool gauges must be bound locally");
        meters.counter("agent.otlp.export.probe").increment();
        Observation.createNotStarted("myai.otlp.export.probe", observations).start().stop();

        String scrape = awaitPrometheusMetrics(Duration.ofSeconds(20));
        assertAlertMetricsAreExported(scrape);

        assertTrue(awaitCollectorTrace(Duration.ofSeconds(10)),
                "Collector debug exporter should receive the Agent trace");
    }

    private static void assertAlertMetricsAreExported(String scrape) throws Exception {
        Pattern tokenPattern = Pattern.compile("[a-zA-Z_:][a-zA-Z0-9_:]*");
        var expressionTokens = new LinkedHashSet<String>();
        for (String line : Files.readAllLines(Path.of("ops/monitoring/agent-alerts.yaml"))) {
            String trimmed = line.trim();
            if (!trimmed.startsWith("expr:")) continue;
            Matcher matcher = tokenPattern.matcher(trimmed.substring("expr:".length()));
            while (matcher.find()) expressionTokens.add(matcher.group());
        }
        var unexpectedTokens = new HashSet<>(expressionTokens);
        unexpectedTokens.removeAll(EXPECTED_ALERT_METRICS);
        assertTrue(!expressionTokens.isEmpty(), "alert rules should contain metric expressions");
        assertTrue(expressionTokens.containsAll(EXPECTED_ALERT_METRICS),
                () -> "Alert expressions do not reference all expected metrics: " + EXPECTED_ALERT_METRICS);
        unexpectedTokens.removeAll(SUPPORTED_PROMQL_FUNCTIONS);
        assertTrue(unexpectedTokens.isEmpty(),
                () -> "Update the alert metric contract when PromQL functions or identifiers change: " + unexpectedTokens);

        Set<String> sampleNames = prometheusSampleNames(scrape);
        var missingSamples = new HashSet<String>();
        for (String metricName : EXPECTED_ALERT_METRICS) {
            if (!sampleNames.contains(metricName)) missingSamples.add(metricName);
        }
        assertTrue(missingSamples.isEmpty(), () -> "Collector Prometheus output is missing alert samples "
                + missingSamples + "; exported agent metrics: " + sampleNames.stream()
                .filter(name -> name.startsWith("myai_agent_worker") || name.startsWith("myai_hikaricp_connections"))
                .sorted().toList());
    }

    private static Set<String> prometheusSampleNames(String exposition) {
        var sampleNames = new HashSet<String>();
        for (String line : exposition.lines().toList()) {
            String sample = line.strip();
            if (sample.isEmpty() || sample.startsWith("#")) continue;
            int separator = sample.indexOf(' ');
            if (separator < 0) continue;
            String nameAndLabels = sample.substring(0, separator);
            int labels = nameAndLabels.indexOf('{');
            sampleNames.add(labels < 0 ? nameAndLabels : nameAndLabels.substring(0, labels));
        }
        return sampleNames;
    }

    private static String collectorEndpoint(String path) {
        return "http://" + COLLECTOR.getHost() + ":" + COLLECTOR.getMappedPort(4318) + path;
    }

    private static String awaitPrometheusMetrics(Duration timeout) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        String latest = "";
        while (System.nanoTime() < deadline) {
            HttpRequest request = HttpRequest.newBuilder(URI.create("http://" + COLLECTOR.getHost() + ":"
                    + COLLECTOR.getMappedPort(8889) + "/metrics")).GET().build();
            HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
            latest = response.body();
            if (response.statusCode() == 200
                    && prometheusSampleNames(latest).containsAll(EXPECTED_ALERT_METRICS)) return latest;
            TimeUnit.MILLISECONDS.sleep(250);
        }
        return latest;
    }

    private static boolean awaitCollectorTrace(Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (COLLECTOR.getLogs().contains("myai.otlp.export.probe")) return true;
            TimeUnit.MILLISECONDS.sleep(250);
        }
        return false;
    }
}
