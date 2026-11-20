package com.example.agent.configuration;

/** Redacted value difference between two immutable configuration revisions. */
public record ConfigDifference(String path, String before, String after) { }
