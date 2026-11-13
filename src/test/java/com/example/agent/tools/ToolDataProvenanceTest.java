package com.example.agent.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.ZonedDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolDataProvenanceTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void timeToolIdentifiesSystemClockDataAsNonSimulated() throws Exception {
        var result = new TimeTool().execute(JSON.readTree("{\"timezone\":\"Asia/Shanghai\"}"));

        assertEquals("java.time.ZonedDateTime.now(ZoneId)", result.path("source").asText());
        assertFalse(result.path("simulated").asBoolean(true));
        assertEquals("Asia/Shanghai", result.path("timezone").asText());
        assertEquals("Asia/Shanghai", ZonedDateTime.parse(result.path("dateTime").asText()).getZone().getId());
        assertTrue(new TimeTool().description().contains("Java 系统时钟"));
        assertTrue(new TimeTool().description().contains("非模拟"));
    }

    @Test
    void weatherToolExplicitlyMarksFixtureDataAsSimulated() throws Exception {
        var result = new WeatherTool().execute(JSON.readTree("{\"city\":\"北京\"}"));

        assertTrue(result.path("simulated").asBoolean(false));
        assertEquals("模拟数据，仅用于演示", result.path("source").asText());
    }
}
