package com.citycopilot.agent.executor;

import com.citycopilot.agent.trace.AgentTraceStep;
import com.citycopilot.agent.tool.ToolMetadata;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;

final class ScriptedAgentLlmClient implements AgentLlmClient {
    private final Queue<AgentDecision> decisions;
    private final Queue<String> completions;

    ScriptedAgentLlmClient(List<AgentDecision> decisions) {
        this.decisions = new ArrayDeque<>(decisions);
        this.completions = new ArrayDeque<>();
    }

    ScriptedAgentLlmClient(List<AgentDecision> decisions, List<String> completions) {
        this.decisions = new ArrayDeque<>(decisions);
        this.completions = new ArrayDeque<>(completions);
    }

    @Override
    public AgentDecision next(String message, List<ToolMetadata> tools, List<AgentTraceStep> observations) {
        return decisions.isEmpty() ? AgentDecision.finalAnswer("done") : decisions.remove();
    }

    @Override
    public String complete(String systemPrompt, List<String> userMessages) {
        return completions.isEmpty() ? "" : completions.remove();
    }
}
