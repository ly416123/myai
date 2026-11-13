package com.example.agent;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.math.BigDecimal;
import java.nio.file.Path;

/** Externalized process settings. Secrets are loaded at startup and are never exposed through getters in logs. */
@ConfigurationProperties(prefix = "agent")
public class AgentProperties {
    private String model = "deepseek-flash";
    private String modelBaseUrl = "https://api.deepseek.com";
    private String modelProxyUrl = "";
    private String apiKeyFile = "deepseek.key";
    private Duration modelTimeout = Duration.ofSeconds(60);
    private Duration toolTimeout = Duration.ofSeconds(10);
    private Duration taskTimeout = Duration.ofMinutes(5);
    private Duration runLeaseDuration = Duration.ofSeconds(30);
    private Duration runLeaseRenewalInterval = Duration.ofSeconds(10);
    private int maxModelCalls = 10;
    private int maxToolCalls = 20;
    private int maxModelRetries = 2;
    private int maxTasks = 8;
    private int maxParallelism = 2;
    private boolean separateTaskWorkers;
    private String executionMode = "interactive";
    private String task;
    private int workerConcurrency = 4;
    private int workerExpectedInstances = 1;
    private Duration workerPollInterval = Duration.ofSeconds(1);
    private Duration retentionPeriod = Duration.ZERO;
    private int retentionBatchSize = 500;
    private boolean evaluationEnabled;
    private Path evaluationDataset = Path.of("eval/scenarios-v2.json");
    private Path evaluationReportDirectory = Path.of("var/agent-evaluations");
    private int evaluationMaxCases = 5;
    private int evaluationMaxModelCalls = 16;
    private int evaluationMaxToolCalls = 10;
    private Duration evaluationTimeout = Duration.ofMinutes(10);
    private BigDecimal evaluationInputUsdPerMillionTokens;
    private BigDecimal evaluationOutputUsdPerMillionTokens;

    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    public String getModelBaseUrl() { return modelBaseUrl; }
    public void setModelBaseUrl(String modelBaseUrl) { this.modelBaseUrl = modelBaseUrl; }
    public String getModelProxyUrl() { return modelProxyUrl; }
    public void setModelProxyUrl(String modelProxyUrl) { this.modelProxyUrl = modelProxyUrl; }
    public String getApiKeyFile() { return apiKeyFile; }
    public void setApiKeyFile(String apiKeyFile) { this.apiKeyFile = apiKeyFile; }
    public Duration getModelTimeout() { return modelTimeout; }
    public void setModelTimeout(Duration modelTimeout) { this.modelTimeout = modelTimeout; }
    public Duration getToolTimeout() { return toolTimeout; }
    public void setToolTimeout(Duration toolTimeout) { this.toolTimeout = toolTimeout; }
    public Duration getTaskTimeout() { return taskTimeout; }
    public void setTaskTimeout(Duration taskTimeout) { this.taskTimeout = taskTimeout; }
    public Duration getRunLeaseDuration() { return runLeaseDuration; }
    public void setRunLeaseDuration(Duration runLeaseDuration) { this.runLeaseDuration = runLeaseDuration; }
    public Duration getRunLeaseRenewalInterval() { return runLeaseRenewalInterval; }
    public void setRunLeaseRenewalInterval(Duration runLeaseRenewalInterval) { this.runLeaseRenewalInterval = runLeaseRenewalInterval; }
    public int getMaxModelCalls() { return maxModelCalls; }
    public void setMaxModelCalls(int maxModelCalls) { this.maxModelCalls = maxModelCalls; }
    public int getMaxToolCalls() { return maxToolCalls; }
    public void setMaxToolCalls(int maxToolCalls) { this.maxToolCalls = maxToolCalls; }
    public int getMaxModelRetries() { return maxModelRetries; }
    public void setMaxModelRetries(int maxModelRetries) { this.maxModelRetries = maxModelRetries; }
    public int getMaxTasks() { return maxTasks; }
    public void setMaxTasks(int maxTasks) { this.maxTasks = maxTasks; }
    public int getMaxParallelism() { return maxParallelism; }
    public void setMaxParallelism(int maxParallelism) { this.maxParallelism = maxParallelism; }
    public boolean isSeparateTaskWorkers() { return separateTaskWorkers; }
    public void setSeparateTaskWorkers(boolean separateTaskWorkers) { this.separateTaskWorkers = separateTaskWorkers; }
    public String getExecutionMode() { return executionMode; }
    public void setExecutionMode(String executionMode) { this.executionMode = executionMode; }
    public String getTask() { return task; }
    public void setTask(String task) { this.task = task; }
    public int getWorkerConcurrency() { return workerConcurrency; }
    public void setWorkerConcurrency(int workerConcurrency) { this.workerConcurrency = workerConcurrency; }
    public int getWorkerExpectedInstances() { return workerExpectedInstances; }
    public void setWorkerExpectedInstances(int workerExpectedInstances) { this.workerExpectedInstances = workerExpectedInstances; }
    public Duration getWorkerPollInterval() { return workerPollInterval; }
    public void setWorkerPollInterval(Duration workerPollInterval) { this.workerPollInterval = workerPollInterval; }
    public Duration getRetentionPeriod() { return retentionPeriod; }
    public void setRetentionPeriod(Duration retentionPeriod) { this.retentionPeriod = retentionPeriod; }
    public int getRetentionBatchSize() { return retentionBatchSize; }
    public void setRetentionBatchSize(int retentionBatchSize) { this.retentionBatchSize = retentionBatchSize; }
    public boolean isEvaluationEnabled() { return evaluationEnabled; }
    public void setEvaluationEnabled(boolean evaluationEnabled) { this.evaluationEnabled = evaluationEnabled; }
    public Path getEvaluationDataset() { return evaluationDataset; }
    public void setEvaluationDataset(Path evaluationDataset) { this.evaluationDataset = evaluationDataset; }
    public Path getEvaluationReportDirectory() { return evaluationReportDirectory; }
    public void setEvaluationReportDirectory(Path evaluationReportDirectory) { this.evaluationReportDirectory = evaluationReportDirectory; }
    public int getEvaluationMaxCases() { return evaluationMaxCases; }
    public void setEvaluationMaxCases(int evaluationMaxCases) { this.evaluationMaxCases = evaluationMaxCases; }
    public int getEvaluationMaxModelCalls() { return evaluationMaxModelCalls; }
    public void setEvaluationMaxModelCalls(int evaluationMaxModelCalls) { this.evaluationMaxModelCalls = evaluationMaxModelCalls; }
    public int getEvaluationMaxToolCalls() { return evaluationMaxToolCalls; }
    public void setEvaluationMaxToolCalls(int evaluationMaxToolCalls) { this.evaluationMaxToolCalls = evaluationMaxToolCalls; }
    public Duration getEvaluationTimeout() { return evaluationTimeout; }
    public void setEvaluationTimeout(Duration evaluationTimeout) { this.evaluationTimeout = evaluationTimeout; }
    public BigDecimal getEvaluationInputUsdPerMillionTokens() { return evaluationInputUsdPerMillionTokens; }
    public void setEvaluationInputUsdPerMillionTokens(BigDecimal value) { this.evaluationInputUsdPerMillionTokens = value; }
    public BigDecimal getEvaluationOutputUsdPerMillionTokens() { return evaluationOutputUsdPerMillionTokens; }
    public void setEvaluationOutputUsdPerMillionTokens(BigDecimal value) { this.evaluationOutputUsdPerMillionTokens = value; }
}
