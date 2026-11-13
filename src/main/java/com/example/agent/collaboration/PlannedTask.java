package com.example.agent.collaboration;

import java.util.List;

/** Supervisor 提出的单个受约束任务；依赖结果只在执行该任务时显式注入。 */
public record PlannedTask(String taskId, String workerId, String description,
                          List<String> dependencies, List<String> deliverableIds) {
    public PlannedTask {
        dependencies = dependencies == null ? List.of() : List.copyOf(dependencies);
        deliverableIds = deliverableIds == null ? List.of() : List.copyOf(deliverableIds);
    }
}
