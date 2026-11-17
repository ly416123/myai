package com.example.agent.memory;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MemoryServiceTest {
    @Test
    void rememberDoesNotOverwriteAndUpdateRequiresExplicitCommand() {
        var store = new InMemoryMemoryStore();
        var service = service(store, "default", 10);

        var created = service.remember("answer.language", MemoryKind.PREFERENCE, "回答使用中文");

        assertThrows(MemoryService.MemoryAlreadyExistsException.class,
                () -> service.remember("answer.language", MemoryKind.PREFERENCE, "English"));
        var updated = service.update("answer.language", MemoryKind.PREFERENCE, "默认使用简体中文");
        assertEquals(created.memoryId(), updated.memoryId());
        assertEquals(2, updated.version());
        assertEquals("默认使用简体中文", service.find("answer.language").orElseThrow().content());
        assertEquals(2, store.events(created.memoryId()).size());
    }

    @Test
    void validatesKeyKindAndUnicodeCodePointLength() {
        var service = service(new InMemoryMemoryStore(), "default", 10);

        assertThrows(MemoryService.MemoryValidationException.class,
                () -> service.remember("../secret", MemoryKind.FACT, "valid text"));
        assertThrows(MemoryService.MemoryValidationException.class,
                () -> service.remember("empty", MemoryKind.FACT, " \n "));
        assertThrows(MemoryService.MemoryValidationException.class,
                () -> service.remember("large", MemoryKind.FACT, "😀".repeat(2_001)));
        assertEquals("😀".repeat(2_000), service.remember("emoji", MemoryKind.FACT,
                "😀".repeat(2_000)).content());
    }

    @Test
    void rejectsObviousCredentialsBeforeWriting() {
        var service = service(new InMemoryMemoryStore(), "default", 10);

        assertThrows(MemoryService.MemoryValidationException.class,
                () -> service.remember("credential", MemoryKind.FACT, "DEEPSEEK_API_KEY=sk-example-secret"));
        assertTrue(service.list().isEmpty());
    }

    @Test
    void enforcesNamespaceLimitAndDeleteAllowsReusingKey() {
        var service = service(new InMemoryMemoryStore(), "default", 1);
        var first = service.remember("answer.language", MemoryKind.PREFERENCE, "中文");

        assertThrows(MemoryService.MemoryLimitExceededException.class,
                () -> service.remember("trip.city", MemoryKind.FACT, "北京"));
        service.delete("answer.language");
        var second = service.remember("answer.language", MemoryKind.PREFERENCE, "简体中文");

        assertFalse(first.memoryId().equals(second.memoryId()));
        assertEquals(1, service.list().size());
    }

    @Test
    void retrievesRelevantMemoriesAndFailsOpenOnStoreReadFailure() {
        var store = new InMemoryMemoryStore();
        var service = service(store, "default", 10);
        service.remember("trip.city", MemoryKind.PREFERENCE, "旅行目的地默认北京");
        service.remember("answer.language", MemoryKind.PREFERENCE, "回答使用中文");

        var snapshot = service.snapshot("北京旅行路线");
        assertEquals(1, snapshot.entries().size());
        assertEquals("trip.city", snapshot.entries().getFirst().key());

        store.failReads(true);
        var degraded = service.snapshot("北京旅行路线");
        assertTrue(degraded.degraded());
        assertTrue(degraded.entries().isEmpty());
        assertThrows(MemoryService.MemoryOperationException.class, service::list);
    }

    private static MemoryService service(InMemoryMemoryStore store, String namespace, int limit) {
        return new MemoryService(store, new MemoryRetriever(5, 2_000), namespace, limit);
    }
}
