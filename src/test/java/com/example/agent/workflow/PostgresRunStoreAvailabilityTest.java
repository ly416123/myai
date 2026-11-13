package com.example.agent.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.sql.SQLException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;

class PostgresRunStoreAvailabilityTest {
    @Test
    void exposesDatabaseReadFailureAsRunStoreUnavailable() {
        DriverManagerDataSource unavailableDataSource = new DriverManagerDataSource(
                "jdbc:postgresql://127.0.0.1:1/unavailable?connectTimeout=1", "unused", "unused");
        PostgresRunStore store = new PostgresRunStore(new JdbcTemplate(unavailableDataSource), new ObjectMapper());

        assertThrows(RunStoreUnavailableException.class, () -> store.find("unavailable-run"));
    }

    @Test
    void doesNotTreatPermanentSqlFailureAsTemporaryStoreOutage() {
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource()) {
            @Override
            public <T> List<T> query(String sql, RowMapper<T> rowMapper, Object... args) {
                throw new BadSqlGrammarException("find", sql, new SQLException("invalid SQL"));
            }
        };
        PostgresRunStore store = new PostgresRunStore(jdbc, new ObjectMapper());

        assertThrows(BadSqlGrammarException.class, () -> store.find("broken-query"));
    }
}
