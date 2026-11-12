package com.example.agent.workflow;

import java.util.List;
import java.util.Optional;

/** 保存和读取 Workflow 检查点的存储抽象。 */
public interface RunStore {
    void save(RunRecord run);
    Optional<RunRecord> find(String runId);
    List<RunRecord> list();
}
