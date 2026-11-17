package com.example.agent.memory;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MemoryRetrieverTest {
    @Test
    void ranksExactKeyThenChineseOverlapAndUsesStableTieBreak() {
        var entries = List.of(
                entry("c", "travel.city", "北京偏好步行", 1),
                entry("b", "visit.city", "北京旅行路线", 1),
                entry("a", "trip.city", "北京旅行路线", 1));

        var result = new MemoryRetriever(5, 2_000).retrieve("travel.city 北京路线", entries);

        assertEquals(List.of("travel.city", "trip.city", "visit.city"), result.entries().stream()
                .map(MemoryEntry::key).toList());
    }

    @Test
    void capsEntryCountAndCountsSupplementaryCharactersAsOneCodePoint() {
        var result = new MemoryRetriever(2, 2_000).retrieve("北京", List.of(
                entry("a", "city", "北京 😀", 1),
                entry("b", "route", "北京路线", 1),
                entry("c", "weather", "北京天气", 1)));

        assertEquals(2, result.entries().size());
        assertTrue(result.characterCount() <= 2_000);
    }

    private static MemoryEntry entry(String id, String key, String content, long version) {
        var now = Instant.parse("2026-11-18T00:00:00Z");
        var memoryId = new UUID(0, id.charAt(0));
        return new MemoryEntry(memoryId, "default", key, MemoryKind.PREFERENCE, content, version, now, now);
    }
}
