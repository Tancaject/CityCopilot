package com.citycopilot.agent.config;

import com.citycopilot.agent.executor.AgentLlmClient;
import com.citycopilot.agent.budget.AgentBudget;
import com.citycopilot.agent.budget.AgentBudgetProperties;
import com.citycopilot.http.DeadlineHttpRequestFactory;
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
import org.springframework.boot.restclient.RestTemplateBuilder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;

/**
 * Agent 核心运行时的装配入口。
 *
 * <p>在这里连接路由器、模型客户端、执行器以及 Redis/内存状态存储；生产环境可通过共享状态开关禁止无意降级为单机内存实现。</p>
 */
@Configuration
public class AgentModuleConfiguration {

    /** @return 根据消息与提示选择 ReAct 或 Plan-Execute 的路由器 */
    @Bean
    public AgentRouter agentRouter() {
        return new AgentRouter();
    }

    /**
     * 构建 OpenAI 兼容模型客户端，并保留确定性的启发式兜底。
     *
     * @param aiProperties AI 配置，可在精简测试上下文中缺省
     * @param restTemplateBuilder HTTP 客户端构建器
     * @param meterRegistry 可选的模型调用指标注册器
     * @return 统一的模型决策客户端
     */
    @Bean
    public AgentLlmClient agentLlmClient(
            ObjectProvider<AgentAiProperties> aiProperties,
            ObjectProvider<RestTemplateBuilder> restTemplateBuilder,
            ObjectProvider<MeterRegistry> meterRegistry,
            ObjectProvider<AgentBudgetProperties> budgetProperties
    ) {
        AgentAiProperties properties = aiProperties.getIfAvailable(AgentAiProperties::new);
        AgentLlmClient fallback = new HeuristicAgentLlmClient(properties);
        RestTemplateBuilder builder = restTemplateBuilder.getIfAvailable(RestTemplateBuilder::new);
        int timeoutSeconds = properties.getTimeoutSeconds() == null ? 8 : Math.max(1, properties.getTimeoutSeconds());
        AgentBudgetProperties limits = budgetProperties.getIfAvailable(AgentBudgetProperties::new);
        RestTemplate restTemplate = builder
                .requestFactory(() -> new DeadlineHttpRequestFactory(
                        Duration.ofSeconds(Math.min(3, timeoutSeconds)), Duration.ofSeconds(timeoutSeconds),
                        AgentBudget::current, false, limits.getConcurrency()))
                .build();
        return new OpenAiCompatibleAgentLlmClient(restTemplate, properties, fallback, meterRegistry.getIfAvailable());
    }

    /**
     * 选择 Trace 存储。Redis 不可用且未强制共享状态时，退化为仅供单实例使用的内存存储。
     *
     * @param redisTemplate 可选 Redis 客户端
     * @param requireSharedState 是否禁止单机内存降级
     * @return Trace 存储实现
     */
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

    /**
     * 选择高风险操作确认存储；Redis 实现使一次性确认可跨实例消费。
     *
     * @param redisTemplate 可选 Redis 客户端
     * @param requireSharedState 是否禁止单机内存降级
     * @return 待确认动作存储
     */
    @Bean
    public PendingActionStore pendingActionStore(
            ObjectProvider<StringRedisTemplate> redisTemplate,
            tools.jackson.databind.ObjectMapper objectMapper,
            @Value("${citycopilot.runtime.require-shared-state:false}") boolean requireSharedState
    ) {
        StringRedisTemplate template = redisTemplate.getIfAvailable();
        if (template == null) {
            requireSharedState("PendingActionStore", requireSharedState);
            return new InMemoryPendingActionStore();
        }
        return new RedisPendingActionStore(template, objectMapper);
    }

    /**
     * 选择短期会话记忆实现；生产共享运行时应使用 Redis 保持跨实例连续性。
     *
     * @param redisTemplate 可选 Redis 客户端
     * @param requireSharedState 是否禁止单机内存降级
     * @return 会话记忆实现
     */
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

    /** 在要求共享状态时拒绝静默退化，避免多实例间出现状态分裂。 */
    private void requireSharedState(String component, boolean required) {
        if (required) {
            throw new IllegalStateException(
                    component + " requires Redis-backed shared state in the production runtime"
            );
        }
    }

    /** @return 最多执行五轮“思考-工具-观察”的 ReAct 执行器 */
    @Bean
    public ReActExecutor reActExecutor(ToolGatewayClient gateway, AgentLlmClient llmClient, TraceStore traceStore) {
        return new ReActExecutor(gateway, llmClient, traceStore, 5);
    }

    /** @return 用于检查计划执行结果是否达标的批评器 */
    @Bean
    public CriticAgent criticAgent(AgentLlmClient llmClient) {
        return new CriticAgent(llmClient);
    }

    /**
     * 构建支持重规划、关键动作确认和 Trace 记录的 Plan-Execute 执行器。
     *
     * @param gateway 远程工具调用入口
     * @param llmClient 模型决策客户端
     * @param traceStore 执行轨迹存储
     * @param criticAgent 结果复核器
     * @param pendingActionStore 一次性确认检查点存储
     * @return Plan-Execute 执行器
     */
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
