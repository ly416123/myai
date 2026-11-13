package com.example.agent.collaboration;

import java.util.List;

/** Supervisor 的单次结构化规划响应。 */
public record TaskPlan(Route route, List<PlannedTask> tasks, List<Deliverable> deliverables) {
    public TaskPlan {
        tasks = tasks == null ? List.of() : List.copyOf(tasks);
        deliverables = deliverables == null ? List.of() : List.copyOf(deliverables);
    }

    public enum Route { NO_SPLIT, DELEGATE }
    public record Deliverable(String deliverableId, String description) { }
}
