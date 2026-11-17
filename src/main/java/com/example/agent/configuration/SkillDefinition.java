package com.example.agent.configuration;

/** Immutable Skill text captured by a configuration revision. */
public record SkillDefinition(String name, String content) {
    public SkillDefinition {
        if (name == null || !name.matches("[a-zA-Z0-9][a-zA-Z0-9._-]{0,79}")) {
            throw new IllegalArgumentException("Skill name is invalid");
        }
        if (content == null || content.isBlank()) throw new IllegalArgumentException("Skill content is required");
    }
}
