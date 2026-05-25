package com.citycopilot.agent.config;

import com.citycopilot.agent.executor.AgentLlmClient;
import com.citycopilot.agent.executor.HeuristicAgentLlmClient;
import com.citycopilot.agent.executor.OpenAiCompatibleAgentLlmClient;
import com.citycopilot.agent.executor.PlanExecuteExecutor;
import com.citycopilot.agent.executor.ReActExecutor;
import com.citycopilot.agent.gateway.ToolAuditSink;
import com.citycopilot.agent.gateway.ToolExecutionGuard;
import com.citycopilot.agent.gateway.ToolGateway;
import com.citycopilot.agent.router.AgentRouter;
import com.citycopilot.agent.tool.AgentToolRegistry;
import com.citycopilot.agent.trace.InMemoryTraceStore;
import com.citycopilot.agent.trace.RedisTraceStore;
import com.citycopilot.agent.trace.TraceStore;
import com.citycopilot.core.agent.AgentTool;
import com.citycopilot.core.config.AiProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.client.RestTemplate;

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

@Configuration
public class AgentModuleConfiguration {

    @Bean
    public AgentToolRegistry agentToolRegistry(ApplicationContext applicationContext) {
        List<Object> toolBeans = new ArrayList<>();
        for (String beanName : applicationContext.getBeanDefinitionNames()) {
            Class<?> beanType = applicationContext.getType(beanName);
            if (beanType != null && hasAgentTool(beanType)) {
                toolBeans.add(applicationContext.getBean(beanName));
            }
        }
        return AgentToolRegistry.fromBeans(toolBeans);
    }

    private boolean hasAgentTool(Class<?> beanType) {
        if (beanType == null || beanType == Object.class) {
            return false;
        }
        for (Method method : beanType.getDeclaredMethods()) {
            if (method.isAnnotationPresent(AgentTool.class)) {
                return true;
            }
        }
        for (Class<?> contract : beanType.getInterfaces()) {
            if (hasAgentTool(contract)) {
                return true;
            }
        }
        return hasAgentTool(beanType.getSuperclass());
    }

    @Bean
    public ToolGateway toolGateway(
            AgentToolRegistry registry,
            ObjectProvider<ToolAuditSink> auditSink,
            ObjectProvider<ToolExecutionGuard> executionGuard
    ) {
        return new ToolGateway(
                registry,
                auditSink.getIfAvailable(ToolAuditSink::noop),
                executionGuard.getIfAvailable(ToolExecutionGuard::direct)
        );
    }

    @Bean
    public AgentRouter agentRouter() {
        return new AgentRouter();
    }

    @Bean
    public AgentLlmClient agentLlmClient(
            ObjectProvider<AiProperties> aiProperties,
            ObjectProvider<RestTemplateBuilder> restTemplateBuilder
    ) {
        AiProperties properties = aiProperties.getIfAvailable(AiProperties::new);
        AgentLlmClient fallback = new HeuristicAgentLlmClient(properties);
        RestTemplateBuilder builder = restTemplateBuilder.getIfAvailable(RestTemplateBuilder::new);
        int timeoutSeconds = properties.getTimeoutSeconds() == null ? 8 : Math.max(1, properties.getTimeoutSeconds());
        RestTemplate restTemplate = builder
                .setConnectTimeout(Duration.ofSeconds(Math.min(3, timeoutSeconds)))
                .setReadTimeout(Duration.ofSeconds(timeoutSeconds))
                .build();
        return new OpenAiCompatibleAgentLlmClient(restTemplate, properties, fallback);
    }

    @Bean
    public TraceStore traceStore(ObjectProvider<StringRedisTemplate> redisTemplate) {
        StringRedisTemplate template = redisTemplate.getIfAvailable();
        if (template == null) {
            return new InMemoryTraceStore();
        }
        return new RedisTraceStore(template);
    }

    @Bean
    public ReActExecutor reActExecutor(ToolGateway gateway, AgentLlmClient llmClient, TraceStore traceStore) {
        return new ReActExecutor(gateway, llmClient, traceStore, 5);
    }

    @Bean
    public PlanExecuteExecutor planExecuteExecutor(ToolGateway gateway, TraceStore traceStore) {
        return new PlanExecuteExecutor(gateway, traceStore);
    }
}
