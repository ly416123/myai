package com.example.agent.client;

import com.example.agent.workflow.FailureCategory;

/** 模型请求失败的脱敏分类；不保留厂商异常正文。 */
public final class ModelClientException extends RuntimeException {
    private final FailureCategory category;
    private final boolean retryable;

    public ModelClientException(FailureCategory category, boolean retryable) {
        super("Model request failed: " + category);
        this.category = category;
        this.retryable = retryable;
    }

    public FailureCategory category() { return category; }
    public boolean retryable() { return retryable; }
}
