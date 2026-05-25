package com.citycopilot.agent.executor;

import com.citycopilot.agent.trace.AgentTraceStep;
import com.citycopilot.agent.tool.ToolMetadata;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;

final class ScriptedAgentLlmClient implements AgentLlmClient {
    private final Queue<AgentDecision> decisions;

    ScriptedAgentLlmClient(List<AgentDecision> decisions) {
        this.decisions = new ArrayDeque<>(decisions);
    }

    @Override
    public AgentDecision next(String message, List<ToolMetadata> tools, List<AgentTraceStep> observations) {
        return decisions.isEmpty() ? AgentDecision.finalAnswer("done") : decisions.remove();
    }
}
