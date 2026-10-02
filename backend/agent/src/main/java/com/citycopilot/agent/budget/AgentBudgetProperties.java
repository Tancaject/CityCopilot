package com.citycopilot.agent.budget;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/** Per-process admission and per-turn limits; retries and replans share the same turn. */
@Component
@ConfigurationProperties(prefix = "citycopilot.agent.budget")
public class AgentBudgetProperties {
    private int concurrency = 8;
    private int queueCapacity = 16;
    private Duration deadline = Duration.ofSeconds(45);
    private int requestBytes = 16_384;
    private int messageBytes = 8_192;
    private int contextBytes = 131_072;
    private int modelCalls = 10;
    private int toolCalls = 16;
    private int toolAttempts = 24;
    private int planSteps = 12;
    private int totalPlanSteps = 24;
    private int modelInputTokens = 32_768;
    private int modelOutputTokens = 2_048;
    private int totalModelOutputTokens = 12_288;
    private int toolResultBytes = 65_536;
    private int modelResponseBytes = 131_072;
    private int responseBytes = 262_144;

    public int getConcurrency() { return concurrency; }
    public void setConcurrency(int value) { concurrency = positive(value); }
    public int getQueueCapacity() { return queueCapacity; }
    public void setQueueCapacity(int value) { queueCapacity = positive(value); }
    public Duration getDeadline() { return deadline; }
    public void setDeadline(Duration value) {
        if (value == null || value.isNegative() || value.isZero()) throw new IllegalArgumentException("Agent deadline must be positive");
        deadline = value;
    }
    public int getRequestBytes() { return requestBytes; }
    public void setRequestBytes(int v) { requestBytes = positive(v); }
    public int getMessageBytes() { return messageBytes; }
    public void setMessageBytes(int v) { messageBytes = positive(v); }
    public int getContextBytes() { return contextBytes; }
    public void setContextBytes(int v) { contextBytes = positive(v); }
    public int getModelCalls() { return modelCalls; }
    public void setModelCalls(int v) { modelCalls = positive(v); }
    public int getToolCalls() { return toolCalls; }
    public void setToolCalls(int v) { toolCalls = positive(v); }
    public int getToolAttempts() { return toolAttempts; }
    public void setToolAttempts(int v) { toolAttempts = positive(v); }
    public int getPlanSteps() { return planSteps; }
    public void setPlanSteps(int v) { planSteps = positive(v); }
    public int getTotalPlanSteps() { return totalPlanSteps; }
    public void setTotalPlanSteps(int v) { totalPlanSteps = positive(v); }
    public int getModelInputTokens() { return modelInputTokens; }
    public void setModelInputTokens(int v) { modelInputTokens = positive(v); }
    public int getModelOutputTokens() { return modelOutputTokens; }
    public void setModelOutputTokens(int v) { modelOutputTokens = positive(v); }
    public int getTotalModelOutputTokens() { return totalModelOutputTokens; }
    public void setTotalModelOutputTokens(int v) { totalModelOutputTokens = positive(v); }
    public int getToolResultBytes() { return toolResultBytes; }
    public void setToolResultBytes(int v) { toolResultBytes = positive(v); }
    public int getModelResponseBytes() { return modelResponseBytes; }
    public void setModelResponseBytes(int v) { modelResponseBytes = positive(v); }
    public int getResponseBytes() { return responseBytes; }
    public void setResponseBytes(int v) { responseBytes = positive(v); }

    private static int positive(int value) {
        if (value <= 0) throw new IllegalArgumentException("Agent resource limits must be positive");
        return value;
    }
}
