package com.citycopilot.agent.executor;

import com.citycopilot.agent.trace.AgentTraceStep;
import com.citycopilot.agent.tool.ToolMetadata;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

public interface AgentLlmClient {
    AgentDecision next(String message, List<ToolMetadata> tools, List<AgentTraceStep> observations);

    default AgentDecision next(
            String message,
            List<ToolMetadata> tools,
            List<AgentTraceStep> observations,
            Map<String, Object> hints
    ) {
        return next(message, tools, observations);
    }

    default String complete(String systemPrompt, List<String> userMessages) {
        return "";
    }

    default String completeStreaming(
            String systemPrompt,
            List<String> userMessages,
            Consumer<String> deltaConsumer
    ) {
        String answer = complete(systemPrompt, userMessages);
        if (answer != null && !answer.isEmpty() && deltaConsumer != null) {
            deltaConsumer.accept(answer);
        }
        return answer;
    }
}
