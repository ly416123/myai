package com.example.agent.configuration;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/** Immutable runtime configuration content. Secret values are intentionally not representable in this type. */
public record AgentConfigDocument(int formatVersion, String systemPrompt, List<SkillDefinition> skills,
                                  ModelSettings model, Set<String> allowedTools,
                                  Map<String, String> workerContractVersions, RuntimeSettings runtime) {
    public AgentConfigDocument {
        if (formatVersion < 1) throw new IllegalArgumentException("Configuration format version must be positive");
        if (systemPrompt == null) throw new IllegalArgumentException("System prompt must be provided (empty is allowed)");
        ConfigurationSecretGuard.rejectCredentialLiteral(systemPrompt, "System prompt");
        skills = skills == null ? List.of() : List.copyOf(skills);
        skills.forEach(skill -> ConfigurationSecretGuard.rejectCredentialLiteral(skill.content(), "Skill content"));
        if (skills.stream().map(SkillDefinition::name).distinct().count() != skills.size()) {
            throw new IllegalArgumentException("Skill names must be unique");
        }
        if (model == null || runtime == null) throw new IllegalArgumentException("Model and runtime settings are required");
        var tools = new TreeSet<String>();
        if (allowedTools != null) {
            for (String tool : allowedTools) {
                if (tool == null || !tool.matches("[a-zA-Z0-9][a-zA-Z0-9._-]{0,79}")) {
                    throw new IllegalArgumentException("Allowed tool name is invalid");
                }
                tools.add(tool);
            }
        }
        allowedTools = Collections.unmodifiableSet(tools);
        var contracts = new TreeMap<String, String>();
        if (workerContractVersions != null) {
            workerContractVersions.forEach((worker, version) -> {
                if (worker == null || !worker.matches("[a-zA-Z0-9][a-zA-Z0-9._-]{0,79}"
                        ) || version == null || !version.matches("[a-zA-Z0-9][a-zA-Z0-9._-]{0,79}")) {
                    throw new IllegalArgumentException("Worker contract reference is invalid");
                }
                contracts.put(worker, version);
            });
        }
        workerContractVersions = Collections.unmodifiableMap(contracts);
    }
}
