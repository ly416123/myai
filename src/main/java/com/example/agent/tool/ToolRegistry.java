package com.example.agent.tool;

import com.example.agent.model.ToolDefinition;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Agent 工具注册表。
 *
 * <p>负责维护“工具名 → Java 实现”的映射，并把工具元数据导出为模型可读的定义列表。
 * 它不执行工具，也不决定模型是否应该调用某个工具。
 *
 * <p>传统后端可以类比为一个轻量 Service Locator/插件目录；在 Spring AI 中，
 * 可调用工具的注册和暴露通常由框架工具回调注册机制管理。
 */
public final class ToolRegistry {
    /** 使用插入顺序，保证传给模型的工具列表稳定且便于观察。 */
    private final Map<String, AgentTool> tools = new LinkedHashMap<>();

    /**
     * 注册工具。同名工具会立即报错，避免静默覆盖导致模型描述与实际实现错配。
     *
     * @param tool 要注册的工具实现
     */
    public synchronized void register(AgentTool tool) {
        if (tool == null) throw new IllegalArgumentException("tool cannot be null");
        if (tool.name() == null || tool.name().isBlank()) throw new IllegalArgumentException("tool name cannot be blank");
        if (tools.putIfAbsent(tool.name(), tool) != null) {
            throw new IllegalArgumentException("Tool already registered: " + tool.name());
        }
    }

    /** 按模型返回的名称查找工具；没有注册时返回空 Optional。 */
    public synchronized Optional<AgentTool> find(String name) {
        return Optional.ofNullable(tools.get(name));
    }

    /** 返回按注册顺序排列的不可变模型侧工具定义列表。 */
    public synchronized List<ToolDefinition> definitions() {
        return tools.values().stream().map(AgentTool::definition).toList();
    }

    /** 返回当前所有工具实现的不可变快照，主要用于启动检查和诊断。 */
    public synchronized Collection<AgentTool> all() {
        return List.copyOf(tools.values());
    }

    /** 创建仅含 allowlist 工具的不可变用途注册表，供不同 Worker 隔离工具可见范围。 */
    public synchronized ToolRegistry restrictedTo(Collection<String> allowedNames) {
        ToolRegistry restricted = new ToolRegistry();
        for (String name : allowedNames) {
            AgentTool tool = tools.get(name);
            if (tool == null) throw new IllegalArgumentException("Worker references an unregistered tool");
            restricted.register(tool);
        }
        return restricted;
    }
}
