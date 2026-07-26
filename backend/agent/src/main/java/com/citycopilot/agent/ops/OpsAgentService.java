package com.citycopilot.agent.ops;

import com.citycopilot.agent.gateway.ToolCallContext;
import com.citycopilot.agent.gateway.ToolGatewayClient;
import com.citycopilot.agent.gateway.ToolResult;
import com.citycopilot.contract.security.PermissionCodes;
import cn.hutool.json.JSONUtil;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class OpsAgentService {
    public static final String STREAM_KEY = "agent.ops.tasks";
    public static final String RESULT_KEY = "agent.ops.results";
    private static final String GROUP = "citycopilot-ops";
    private static final String CONSUMER = "worker";
    private static final int DATA_PREVIEW_LIMIT = 500;
    private static final int DEFAULT_RESULT_LIMIT = 20;
    private static final int MAX_RESULT_LIMIT = 100;

    private final StringRedisTemplate redisTemplate;
    private final ToolGatewayClient toolGateway;

    @Autowired
    public OpsAgentService(StringRedisTemplate redisTemplate, ObjectProvider<ToolGatewayClient> toolGateway) {
        this(redisTemplate, toolGateway.getIfAvailable());
    }

    OpsAgentService(StringRedisTemplate redisTemplate) {
        this(redisTemplate, (ToolGatewayClient) null);
    }

    OpsAgentService(StringRedisTemplate redisTemplate, ToolGatewayClient toolGateway) {
        this.redisTemplate = redisTemplate;
        this.toolGateway = toolGateway;
    }

    public Map<String, Object> enqueue(String type, Map<String, Object> payload) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", type);
        body.put("payload", payload == null ? Map.of() : payload);
        body.put("createdAt", Instant.now().toString());
        try {
            Object recordId = redisTemplate.opsForStream().add(STREAM_KEY, body);
            return Map.of("queued", true, "recordId", String.valueOf(recordId), "type", type);
        } catch (Exception error) {
            return Map.of("queued", false, "type", type, "error", error.getMessage());
        }
    }

    public Map<String, Object> pollOnce() {
        try {
            StreamOperations<String, Object, Object> streamOps = redisTemplate.opsForStream();
            if (!Boolean.TRUE.equals(redisTemplate.hasKey(STREAM_KEY))) {
                return Map.of("processed", false, "reason", "empty");
            }
            ensureConsumerGroup(streamOps);
            List<MapRecord<String, Object, Object>> records = streamOps.read(
                    Consumer.from(GROUP, CONSUMER),
                    StreamReadOptions.empty().count(1).block(Duration.ofMillis(250)),
                    StreamOffset.create(STREAM_KEY, ReadOffset.lastConsumed())
            );
            if (records == null || records.isEmpty()) {
                return Map.of("processed", false, "reason", "empty");
            }
            MapRecord<String, Object, Object> record = records.get(0);
            Map<Object, Object> value = record.getValue();
            Map<String, Object> result = process(new OpsTaskRequest(
                    String.valueOf(value.get("type")),
                    Map.of("raw", value.get("payload"))
            ));
            if (Boolean.TRUE.equals(result.get("processed"))) {
                streamOps.acknowledge(STREAM_KEY, GROUP, record.getId());
                recordResult(record.getId().getValue(), result);
            }
            return result;
        } catch (Exception error) {
            return Map.of("processed", false, "error", error.getMessage());
        }
    }

    public Map<String, Object> recentResults(Integer limit) {
        int safeLimit = clampLimit(limit);
        if (redisTemplate == null) {
            return Map.of(
                    "available", false,
                    "key", RESULT_KEY,
                    "limit", safeLimit,
                    "results", List.of(),
                    "reason", "redis_unavailable"
            );
        }
        try {
            List<String> rows = redisTemplate.opsForList().range(RESULT_KEY, 0, safeLimit - 1L);
            return Map.of(
                    "available", true,
                    "key", RESULT_KEY,
                    "limit", safeLimit,
                    "results", rows == null ? List.of() : rows
            );
        } catch (Exception error) {
            return Map.of(
                    "available", false,
                    "key", RESULT_KEY,
                    "limit", safeLimit,
                    "results", List.of(),
                    "error", error.getMessage()
            );
        }
    }

    private void ensureConsumerGroup(StreamOperations<String, Object, Object> streamOps) {
        try {
            streamOps.createGroup(STREAM_KEY, ReadOffset.from("0-0"), GROUP);
        } catch (Exception error) {
            String message = error.getMessage();
            if (message == null || !message.contains("BUSYGROUP")) {
                throw error;
            }
        }
    }

    public Map<String, Object> process(OpsTaskRequest task) {
        String type = task.type() == null ? "" : task.type();
        return switch (type) {
            case "geo_calibration" -> geoCalibration(task.payload());
            case "order_anomaly_scan" -> orderAnomalyScan(task.payload());
            case "daily_report" -> dailyReport(task.payload());
            default -> Map.of("processed", false, "type", type, "error", "unknown_ops_task");
        };
    }

    private Map<String, Object> geoCalibration(Map<String, Object> payload) {
        Map<String, Object> body = payload(payload);
        Map<String, Object> result = baseResult("geo_calibration", "geo_calibration_plan_ready");
        result.put("recommendedTool", "start_batch_geocode");
        result.put("scopeCity", stringValue(body, "city", "杭州"));
        result.put("onlyMissing", booleanValue(body, "onlyMissing", true));
        result.put("limit", numberValue(body, "limit", 50));
        result.put("checks", List.of(
                "确认 AMap provider status",
                "筛选缺少坐标或未验证坐标的店铺",
                "按批次启动 geocode job",
                "失败项进入 retry_geo_batch_failed_items"
        ));
        result.put("executionMode", "legacy_plan_only");
        result.put("migrationTarget", "ops_case_v2");
        return result;
    }

    private Map<String, Object> orderAnomalyScan(Map<String, Object> payload) {
        Map<String, Object> body = payload(payload);
        Map<String, Object> result = baseResult("order_anomaly_scan", "order_anomaly_scan_ready");
        result.put("windowMinutes", numberValue(body, "windowMinutes", 60));
        result.put("signals", List.of(
                "created_without_payment",
                "paid_without_stock_deduction",
                "cancelled_but_user_order_set_not_released",
                "refund_without_status_transition"
        ));
        result.put("recommendedTools", List.of("query_audit_logs", "ops_summary"));
        attachExecutions(result, List.of(
                new OpsToolCall("query_audit_logs", args(
                        "arg0", 1,
                        "arg1", "order",
                        "arg2", null,
                        "arg3", null,
                        "arg4", null
                )),
                new OpsToolCall("ops_summary", Map.of())
        ));
        return result;
    }

    private Map<String, Object> dailyReport(Map<String, Object> payload) {
        Map<String, Object> body = payload(payload);
        Map<String, Object> result = baseResult("daily_report", "daily_report_ready");
        result.put("date", stringValue(body, "date", java.time.LocalDate.now().toString()));
        result.put("sections", List.of(
                "ops_summary",
                "tool_call_health",
                "geo_batch_status",
                "order_anomaly_scan",
                "audit_retention_status"
        ));
        result.put("recommendedTools", List.of("ops_summary", "get_audit_retention_status", "list_geo_batch_jobs"));
        attachExecutions(result, List.of(
                new OpsToolCall("ops_summary", Map.of()),
                new OpsToolCall("get_audit_retention_status", Map.of()),
                new OpsToolCall("list_geo_batch_jobs", args("arg0", 1, "arg1", null))
        ));
        return result;
    }

    private Map<String, Object> baseResult(String type, String action) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("processed", true);
        result.put("type", type);
        result.put("action", action);
        result.put("processedAt", Instant.now().toString());
        return result;
    }

    private void recordResult(String recordId, Map<String, Object> result) {
        if (redisTemplate == null) {
            return;
        }
        try {
            Map<String, Object> entry = new LinkedHashMap<>(result);
            entry.put("recordId", recordId);
            redisTemplate.opsForList().leftPush(RESULT_KEY, JSONUtil.toJsonStr(entry));
            redisTemplate.opsForList().trim(RESULT_KEY, 0, 199);
        } catch (Exception ignored) {
            // Ops task result persistence should not prevent stream acknowledgement.
        }
    }

    private int clampLimit(Integer limit) {
        if (limit == null) {
            return DEFAULT_RESULT_LIMIT;
        }
        return Math.max(1, Math.min(MAX_RESULT_LIMIT, limit));
    }

    private void attachExecutions(Map<String, Object> result, List<OpsToolCall> calls) {
        if (toolGateway == null) {
            result.put("executionMode", "plan_only");
            return;
        }
        List<Map<String, Object>> executions = calls.stream()
                .map(this::executeTool)
                .toList();
        result.put("executionMode", "live_tools");
        result.put("toolExecutions", executions);
        result.put("executionStatus", executionStatus(executions));
    }

    private Map<String, Object> executeTool(OpsToolCall call) {
        ToolCallContext context = opsContext().withIdempotencyKey(
                "ops:" + call.toolName() + ":" + Integer.toHexString(call.args().hashCode())
        );
        ToolResult result = toolGateway.invoke(call.toolName(), call.args(), context);
        Map<String, Object> execution = new LinkedHashMap<>();
        execution.put("tool", call.toolName());
        execution.put("args", call.args());
        execution.put("ok", result.ok());
        if (result.ok()) {
            execution.put("dataPreview", preview(result.data()));
        } else if (result.error() != null) {
            execution.put("errorCode", result.error().code());
            execution.put("errorMessage", result.error().message());
            execution.put("retryable", result.error().retryable());
        }
        return execution;
    }

    private String executionStatus(List<Map<String, Object>> executions) {
        long successCount = executions.stream()
                .filter(execution -> Boolean.TRUE.equals(execution.get("ok")))
                .count();
        if (successCount == executions.size()) {
            return "success";
        }
        return successCount == 0 ? "failed" : "partial_success";
    }

    private ToolCallContext opsContext() {
        return new ToolCallContext("ops-agent", Set.of(
                PermissionCodes.ADMIN_ACCESS,
                PermissionCodes.AUDIT_READ,
                PermissionCodes.GEO_READ,
                PermissionCodes.GEO_WRITE
        ));
    }

    private String preview(Object data) {
        if (data == null) {
            return "";
        }
        String text = String.valueOf(data);
        return text.length() <= DATA_PREVIEW_LIMIT ? text : text.substring(0, DATA_PREVIEW_LIMIT) + "...";
    }

    private Map<String, Object> args(Object... entries) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (int i = 0; i < entries.length - 1; i += 2) {
            out.put(String.valueOf(entries[i]), entries[i + 1]);
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> payload(Map<String, Object> payload) {
        if (payload == null || payload.isEmpty()) {
            return Map.of();
        }
        Object raw = payload.get("raw");
        if (raw instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                out.put(String.valueOf(entry.getKey()), entry.getValue());
            }
            return out;
        }
        return payload;
    }

    private String stringValue(Map<String, Object> payload, String key, String fallback) {
        Object value = payload.get(key);
        return value == null || String.valueOf(value).isBlank() ? fallback : String.valueOf(value);
    }

    private boolean booleanValue(Map<String, Object> payload, String key, boolean fallback) {
        Object value = payload.get(key);
        return value instanceof Boolean bool ? bool : fallback;
    }

    private int numberValue(Map<String, Object> payload, String key, int fallback) {
        Object value = payload.get(key);
        return value instanceof Number number ? number.intValue() : fallback;
    }

    private record OpsToolCall(String toolName, Map<String, Object> args) {
    }
}
