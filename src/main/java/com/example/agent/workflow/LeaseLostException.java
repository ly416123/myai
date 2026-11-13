package com.example.agent.workflow;

/** 当前执行者已失去 Run 租约；拒绝使用过期检查点继续覆盖持久状态。 */
public final class LeaseLostException extends RuntimeException {
    public LeaseLostException(String runId) {
        super("Run lease lost: " + runId);
    }
}
