package com.citycopilot.agent.executor;

import com.citycopilot.agent.gateway.ToolResult;
import com.citycopilot.agent.trace.AgentTraceStep;
import com.citycopilot.agent.config.AgentAiProperties;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class HeuristicAgentLlmClientTest {

    @Test
    void reportsFailedToolObservationInsteadOfSuccess() {
        HeuristicAgentLlmClient client = new HeuristicAgentLlmClient(new AgentAiProperties());
        AgentTraceStep failedObservation = AgentTraceStep.toolResult(
                1,
                "search_nearby_shops",
                ToolResult.failure("tool_timeout", "Tool timed out: search_nearby_shops", true)
        );

        AgentDecision decision = client.next("nearby hotpot", List.of(), List.of(failedObservation), Map.of());

        assertEquals(AgentDecision.Type.FINAL, decision.type());
        assertEquals("工具调用失败：Tool timed out: search_nearby_shops", decision.finalAnswer());
    }

    @Test
    void appliesRememberedCategoryToGenericNearbySearch() {
        HeuristicAgentLlmClient client = new HeuristicAgentLlmClient(new AgentAiProperties());

        AgentDecision decision = client.next(
                "推荐附近的店",
                List.of(),
                List.of(),
                hints(List.of("火锅", "咖啡"))
        );

        assertEquals(AgentDecision.Type.TOOL_CALL, decision.type());
        assertEquals("search_nearby_shops", decision.toolName());
        assertEquals("火锅", decision.args().get("preferredCategory"));
    }

    @Test
    void currentCategoryOverridesMemoryAndConversationHistory() {
        HeuristicAgentLlmClient client = new HeuristicAgentLlmClient(new AgentAiProperties());

        AgentDecision decision = client.next(
                "Conversation history:\nuser: 推荐烧烤\nCurrent user: 推荐附近的咖啡店",
                List.of(),
                List.of(),
                hints(List.of("火锅"))
        );

        assertEquals("咖啡", decision.args().get("preferredCategory"));
    }

    @Test
    void currentExclusionPreventsRememberedCategoryFromReturning() {
        HeuristicAgentLlmClient client = new HeuristicAgentLlmClient(new AgentAiProperties());

        AgentDecision decision = client.next(
                "推荐附近的店，不要火锅",
                List.of(),
                List.of(),
                hints(List.of("火锅"))
        );

        assertFalse(decision.args().containsKey("preferredCategory"));
    }

    @Test
    void categoryRequestTriggersSearchWithoutNearbyKeyword() {
        HeuristicAgentLlmClient client = new HeuristicAgentLlmClient(new AgentAiProperties());

        AgentDecision decision = client.next("想吃川菜", List.of(), List.of(), Map.of());

        assertEquals(AgentDecision.Type.TOOL_CALL, decision.type());
        assertEquals("search_nearby_shops", decision.toolName());
        assertEquals("川菜", decision.args().get("preferredCategory"));
    }

    @Test
    void conversationHistoryDoesNotRouteTheCurrentTurn() {
        HeuristicAgentLlmClient client = new HeuristicAgentLlmClient(new AgentAiProperties());

        AgentDecision decision = client.next(
                "Conversation history:\nuser: 推荐附近火锅和优惠券\nCurrent user: 谢谢",
                List.of(),
                List.of(),
                hints(List.of("火锅"))
        );

        assertEquals(AgentDecision.Type.FINAL, decision.type());
    }

    private Map<String, Object> hints(List<String> categories) {
        return Map.of(
                "x", 120.1D,
                "y", 30.2D,
                "userPreferences", Map.of(
                        "scope", "recommendation_defaults",
                        "values", Map.of("preferredCategories", categories)
                )
        );
    }
}
