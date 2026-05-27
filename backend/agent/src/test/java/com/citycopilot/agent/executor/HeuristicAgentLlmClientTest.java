package com.citycopilot.agent.executor;

import com.citycopilot.agent.gateway.ToolResult;
import com.citycopilot.agent.trace.AgentTraceStep;
import com.citycopilot.core.config.AiProperties;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class HeuristicAgentLlmClientTest {

    @Test
    void reportsFailedToolObservationInsteadOfSuccess() {
        HeuristicAgentLlmClient client = new HeuristicAgentLlmClient(new AiProperties());
        AgentTraceStep failedObservation = AgentTraceStep.toolResult(
                1,
                "search_nearby_shops",
                ToolResult.failure("tool_timeout", "Tool timed out: search_nearby_shops", true)
        );

        AgentDecision decision = client.next("nearby hotpot", List.of(), List.of(failedObservation), Map.of());

        assertEquals(AgentDecision.Type.FINAL, decision.type());
        assertEquals("工具调用失败：Tool timed out: search_nearby_shops", decision.finalAnswer());
    }
}
