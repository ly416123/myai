package com.example.agent.workflow;

import com.example.agent.model.Message;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** 单次 Workflow 的检查点与汇总指标。JSON 文件存储该类型。 */
@JsonIgnoreProperties(ignoreUnknown = true)
public final class RunRecord {
    private String runId;
    private RunStatus status;
    private String userInput;
    private List<Message> messages = new ArrayList<>();
    private List<RunStep> steps = new ArrayList<>();
    private List<RunStateChange> stateChanges = new ArrayList<>();
    private Instant createdAt;
    private Instant updatedAt;
    private Instant deadlineAt;
    private int modelCalls;
    private int toolCalls;
    private boolean resumed;
    private FailureCategory failureCategory;
    private String finalAnswer;
    private Long promptTokens;
    private Long completionTokens;
    private boolean tokenUsageKnown = true;

    /** Jackson 反序列化构造器。 */
    public RunRecord() { }

    private RunRecord(String runId, String userInput, Instant createdAt, Instant deadlineAt) {
        this.runId = Objects.requireNonNull(runId);
        this.userInput = Objects.requireNonNull(userInput);
        this.status = RunStatus.CREATED;
        this.createdAt = createdAt;
        this.updatedAt = createdAt;
        this.deadlineAt = deadlineAt;
        this.stateChanges.add(new RunStateChange(RunStatus.CREATED, createdAt, null));
    }

    public static RunRecord create(String runId, String userInput, Duration timeout) {
        Instant now = Instant.now();
        return new RunRecord(runId, userInput, now, now.plus(timeout));
    }

    public synchronized void addMessage(Message message) { messages.add(message); }
    public synchronized void addStep(RunStep step) { steps.add(step); }
    public synchronized void transitionTo(RunStatus next, Instant now, FailureCategory category) {
        if (status == next) {
            updatedAt = now;
            return;
        }
        boolean allowed = switch (status) {
            case CREATED -> next == RunStatus.RUNNING || next == RunStatus.FAILED || next == RunStatus.CANCELLED;
            case RUNNING -> next == RunStatus.SUCCEEDED || next == RunStatus.FAILED || next == RunStatus.CANCELLED;
            case SUCCEEDED, FAILED, CANCELLED -> false;
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
    public synchronized List<RunStateChange> stateChanges() { return List.copyOf(stateChanges); }
    public synchronized List<Message> messages() { return List.copyOf(messages); }
    public synchronized void setMessages(List<Message> messages) { this.messages = new ArrayList<>(messages); }
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

    public String runId() { return runId; }
    public RunStatus status() { return status; }
    public String userInput() { return userInput; }
    public Instant createdAt() { return createdAt; }
    public Instant updatedAt() { return updatedAt; }
    public Instant deadlineAt() { return deadlineAt; }
    public int modelCalls() { return modelCalls; }
    public int toolCalls() { return toolCalls; }
    public boolean resumed() { return resumed; }
    public FailureCategory failureCategory() { return failureCategory; }
    public String finalAnswer() { return finalAnswer; }
    public Long promptTokens() { return promptTokens; }
    public Long completionTokens() { return completionTokens; }
    public boolean tokenUsageKnown() { return tokenUsageKnown; }
    public long totalDurationMillis() {
        if (createdAt == null || updatedAt == null) return 0;
        return Math.max(0, Duration.between(createdAt, updatedAt).toMillis());
    }

    public void setRunId(String value) { runId = value; }
    public void setStatus(RunStatus value) { status = value; }
    public void setUserInput(String value) { userInput = value; }
    public void setCreatedAt(Instant value) { createdAt = value; }
    public void setUpdatedAt(Instant value) { updatedAt = value; }
    public void setDeadlineAt(Instant value) { deadlineAt = value; }
    public void setModelCalls(int value) { modelCalls = value; }
    public void setToolCalls(int value) { toolCalls = value; }
    public void setResumed(boolean value) { resumed = value; }
    public void setFailureCategory(FailureCategory value) { failureCategory = value; }
    public void setFinalAnswer(String value) { finalAnswer = value; }
    public void setPromptTokens(Long value) { promptTokens = value; }
    public void setCompletionTokens(Long value) { completionTokens = value; }
    public void setTokenUsageKnown(boolean value) { tokenUsageKnown = value; }

    public String getRunId() { return runId; }
    public RunStatus getStatus() { return status; }
    public String getUserInput() { return userInput; }
    public List<Message> getMessages() { return messages; }
    public List<RunStep> getSteps() { return steps; }
    public List<RunStateChange> getStateChanges() { return stateChanges; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public Instant getDeadlineAt() { return deadlineAt; }
    public int getModelCalls() { return modelCalls; }
    public int getToolCalls() { return toolCalls; }
    public boolean isResumed() { return resumed; }
    public FailureCategory getFailureCategory() { return failureCategory; }
    public String getFinalAnswer() { return finalAnswer; }
    public Long getPromptTokens() { return promptTokens; }
    public Long getCompletionTokens() { return completionTokens; }
    public boolean isTokenUsageKnown() { return tokenUsageKnown; }
}
