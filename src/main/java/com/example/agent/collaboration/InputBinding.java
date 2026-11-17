package com.example.agent.collaboration;

import com.fasterxml.jackson.databind.JsonNode;

/** 子任务契约字段的显式来源；父请求值由 Planner 提取，依赖值由 JSON Pointer 定位。 */
public record InputBinding(String field, Source source, JsonNode parentValue,
                           String dependencyTaskId, String jsonPointer) {
    public InputBinding {
        parentValue = parentValue == null ? null : parentValue.deepCopy();
    }

    @Override public JsonNode parentValue() {
        return parentValue == null ? null : parentValue.deepCopy();
    }

    public enum Source { PARENT_REQUEST, DEPENDENCY_OUTPUT }
}
