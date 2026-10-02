package com.citycopilot.agent.budget;

import com.citycopilot.agent.config.AgentAiProperties;
import com.citycopilot.agent.executor.AgentDecision;
import com.citycopilot.agent.executor.AgentLlmClient;
import com.citycopilot.agent.executor.OpenAiCompatibleAgentLlmClient;
import com.citycopilot.agent.tool.ToolMetadata;
import com.citycopilot.agent.trace.AgentTraceStep;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class AgentModelBudgetTest {
    @Test void exhaustedInputNeverSendsOrFallsBack() {
        RestTemplate rest = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(rest).build();
        AtomicInteger fallbackCalls = new AtomicInteger();
        var client = new OpenAiCompatibleAgentLlmClient(rest, enabled(), fallback(fallbackCalls));
        AgentBudgetProperties limits = new AgentBudgetProperties(); limits.setModelInputTokens(8);
        try (AgentBudget.Scope ignored = new AgentBudget(limits).bind()) {
            assertEquals("agent_model_input_budget", assertThrows(AgentBudgetException.class,
                    () -> client.complete("planner", List.of("request"))).code());
        }
        assertEquals(0, fallbackCalls.get()); server.verify();
    }

    @Test void decisionAndCompletionShareCountAndSendProviderOutputCeiling() {
        RestTemplate rest = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(rest).build();
        for (int i = 0; i < 2; i++) {
            server.expect(content().json("{\"max_tokens\":128}"))
                    .andRespond(withSuccess("{\"choices\":[{\"message\":{\"content\":\"ok\"}}]}", MediaType.APPLICATION_JSON));
        }
        AtomicInteger fallbackCalls = new AtomicInteger();
        var client = new OpenAiCompatibleAgentLlmClient(rest, enabled(), fallback(fallbackCalls));
        AgentBudgetProperties limits = new AgentBudgetProperties(); limits.setModelCalls(2); limits.setModelOutputTokens(128);
        try (AgentBudget.Scope ignored = new AgentBudget(limits).bind()) {
            client.next("request", List.of(), List.of());
            assertEquals("ok", client.complete("critic", List.of("context")));
            assertEquals("agent_model_calls_exceeded", assertThrows(AgentBudgetException.class,
                    () -> client.completeStreaming("summary", List.of("context"), value -> fail())).code());
        }
        assertEquals(0, fallbackCalls.get()); server.verify();
    }

    @Test void cancelledStreamingDoesNotInvokeFallbackOrEmitAnotherDelta() {
        RestTemplate rest = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(rest).build();
        server.expect(anything()).andRespond(withSuccess(
                "data: {\"choices\":[{\"delta\":{\"content\":\"one\"}}]}\n\ndata: {\"choices\":[{\"delta\":{\"content\":\"two\"}}]}\n\n",
                MediaType.TEXT_EVENT_STREAM));
        AtomicInteger fallbacks = new AtomicInteger(); AtomicInteger deltas = new AtomicInteger();
        var client = new OpenAiCompatibleAgentLlmClient(rest, enabled(), fallback(fallbacks));
        AgentBudget budget = new AgentBudget(new AgentBudgetProperties());
        try (AgentBudget.Scope ignored = budget.bind()) {
            assertThrows(AgentBudgetException.class, () -> client.completeStreaming("summary", List.of("context"), value -> {
                deltas.incrementAndGet(); budget.cancel("agent_cancelled");
            }));
        }
        assertEquals(1, deltas.get()); assertEquals(0, fallbacks.get()); server.verify();
    }

    private AgentAiProperties enabled() {
        AgentAiProperties properties = new AgentAiProperties();
        properties.setEnabled(true); properties.setApiKey("local-test"); properties.setApiUrl("http://localhost/model");
        return properties;
    }

    private AgentLlmClient fallback(AtomicInteger calls) {
        return new AgentLlmClient() {
            @Override public AgentDecision next(String message, List<ToolMetadata> tools, List<AgentTraceStep> observations) {
                calls.incrementAndGet(); return AgentDecision.finalAnswer("fallback");
            }
            @Override public String complete(String prompt, List<String> messages) { calls.incrementAndGet(); return "fallback"; }
        };
    }
}
