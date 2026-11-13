package com.example.agent.collaboration;

import com.example.agent.model.Message;
import com.example.agent.workflow.FailureCategory;
import com.example.agent.workflow.RunStep;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** 父 Run 中的可恢复子任务快照，不创建独立子 Run。 */
public record ChildTaskRecord(String taskId, String workerId, String description,
                              List<String> dependencies, List<String> deliverableIds,
                              TaskStatus status, Instant startedAt, Instant completedAt,
                              long durationMillis, FailureCategory failureCategory,
                              String result, List<Message> messages, int modelCalls, int toolCalls,
                              List<RunStep> steps, Map<String, String> traceContext) {
    public ChildTaskRecord {
        dependencies = dependencies == null ? List.of() : List.copyOf(dependencies);
        deliverableIds = deliverableIds == null ? List.of() : List.copyOf(deliverableIds);
        messages = messages == null ? List.of() : List.copyOf(messages);
        steps = steps == null ? List.of() : List.copyOf(steps);
        traceContext = traceContext == null ? Map.of() : Map.copyOf(traceContext);
    }

    public static ChildTaskRecord pending(PlannedTask task) {
        return pending(task, Map.of());
    }

    public static ChildTaskRecord pending(PlannedTask task, Map<String, String> traceContext) {
        return new ChildTaskRecord(task.taskId(), task.workerId(), task.description(), task.dependencies(),
                task.deliverableIds(), TaskStatus.PENDING, null, null, 0, null, null, List.of(), 0, 0, List.of(),
                traceContext);
    }

    public ChildTaskRecord start(List<Message> initialMessages, Instant now) {
        return new ChildTaskRecord(taskId, workerId, description, dependencies, deliverableIds,
                TaskStatus.IN_PROGRESS, now, null, 0, null, result, initialMessages, modelCalls, toolCalls, steps,
                traceContext);
    }

    public ChildTaskRecord withMessages(List<Message> value) {
        return new ChildTaskRecord(taskId, workerId, description, dependencies, deliverableIds,
                status, startedAt, completedAt, durationMillis, failureCategory, result, value, modelCalls, toolCalls,
                steps, traceContext);
    }

    public ChildTaskRecord withSteps(List<RunStep> value) {
        return new ChildTaskRecord(taskId, workerId, description, dependencies, deliverableIds,
                status, startedAt, completedAt, durationMillis, failureCategory, result, messages, modelCalls, toolCalls,
                value, traceContext);
    }

    public ChildTaskRecord withTraceContext(Map<String, String> value) {
        return new ChildTaskRecord(taskId, workerId, description, dependencies, deliverableIds, status, startedAt,
                completedAt, durationMillis, failureCategory, result, messages, modelCalls, toolCalls, steps, value);
    }

    public ChildTaskRecord finish(TaskStatus finalStatus, String value, FailureCategory category, Instant now) {
        long elapsed = startedAt == null ? 0 : Math.max(0, Duration.between(startedAt, now).toMillis());
        return new ChildTaskRecord(taskId, workerId, description, dependencies, deliverableIds,
                finalStatus, startedAt, now, elapsed, category, value, messages, modelCalls, toolCalls, steps,
                traceContext);
    }

    public ChildTaskRecord addModelCall() {
        return new ChildTaskRecord(taskId, workerId, description, dependencies, deliverableIds,
                status, startedAt, completedAt, durationMillis, failureCategory, result, messages,
                modelCalls + 1, toolCalls, steps, traceContext);
    }

    public ChildTaskRecord addToolCalls(int count) {
        return new ChildTaskRecord(taskId, workerId, description, dependencies, deliverableIds,
                status, startedAt, completedAt, durationMillis, failureCategory, result, messages,
                modelCalls, toolCalls + count, steps, traceContext);
    }
}
