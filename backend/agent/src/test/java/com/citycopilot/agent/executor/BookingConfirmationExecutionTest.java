package com.citycopilot.agent.executor;

import com.citycopilot.agent.confirmation.InMemoryPendingActionStore;
import com.citycopilot.agent.gateway.*;
import com.citycopilot.agent.tool.AgentToolRegistry;
import com.citycopilot.agent.trace.InMemoryTraceStore;
import com.citycopilot.contract.tool.AgentTool;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class BookingConfirmationExecutionTest {
    @Test void reactStopsAtCoreCardBeforeTheModelCanIssueFinalConfirmation() {
        var tools = new ProposalTools();
        var gateway = new ToolGateway(AgentToolRegistry.fromBeans(List.of(tools)), ToolAuditSink.noop());
        var llm = new ScriptedAgentLlmClient(List.of(AgentDecision.callTool("hold_booking", Map.of("quoteId", "q")),
                AgentDecision.callTool("confirm_booking", Map.of("bookingId", "fabricated"))));
        var result = new ReActExecutor(gateway, llm, new InMemoryTraceStore(), 5)
                .execute("帮我预约", new ToolCallContext("701", Set.of()));
        assertTrue(result.finalAnswer().contains("尚未执行"));
        assertEquals(1, tools.holds); assertEquals(0, tools.confirmations);
    }
    @Test void planIgnoresModelHighRiskForProposalAndStopsWithoutCreatingAgentCheckpoint() {
        var tools = new ProposalTools();
        var gateway = new ToolGateway(AgentToolRegistry.fromBeans(List.of(tools)), ToolAuditSink.noop());
        var llm = new ScriptedAgentLlmClient(List.of(), List.of("""
                {"steps":[{"idx":1,"tool":"hold_booking","args":{"quoteId":"q"},"highRisk":true},
                {"idx":2,"tool":"confirm_booking","args":{"bookingId":"fabricated"},"highRisk":true}]}
                """));
        var result = new PlanExecuteExecutor(llm, gateway, new InMemoryTraceStore(), 2, null, new InMemoryPendingActionStore())
                .execute("帮我预约并确认", new ToolCallContext("701", Set.of()), Map.of());
        assertTrue(result.finalAnswer().contains("尚未执行"));
        assertTrue(result.steps().stream().noneMatch(step -> step.type().equals("confirmation")));
        assertEquals(1, tools.holds); assertEquals(0, tools.confirmations);
    }
    static class ProposalTools {
        int holds; int confirmations;
        @AgentTool(name = "hold_booking", description = "Create a review card", scope = "booking-confirmation")
        public Map<String, Object> hold(String quoteId) {
            holds++; return Map.of("type", "BOOKING_CONFIRMATION", "operationId", "bc-existing", "state", "PENDING");
        }
        @AgentTool(name = "confirm_booking", description = "Create a final review card", scope = "booking-confirmation")
        public Map<String, Object> confirm(String bookingId) { confirmations++; return Map.of(); }
    }
}
