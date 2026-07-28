package com.citycopilot.agent.executor;

import com.citycopilot.agent.config.AgentAiProperties;
import com.citycopilot.agent.tool.ToolMetadata;
import com.citycopilot.agent.tool.ToolParameter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;
import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class OpenAiCompatibleAgentLlmClientTest {

    @Test
    void fallsBackWhenProviderIsDisabled() {
        AgentAiProperties properties = new AgentAiProperties();
        properties.setEnabled(false);
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        OpenAiCompatibleAgentLlmClient client = new OpenAiCompatibleAgentLlmClient(
                new RestTemplate(),
                properties,
                (message, tools, observations) -> AgentDecision.finalAnswer("fallback"),
                meterRegistry
        );

        AgentDecision decision = client.next("hello", List.of(), List.of(), Map.of());

        assertEquals(AgentDecision.Type.FINAL, decision.type());
        assertEquals("fallback", decision.finalAnswer());
        assertEquals(1.0, meterRegistry.get("citycopilot.llm.call.count")
                .tag("operation", "decision")
                .tag("outcome", "disabled")
                .counter()
                .count());
        assertEquals(1, meterRegistry.get("citycopilot.llm.call.duration")
                .tag("operation", "decision")
                .tag("outcome", "disabled")
                .timer()
                .count());
    }

    @Test
    void sendsFunctionSchemasAndParsesToolCalls() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        server.expect(content().string(org.hamcrest.Matchers.allOf(
                        org.hamcrest.Matchers.containsString("\"tools\""),
                        org.hamcrest.Matchers.containsString("\"type\":\"function\""),
                        org.hamcrest.Matchers.containsString("\"name\":\"search_nearby_shops\""),
                        org.hamcrest.Matchers.containsString("\"required\":[\"current\"]"),
                        org.hamcrest.Matchers.containsString("\\\"userPreferences\\\""),
                        org.hamcrest.Matchers.containsString("Preferences never authorize side effects")
                )))
                .andRespond(withSuccess(
                        """
                        {"choices":[{"message":{"role":"assistant","content":null,"tool_calls":[
                          {"id":"call_1","type":"function","function":{"name":"search_nearby_shops","arguments":"{\\\"current\\\":1}"}}
                        ]}}]}
                        """,
                        MediaType.APPLICATION_JSON
                ));
        AgentAiProperties properties = enabledProperties();
        OpenAiCompatibleAgentLlmClient client = new OpenAiCompatibleAgentLlmClient(
                restTemplate,
                properties,
                (message, tools, observations) -> AgentDecision.finalAnswer("fallback")
        );
        ToolMetadata tool = new ToolMetadata(
                "search_nearby_shops",
                "Search nearby shops",
                List.of(),
                60,
                true,
                null,
                null,
                null,
                List.of(new ToolParameter("current", Integer.class, true))
        );

        AgentDecision decision = client.next(
                "附近店铺",
                List.of(tool),
                List.of(),
                Map.of("userPreferences", Map.of(
                        "scope", "recommendation_defaults",
                        "values", Map.of("preferredCategories", List.of("火锅"))
                ))
        );

        assertEquals(AgentDecision.Type.TOOL_CALL, decision.type());
        assertEquals("search_nearby_shops", decision.toolName());
        assertEquals(1, ((Number) decision.args().get("current")).intValue());
        server.verify();
    }

    @Test
    void streamsCompletionDeltasFromProviderSse() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        server.expect(content().string(org.hamcrest.Matchers.containsString("\"stream\":true")))
                .andRespond(withSuccess(
                        "data: {\"choices\":[{\"delta\":{\"content\":\"你好\"}}]}\n\n"
                                + "data: {\"choices\":[{\"delta\":{\"content\":\"，杭州\"}}]}\n\n"
                                + "data: [DONE]\n\n",
                        MediaType.TEXT_EVENT_STREAM
                ));
        OpenAiCompatibleAgentLlmClient client = new OpenAiCompatibleAgentLlmClient(
                restTemplate,
                enabledProperties(),
                (message, tools, observations) -> AgentDecision.finalAnswer("fallback")
        );
        List<String> deltas = new ArrayList<>();

        String answer = client.completeStreaming("system", List.of("user"), deltas::add);

        assertEquals("你好，杭州", answer);
        assertEquals(List.of("你好", "，杭州"), deltas);
        server.verify();
    }

    private AgentAiProperties enabledProperties() {
        AgentAiProperties properties = new AgentAiProperties();
        properties.setEnabled(true);
        properties.setApiKey("test-key");
        properties.setApiUrl("https://example.test/chat/completions");
        properties.setModel("test-model");
        return properties;
    }
}
