package com.example.agent.evaluation;

import java.util.List;

/** Non-sensitive shape of one Supervisor plan task for human/fixture review. */
public record AgentEvaluationPlanTask(String taskId, String workerId,
                                     List<String> dependencies, List<String> deliverableIds) {
    public AgentEvaluationPlanTask {
        dependencies = List.copyOf(dependencies);
        deliverableIds = List.copyOf(deliverableIds);
    }
}
