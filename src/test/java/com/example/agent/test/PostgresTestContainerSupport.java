package com.example.agent.test;

import org.testcontainers.containers.PostgreSQLContainer;

import java.util.Map;

/**
 * Creates disposable PostgreSQL containers with their database files in memory.
 *
 * <p>The integration tests do not need database files to survive a container
 * restart. Keeping PGDATA on a bounded tmpfs prevents test data from filling
 * the Docker host's persistent container layer and makes cleanup automatic.
 */
public final class PostgresTestContainerSupport {
    private static final String DATA_DIRECTORY = "/var/lib/postgresql/data";
    private static final String TMPFS_OPTIONS = "rw,noexec,nosuid,size=512m";

    private PostgresTestContainerSupport() {
    }

    public static PostgreSQLContainer<?> create() {
        return new PostgreSQLContainer<>("postgres:16-alpine")
                .withTmpFs(Map.of(DATA_DIRECTORY, TMPFS_OPTIONS));
    }
}
