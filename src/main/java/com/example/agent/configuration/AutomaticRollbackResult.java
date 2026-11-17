package com.example.agent.configuration;

/** Counts describe only completed candidate Runs visible inside the configured time window. */
public record AutomaticRollbackResult(AutomaticRollbackOutcome outcome, int samples, int failures,
                                      Double failureRate) { }
