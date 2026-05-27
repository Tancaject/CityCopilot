package com.citycopilot.agent.http;

import com.citycopilot.agent.executor.AgentExecutionResult;
import com.citycopilot.agent.gateway.ToolResult;
import com.citycopilot.agent.trace.AgentTraceStep;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AgentSseEventMapperTest {

    @Test
    void mapsTraceToolResultAndFinalEvents() {
        AgentExecutionResult result = new AgentExecutionResult(
                "trace-1",
                "REACT",
                List.of(
                        AgentTraceStep.toolCall(1, "search_nearby_shops", Map.of("current", 1)),
                        AgentTraceStep.toolResult(1, "search_nearby_shops", ToolResult.success(List.of("shop"))),
                        AgentTraceStep.finalAnswer(2, "done")
                ),
                "done",
                12
        );

        List<AgentSseEvent> events = AgentSseEventMapper.toEvents(result);

        assertEquals("traceId", events.get(0).event());
        assertEquals("tool", events.get(1).event());
        assertEquals("tool", events.get(2).event());
        assertEquals("final", events.get(3).event());
        assertEquals(4, events.size());
    }

    @Test
    void emitsFinalEventWhenExecutionEndsWithErrorStep() {
        AgentExecutionResult result = new AgentExecutionResult(
                "trace-2",
                "PLAN_EXECUTE",
                List.of(
                        AgentTraceStep.toolCall(1, "place_seckill_order", Map.of("voucherId", 1L)),
                        AgentTraceStep.error(1, "confirmation_required", "需要登录或授权")
                ),
                "需要登录或授权",
                9
        );

        List<AgentSseEvent> events = AgentSseEventMapper.toEvents(result);

        assertEquals("error", events.get(2).event());
        assertEquals("final", events.get(3).event());
        assertEquals(4, events.size());
    }
}
