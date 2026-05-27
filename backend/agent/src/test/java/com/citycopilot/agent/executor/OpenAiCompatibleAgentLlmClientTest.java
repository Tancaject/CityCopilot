package com.citycopilot.agent.executor;

import com.citycopilot.core.config.AiProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class OpenAiCompatibleAgentLlmClientTest {

    @Test
    void fallsBackWhenProviderIsDisabled() {
        AiProperties properties = new AiProperties();
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
}
