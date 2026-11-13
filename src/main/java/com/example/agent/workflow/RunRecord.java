package com.example.agent.workflow;

import com.example.agent.model.Message;
import com.example.agent.collaboration.ChildTaskRecord;
import com.example.agent.collaboration.TaskPlan;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** 单次 Workflow 的检查点与汇总指标。JSON 文件存储该类型。 */
@JsonIgnoreProperties(ignoreUnknown = true)
public final class RunRecord {
    private String runId;
    // 缺少该字段的阶段二及更早 JSON 检查点默认按单 Agent 读取。
    private RunType runType = RunType.SINGLE_AGENT;
    private RunStatus status;
    private String userInput;
    private List<Message> messages = new ArrayList<>();
    private List<Message> agentMessages = new ArrayList<>();
    private List<RunStep> steps = new ArrayList<>();
    private TaskPlan taskPlan;
    private List<ChildTaskRecord> childTasks = new ArrayList<>();
    private List<RunStateChange> stateChanges = new ArrayList<>();
    private Instant createdAt;
    private Instant updatedAt;
    private Instant deadlineAt;
    private String leaseOwner;
    private Instant leaseExpiresAt;
    private long fencingToken;
    private long version;
    private boolean cancellationRequested;
    private int modelCalls;
    private int toolCalls;
    private Map<String, BudgetKind> budgetReservations = new LinkedHashMap<>();
    private boolean resumed;
    private FailureCategory failureCategory;
    private String finalAnswer;
    private Long promptTokens;
    private Long completionTokens;
    private boolean tokenUsageKnown = true;

    /** Jackson 反序列化构造器。 */
    public RunRecord() { }

    private RunRecord(String runId, String userInput, Instant createdAt, Instant deadlineAt, RunType runType) {
        this.runId = Objects.requireNonNull(runId);
        this.runType = Objects.requireNonNull(runType);
        this.userInput = Objects.requireNonNull(userInput);
        this.status = RunStatus.CREATED;
        this.createdAt = createdAt;
        this.updatedAt = createdAt;
        this.deadlineAt = deadlineAt;
        this.stateChanges.add(new RunStateChange(RunStatus.CREATED, createdAt, null));
    }

    public static RunRecord create(String runId, String userInput, Duration timeout) {
        return create(runId, userInput, timeout, RunType.SINGLE_AGENT);
    }

    public static RunRecord create(String runId, String userInput, Duration timeout, RunType runType) {
        Instant now = Instant.now();
        return new RunRecord(runId, userInput, now, now.plus(timeout), runType);
    }

    public synchronized void addMessage(Message message) { messages.add(message); }
    public synchronized void addStep(RunStep step) { steps.add(step); }
    public synchronized void setTaskPlan(TaskPlan value) { taskPlan = value; }
    public synchronized void addChildTask(ChildTaskRecord task) { childTasks.add(task); }
    public synchronized void setChildTasks(List<ChildTaskRecord> tasks) {
        childTasks = tasks == null ? new ArrayList<>() : new ArrayList<>(tasks);
        rebuildStepProjection();
    }
    public synchronized void replaceChildTask(String taskId, ChildTaskRecord replacement) {
        for (int i = 0; i < childTasks.size(); i++) {
            if (childTasks.get(i).taskId().equals(taskId)) {
                childTasks.set(i, replacement);
                return;
            }
        }
        throw new IllegalArgumentException("Unknown child task ID");
    }

    /** Rebuilds the visible Run step list from supervisor steps and task-owned checkpoints. */
    private void rebuildStepProjection() {
        var combined = new java.util.LinkedHashMap<String, RunStep>();
        steps.stream().filter(step -> step.taskId() == null).forEach(step -> combined.put(step.stepId(), step));
        // Keep task-scoped steps from an older checkpoint if the task payload has not
        // captured them yet; the task payload wins whenever it has the same step ID.
        steps.stream().filter(step -> step.taskId() != null).forEach(step -> combined.put(step.stepId(), step));
        childTasks.forEach(task -> task.steps().forEach(step -> combined.put(step.stepId(), step)));
        steps = new ArrayList<>(combined.values());
    }
    public synchronized void transitionTo(RunStatus next, Instant now, FailureCategory category) {
        if (status == next) {
            updatedAt = now;
            return;
        }
        boolean allowed = switch (status) {
            case CREATED -> next == RunStatus.RUNNING || next == RunStatus.FAILED || next == RunStatus.CANCELLED;
            case RUNNING -> next == RunStatus.SUCCEEDED || next == RunStatus.PARTIAL || next == RunStatus.FAILED || next == RunStatus.CANCELLED;
            case SUCCEEDED, PARTIAL, FAILED, CANCELLED -> false;
        };
        if (!allowed) throw new IllegalStateException("Invalid run status transition");
        status = next;
        updatedAt = now;
        failureCategory = category;
        stateChanges.add(new RunStateChange(next, now, category));
    }
    public synchronized void replaceStep(String stepId, RunStep replacement) {
        for (int i = 0; i < steps.size(); i++) {
            if (steps.get(i).stepId().equals(stepId)) {
                steps.set(i, replacement);
                return;
            }
        }
        throw new IllegalArgumentException("Unknown step ID");
    }
    public synchronized RunStep step(String stepId) {
        return steps.stream().filter(step -> step.stepId().equals(stepId)).findFirst().orElse(null);
    }
    public synchronized List<RunStep> steps() { return List.copyOf(steps); }
    public synchronized List<ChildTaskRecord> childTasks() { return List.copyOf(childTasks); }
    public synchronized TaskPlan taskPlan() { return taskPlan; }
    public synchronized List<RunStateChange> stateChanges() { return List.copyOf(stateChanges); }
    public synchronized List<Message> messages() { return List.copyOf(messages); }
    public synchronized List<Message> agentMessages() { return List.copyOf(agentMessages); }
    public synchronized void setMessages(List<Message> messages) { this.messages = new ArrayList<>(messages); }
    public synchronized void setAgentMessages(List<Message> value) { agentMessages = new ArrayList<>(value); }
    public synchronized void setSteps(List<RunStep> steps) { this.steps = new ArrayList<>(steps); }
    public synchronized void setStateChanges(List<RunStateChange> changes) { this.stateChanges = new ArrayList<>(changes); }
    public synchronized void incrementModelCalls() { modelCalls++; }
    public synchronized void incrementToolCalls() { toolCalls++; }
    public synchronized void addTokenUsage(Long prompt, Long completion) {
        if (prompt == null || completion == null || !tokenUsageKnown) {
            tokenUsageKnown = false;
            promptTokens = null;
            completionTokens = null;
            return;
        }
        promptTokens = (promptTokens == null ? 0 : promptTokens) + prompt;
        completionTokens = (completionTokens == null ? 0 : completionTokens) + completion;
    }
    public synchronized void markTokenUsageUnknown() {
        tokenUsageKnown = false;
        promptTokens = null;
        completionTokens = null;
    }
    public synchronized void touch(Instant now) { updatedAt = now; }

    public synchronized String runId() { return runId; }
    public synchronized RunType runType() { return runType; }
    public synchronized RunStatus status() { return status; }
    public synchronized String userInput() { return userInput; }
    public synchronized Instant createdAt() { return createdAt; }
    public synchronized Instant updatedAt() { return updatedAt; }
    public synchronized Instant deadlineAt() { return deadlineAt; }
    public synchronized String leaseOwner() { return leaseOwner; }
    public synchronized Instant leaseExpiresAt() { return leaseExpiresAt; }
    public synchronized long fencingToken() { return fencingToken; }
    public synchronized long version() { return version; }
    public synchronized boolean cancellationRequested() { return cancellationRequested; }
    public synchronized int modelCalls() { return modelCalls; }
    public synchronized int toolCalls() { return toolCalls; }
    public synchronized Map<String, BudgetKind> budgetReservations() { return Map.copyOf(budgetReservations); }
    public synchronized boolean resumed() { return resumed; }
    public synchronized FailureCategory failureCategory() { return failureCategory; }
    public synchronized String finalAnswer() { return finalAnswer; }
    public synchronized Long promptTokens() { return promptTokens; }
    public synchronized Long completionTokens() { return completionTokens; }
    public synchronized boolean tokenUsageKnown() { return tokenUsageKnown; }
    public synchronized long totalDurationMillis() {
        if (createdAt == null || updatedAt == null) return 0;
        return Math.max(0, Duration.between(createdAt, updatedAt).toMillis());
    }

    public synchronized void setRunId(String value) { runId = value; }
    public synchronized void setRunType(RunType value) { runType = value; }
    public synchronized void setStatus(RunStatus value) { status = value; }
    public synchronized void setUserInput(String value) { userInput = value; }
    public synchronized void setCreatedAt(Instant value) { createdAt = value; }
    public synchronized void setUpdatedAt(Instant value) { updatedAt = value; }
    public synchronized void setDeadlineAt(Instant value) { deadlineAt = value; }
    public synchronized void setLeaseOwner(String value) { leaseOwner = value; }
    public synchronized void setLeaseExpiresAt(Instant value) { leaseExpiresAt = value; }
    public synchronized void setFencingToken(long value) { fencingToken = value; }
    public synchronized void setVersion(long value) { version = value; }
    public synchronized void setCancellationRequested(boolean value) { cancellationRequested = value; }
    public synchronized void setModelCalls(int value) { modelCalls = value; }
    public synchronized void setToolCalls(int value) { toolCalls = value; }
    public synchronized Map<String, BudgetKind> getBudgetReservations() { return Map.copyOf(budgetReservations); }
    public synchronized void setBudgetReservations(Map<String, BudgetKind> value) {
        budgetReservations = value == null ? new LinkedHashMap<>() : new LinkedHashMap<>(value);
    }
    public synchronized void setResumed(boolean value) { resumed = value; }
    public synchronized void setFailureCategory(FailureCategory value) { failureCategory = value; }
    public synchronized void setFinalAnswer(String value) { finalAnswer = value; }
    public synchronized void setPromptTokens(Long value) { promptTokens = value; }
    public synchronized void setCompletionTokens(Long value) { completionTokens = value; }
    public synchronized void setTokenUsageKnown(boolean value) { tokenUsageKnown = value; }

    public synchronized String getRunId() { return runId; }
    public synchronized RunType getRunType() { return runType; }
    public synchronized RunStatus getStatus() { return status; }
    public synchronized String getUserInput() { return userInput; }
    public synchronized List<Message> getMessages() { return List.copyOf(messages); }
    public synchronized List<Message> getAgentMessages() { return List.copyOf(agentMessages); }
    public synchronized List<RunStep> getSteps() { return List.copyOf(steps); }
    public synchronized TaskPlan getTaskPlan() { return taskPlan; }
    public synchronized List<ChildTaskRecord> getChildTasks() { return List.copyOf(childTasks); }
    public synchronized List<RunStateChange> getStateChanges() { return List.copyOf(stateChanges); }
    public synchronized Instant getCreatedAt() { return createdAt; }
    public synchronized Instant getUpdatedAt() { return updatedAt; }
    public synchronized Instant getDeadlineAt() { return deadlineAt; }
    public synchronized String getLeaseOwner() { return leaseOwner; }
    public synchronized Instant getLeaseExpiresAt() { return leaseExpiresAt; }
    public synchronized long getFencingToken() { return fencingToken; }
    public synchronized long getVersion() { return version; }
    public synchronized boolean isCancellationRequested() { return cancellationRequested; }
    public synchronized int getModelCalls() { return modelCalls; }
    public synchronized int getToolCalls() { return toolCalls; }
    public synchronized boolean isResumed() { return resumed; }
    public synchronized FailureCategory getFailureCategory() { return failureCategory; }
    public synchronized String getFinalAnswer() { return finalAnswer; }
    public synchronized Long getPromptTokens() { return promptTokens; }
    public synchronized Long getCompletionTokens() { return completionTokens; }
    public synchronized boolean isTokenUsageKnown() { return tokenUsageKnown; }
}
