package com.citycopilot.agent.config;

import com.citycopilot.agent.executor.AgentLlmClient;
import com.citycopilot.agent.confirmation.InMemoryPendingActionStore;
import com.citycopilot.agent.confirmation.PendingActionStore;
import com.citycopilot.agent.confirmation.RedisPendingActionStore;
import com.citycopilot.agent.executor.CriticAgent;
import com.citycopilot.agent.executor.HeuristicAgentLlmClient;
import com.citycopilot.agent.executor.OpenAiCompatibleAgentLlmClient;
import com.citycopilot.agent.executor.PlanExecuteExecutor;
import com.citycopilot.agent.executor.ReActExecutor;
import com.citycopilot.agent.gateway.ToolExecutionGuard;
import com.citycopilot.agent.gateway.ToolGatewayClient;
import com.citycopilot.agent.router.AgentRouter;
import com.citycopilot.agent.memory.ConversationMemory;
import com.citycopilot.agent.memory.InMemoryConversationMemory;
import com.citycopilot.agent.memory.RedisConversationMemory;
import com.citycopilot.agent.trace.InMemoryTraceStore;
import com.citycopilot.agent.trace.RedisTraceStore;
import com.citycopilot.agent.trace.TraceStore;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;

@Configuration
public class AgentModuleConfiguration {

    @Bean
    public AgentRouter agentRouter() {
        return new AgentRouter();
    }

    @Bean
    public AgentLlmClient agentLlmClient(
            ObjectProvider<AgentAiProperties> aiProperties,
            ObjectProvider<RestTemplateBuilder> restTemplateBuilder,
            ObjectProvider<MeterRegistry> meterRegistry
    ) {
        AgentAiProperties properties = aiProperties.getIfAvailable(AgentAiProperties::new);
        AgentLlmClient fallback = new HeuristicAgentLlmClient(properties);
        RestTemplateBuilder builder = restTemplateBuilder.getIfAvailable(RestTemplateBuilder::new);
        int timeoutSeconds = properties.getTimeoutSeconds() == null ? 8 : Math.max(1, properties.getTimeoutSeconds());
        RestTemplate restTemplate = builder
                .setConnectTimeout(Duration.ofSeconds(Math.min(3, timeoutSeconds)))
                .setReadTimeout(Duration.ofSeconds(timeoutSeconds))
                .build();
        return new OpenAiCompatibleAgentLlmClient(restTemplate, properties, fallback, meterRegistry.getIfAvailable());
    }

    @Bean
    public TraceStore traceStore(
            ObjectProvider<StringRedisTemplate> redisTemplate,
            @Value("${citycopilot.runtime.require-shared-state:false}") boolean requireSharedState
    ) {
        StringRedisTemplate template = redisTemplate.getIfAvailable();
        if (template == null) {
            requireSharedState("TraceStore", requireSharedState);
            return new InMemoryTraceStore();
        }
        return new RedisTraceStore(template);
    }

    @Bean
    public PendingActionStore pendingActionStore(
            ObjectProvider<StringRedisTemplate> redisTemplate,
            @Value("${citycopilot.runtime.require-shared-state:false}") boolean requireSharedState
    ) {
        StringRedisTemplate template = redisTemplate.getIfAvailable();
        if (template == null) {
            requireSharedState("PendingActionStore", requireSharedState);
            return new InMemoryPendingActionStore();
        }
        return new RedisPendingActionStore(template);
    }

    @Bean
    public ConversationMemory conversationMemory(
            ObjectProvider<StringRedisTemplate> redisTemplate,
            @Value("${citycopilot.runtime.require-shared-state:false}") boolean requireSharedState
    ) {
        StringRedisTemplate template = redisTemplate.getIfAvailable();
        if (template == null) {
            requireSharedState("ConversationMemory", requireSharedState);
            return new InMemoryConversationMemory();
        }
        return new RedisConversationMemory(template);
    }

    private void requireSharedState(String component, boolean required) {
        if (required) {
            throw new IllegalStateException(
                    component + " requires Redis-backed shared state in the production runtime"
            );
        }
    }

    @Bean
    public ReActExecutor reActExecutor(ToolGatewayClient gateway, AgentLlmClient llmClient, TraceStore traceStore) {
        return new ReActExecutor(gateway, llmClient, traceStore, 5);
    }

    @Bean
    public CriticAgent criticAgent(AgentLlmClient llmClient) {
        return new CriticAgent(llmClient);
    }

    @Bean
    public PlanExecuteExecutor planExecuteExecutor(
            ToolGatewayClient gateway,
            AgentLlmClient llmClient,
            TraceStore traceStore,
            CriticAgent criticAgent,
            PendingActionStore pendingActionStore
    ) {
        return new PlanExecuteExecutor(llmClient, gateway, traceStore, 2, criticAgent, pendingActionStore);
    }
}
