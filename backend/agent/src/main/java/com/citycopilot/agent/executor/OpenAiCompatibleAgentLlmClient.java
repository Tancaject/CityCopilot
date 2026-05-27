package com.citycopilot.agent.executor;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.citycopilot.agent.trace.AgentTraceStep;
import com.citycopilot.agent.tool.ToolMetadata;
import com.citycopilot.core.config.AiProperties;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class OpenAiCompatibleAgentLlmClient implements AgentLlmClient {
    private final RestTemplate restTemplate;
    private final AiProperties properties;
    private final AgentLlmClient fallback;
    private final MeterRegistry meterRegistry;

    public OpenAiCompatibleAgentLlmClient(RestTemplate restTemplate, AiProperties properties, AgentLlmClient fallback) {
        this(restTemplate, properties, fallback, null);
    }

    public OpenAiCompatibleAgentLlmClient(
            RestTemplate restTemplate,
            AiProperties properties,
            AgentLlmClient fallback,
            MeterRegistry meterRegistry
    ) {
        this.restTemplate = restTemplate;
        this.properties = properties;
        this.fallback = fallback;
        this.meterRegistry = meterRegistry;
    }

    @Override
    public AgentDecision next(String message, List<ToolMetadata> tools, List<AgentTraceStep> observations) {
        return next(message, tools, observations, Map.of());
    }

    @Override
    public AgentDecision next(
            String message,
            List<ToolMetadata> tools,
            List<AgentTraceStep> observations,
            Map<String, Object> hints
    ) {
        long startNanos = System.nanoTime();
        if (!isEnabled()) {
            recordMetrics("decision", "disabled", startNanos);
            return fallback.next(message, tools, observations, hints);
        }
        try {
            ResponseEntity<Map> response = restTemplate.postForEntity(
                    properties.getApiUrl(),
                    new HttpEntity<>(requestBody(message, tools, observations, hints), headers()),
                    Map.class
            );
            AgentDecision decision = parseDecision(response.getBody());
            if (decision == null) {
                recordMetrics("decision", "fallback", startNanos);
                return fallback.next(message, tools, observations, hints);
            }
            recordMetrics("decision", "success", startNanos);
            return decision;
        } catch (Exception ignored) {
            recordMetrics("decision", "error", startNanos);
            return fallback.next(message, tools, observations, hints);
        }
    }

    @Override
    public String complete(String systemPrompt, List<String> userMessages) {
        long startNanos = System.nanoTime();
        if (!isEnabled()) {
            recordMetrics("completion", "disabled", startNanos);
            return fallback.complete(systemPrompt, userMessages);
        }
        try {
            ResponseEntity<Map> response = restTemplate.postForEntity(
                    properties.getApiUrl(),
                    new HttpEntity<>(completionBody(systemPrompt, userMessages), headers()),
                    Map.class
            );
            Object content = contentOf(response.getBody());
            if (content instanceof String text && StrUtil.isNotBlank(text)) {
                recordMetrics("completion", "success", startNanos);
                return text;
            }
            recordMetrics("completion", "fallback", startNanos);
            return fallback.complete(systemPrompt, userMessages);
        } catch (Exception ignored) {
            recordMetrics("completion", "error", startNanos);
            return fallback.complete(systemPrompt, userMessages);
        }
    }

    private void recordMetrics(String operation, String outcome, long startNanos) {
        if (meterRegistry == null) {
            return;
        }
        Duration duration = Duration.ofNanos(Math.max(0L, System.nanoTime() - startNanos));
        Timer.builder("citycopilot.llm.call.duration")
                .description("LLM provider call duration")
                .tag("operation", operation)
                .tag("outcome", outcome)
                .tag("model", properties.getModel() == null || properties.getModel().isBlank() ? "unknown" : properties.getModel())
                .register(meterRegistry)
                .record(duration);
        Counter.builder("citycopilot.llm.call.count")
                .description("LLM provider call count")
                .tag("operation", operation)
                .tag("outcome", outcome)
                .tag("model", properties.getModel() == null || properties.getModel().isBlank() ? "unknown" : properties.getModel())
                .register(meterRegistry)
                .increment();
    }

    private boolean isEnabled() {
        return Boolean.TRUE.equals(properties.getEnabled())
                && StrUtil.isNotBlank(properties.getApiKey())
                && StrUtil.isNotBlank(properties.getApiUrl())
                && StrUtil.isNotBlank(properties.getModel());
    }

    private HttpHeaders headers() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(properties.getApiKey());
        return headers;
    }

    private Map<String, Object> requestBody(
            String message,
            List<ToolMetadata> tools,
            List<AgentTraceStep> observations,
            Map<String, Object> hints
    ) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", properties.getModel());
        body.put("temperature", 0.1);
        body.put("messages", List.of(
                Map.of("role", "system", "content", systemPrompt(tools)),
                Map.of("role", "user", "content", JSONUtil.toJsonStr(Map.of(
                        "message", message == null ? "" : message,
                        "hints", hints == null ? Map.of() : hints,
                        "observations", observations == null ? List.of() : observations
                )))
        ));
        return body;
    }

    private Map<String, Object> completionBody(String systemPrompt, List<String> userMessages) {
        List<Map<String, String>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", systemPrompt == null ? "" : systemPrompt));
        if (userMessages != null) {
            for (String userMessage : userMessages) {
                messages.add(Map.of("role", "user", "content", userMessage == null ? "" : userMessage));
            }
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", properties.getModel());
        body.put("temperature", 0.1);
        body.put("messages", messages);
        return body;
    }

    private String systemPrompt(List<ToolMetadata> tools) {
        return """
                You are CityCopilot, an acting local-life service agent.
                Return exactly one JSON object, no markdown.
                Use {"type":"final","answer":"..."} for a final answer.
                Use {"type":"tool_call","toolName":"...","args":{...}} to call a tool.
                Available tools:
                """ + JSONUtil.toJsonStr(toolSummaries(tools));
    }

    private List<Map<String, Object>> toolSummaries(List<ToolMetadata> tools) {
        List<Map<String, Object>> summaries = new ArrayList<>();
        if (tools == null) {
            return summaries;
        }
        for (ToolMetadata tool : tools) {
            summaries.add(Map.of(
                    "name", tool.name(),
                    "description", tool.description(),
                    "parameters", tool.parameters()
            ));
        }
        return summaries;
    }

    private AgentDecision parseDecision(Map<?, ?> body) {
        Object content = contentOf(body);
        if (!(content instanceof String text) || StrUtil.isBlank(text)) {
            return null;
        }
        String json = extractJson(text);
        JSONObject object = JSONUtil.parseObj(json);
        String type = object.getStr("type", "");
        if ("final".equals(type)) {
            return AgentDecision.finalAnswer(object.getStr("answer", ""));
        }
        if ("tool_call".equals(type)) {
            JSONObject args = object.getJSONObject("args");
            Map<String, Object> argMap = args == null ? Map.of() : args;
            return AgentDecision.callTool(object.getStr("toolName", ""), argMap);
        }
        return null;
    }

    private Object contentOf(Map<?, ?> body) {
        if (body == null) {
            return null;
        }
        Object choicesValue = body.get("choices");
        if (!(choicesValue instanceof List<?> choices) || choices.isEmpty()) {
            return null;
        }
        Object firstValue = choices.get(0);
        if (!(firstValue instanceof Map<?, ?> first)) {
            return null;
        }
        Object messageValue = first.get("message");
        if (!(messageValue instanceof Map<?, ?> message)) {
            return null;
        }
        return message.get("content");
    }

    private String extractJson(String text) {
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return text.substring(start, end + 1);
        }
        return text;
    }
}
