package com.citycopilot.agent.executor;

import com.citycopilot.agent.gateway.ToolAuditSink;
import com.citycopilot.agent.gateway.ToolCallContext;
import com.citycopilot.agent.gateway.ToolGateway;
import com.citycopilot.agent.tool.AgentToolRegistry;
import com.citycopilot.agent.trace.InMemoryTraceStore;
import com.citycopilot.contract.tool.AgentTool;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReActExecutorTest {

    @Test
    void invokesToolStoresTraceAndBuildsFinalAnswer() {
        InMemoryTraceStore traceStore = new InMemoryTraceStore();
        ToolGateway gateway = new ToolGateway(
                AgentToolRegistry.fromBeans(List.of(new DemoTools())),
                ToolAuditSink.noop()
        );
        ReActExecutor executor = new ReActExecutor(
                gateway,
                new ScriptedAgentLlmClient(List.of(
                        AgentDecision.callTool("search_nearby_shops", Map.of("current", 1, "size", 5, "x", 120.1, "y", 30.2)),
                        AgentDecision.finalAnswer("附近有 2 家火锅店")
                )),
                traceStore,
                5
        );

        AgentExecutionResult result = executor.execute(
                "附近有什么店",
                new ToolCallContext("user-1", Set.of()),
                Map.of("userPreferences", Map.of(
                        "scope", "recommendation_defaults",
                        "values", Map.of("preferredCategories", List.of("火锅"))
                ))
        );

        assertEquals("附近有 2 家火锅店", result.finalAnswer());
        assertEquals(3, result.steps().size());
        assertEquals("search_nearby_shops", result.steps().get(0).toolName());
        assertEquals("火锅", result.steps().get(0).args().get("preferredCategory"));
        assertTrue(traceStore.find(result.traceId()).isPresent());
    }

    @Test
    void stopsRepeatedToolCallsBeforeLooping() {
        InMemoryTraceStore traceStore = new InMemoryTraceStore();
        ToolGateway gateway = new ToolGateway(
                AgentToolRegistry.fromBeans(List.of(new DemoTools())),
                ToolAuditSink.noop()
        );
        ReActExecutor executor = new ReActExecutor(
                gateway,
                new ScriptedAgentLlmClient(List.of(
                        AgentDecision.callTool("search_nearby_shops", Map.of("current", 1, "size", 5, "x", 120.1, "y", 30.2)),
                        AgentDecision.callTool("search_nearby_shops", Map.of("current", 1, "size", 5, "x", 120.1, "y", 30.2)),
                        AgentDecision.callTool("search_nearby_shops", Map.of("current", 1, "size", 5, "x", 120.1, "y", 30.2))
                )),
                traceStore,
                5
        );

        AgentExecutionResult result = executor.execute(
                "附近有什么火锅",
                new ToolCallContext("user-1", Set.of())
        );

        assertEquals("Agent stopped because the same tool call repeated.", result.finalAnswer());
        assertEquals(6, result.steps().size());
    }

    static final class DemoTools {
        @AgentTool(name = "search_nearby_shops", description = "Search nearby shops")
        public List<String> nearby(Integer current, Integer size, Double x, Double y) {
            return List.of("川味火锅", "湖滨火锅");
        }
    }
}
