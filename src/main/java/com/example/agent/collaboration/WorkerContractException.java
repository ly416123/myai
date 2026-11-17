package com.example.agent.collaboration;

import java.util.List;

/** 带有脱敏、机器可读字段信息的 Worker 契约校验异常。 */
public final class WorkerContractException extends IllegalArgumentException {
    private final List<WorkerContractIssue> issues;

    public WorkerContractException(List<WorkerContractIssue> issues) {
        super("Worker contract validation failed");
        this.issues = issues == null ? List.of() : List.copyOf(issues);
    }

    public List<WorkerContractIssue> issues() { return issues; }
}
