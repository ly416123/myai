package com.example.agent.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** 用于单元测试和本地验证的内存 RunStore。保存时复制记录，模拟存储边界。 */
public final class InMemoryRunStore implements RunStore {
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    private final ConcurrentHashMap<String, RunRecord> records = new ConcurrentHashMap<>();

    @Override public void save(RunRecord run) { records.put(run.runId(), copy(run)); }
    @Override public Optional<RunRecord> find(String runId) { return Optional.ofNullable(records.get(runId)).map(InMemoryRunStore::copy); }
    @Override public List<RunRecord> list() {
        return records.values().stream().map(InMemoryRunStore::copy)
                .sorted(Comparator.comparing(RunRecord::createdAt)).toList();
    }
    private static RunRecord copy(RunRecord run) {
        return JSON.convertValue(run, RunRecord.class);
    }
}
