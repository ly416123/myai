package com.example.agent;

import com.example.agent.workflow.PostgresRunStore;
import com.example.agent.workflow.RunStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import io.micrometer.core.instrument.MeterRegistry;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = {"DEEPSEEK_API_KEY=test-only-not-a-real-key", "spring.main.web-application-type=none",
        "agent.cli.enabled=false", "agent.execution-mode=worker", "agent.separate-task-workers=true",
        "agent.worker-poll-interval=10s"})
class AgentApplicationContextTest {
    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private RunStore runStore;

    @Autowired
    private com.example.agent.workflow.RunPollingWorker runPollingWorker;

    @Autowired
    private com.example.agent.workflow.ChildTaskPollingWorker childTaskPollingWorker;

    @Autowired
    private MeterRegistry meterRegistry;

    @AfterEach
    void stopBackgroundWorkerBeforeItsDatabaseContainerStops() {
        runPollingWorker.close();
        childTaskPollingWorker.close();
    }

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        properties.add("spring.datasource.username", POSTGRES::getUsername);
        properties.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Test
    void applicationWiresPostgresRunStoreWithoutStartingWebApi() {
        assertInstanceOf(PostgresRunStore.class, runStore);
        assertNotNull(runPollingWorker);
        assertNotNull(childTaskPollingWorker);
        assertNotNull(meterRegistry.find("hikaricp.connections.active").gauge());
        assertNotNull(meterRegistry.find("hikaricp.connections.max").gauge());
    }
}
