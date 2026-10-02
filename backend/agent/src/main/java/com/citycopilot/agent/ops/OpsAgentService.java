package com.citycopilot.agent.ops;

import com.citycopilot.agent.gateway.ToolCallContext;
import com.citycopilot.agent.budget.AgentBudget;
import com.citycopilot.agent.budget.AgentExecutionRuntime;
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

/**
 * 旧版运维任务的入队、消费和工具执行服务。
 *
 * <p>任务通过 Redis Stream 解耦 HTTP 与后台处理，结果保存在有界 Redis List；地理校准仍只生成计划，其余只读任务可经 ToolGateway 调用 Core。新功能应优先使用 ops case v2。</p>
 */
@Service
public class OpsAgentService {
    /** 旧版运维任务 Stream。 */
    public static final String STREAM_KEY = "agent.ops.tasks";
    /** 最近任务结果 List。 */
    public static final String RESULT_KEY = "agent.ops.results";
    /** Redis Stream 消费组名。 */
    private static final String GROUP = "citycopilot-ops";
    /** 当前旧版 worker 的消费者名。 */
    private static final String CONSUMER = "worker";
    /** 工具返回值写入结果时的最大预览字符数。 */
    private static final int DATA_PREVIEW_LIMIT = 500;
    private static final int DEFAULT_RESULT_LIMIT = 20;
    private static final int MAX_RESULT_LIMIT = 100;

    /** 负责队列、消费组和结果列表的 Redis 客户端。 */
    private final StringRedisTemplate redisTemplate;
    /** 可选工具网关；缺失时仅输出执行计划。 */
    private final ToolGatewayClient toolGateway;
    private AgentExecutionRuntime runtime = AgentExecutionRuntime.directUse();

    @Autowired
    void configureRuntime(AgentExecutionRuntime runtime) { this.runtime = runtime; }

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

    /**
     * 将运维任务追加到 Redis Stream。
     *
     * @param type 受支持任务类型
     * @param payload 可选任务参数
     * @return 是否入队及 Stream recordId；Redis 异常以结构化失败返回
     */
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

    /**
     * 从消费组读取并处理一条新任务。
     *
     * <p>只有处理结果标记为成功才 ACK；未知或失败任务留在 PEL，供人工排查或后续恢复。</p>
     */
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
            // 处理成功后才确认消息，避免业务失败被永久从待处理列表移除。
            if (Boolean.TRUE.equals(result.get("processed"))) {
                streamOps.acknowledge(STREAM_KEY, GROUP, record.getId());
                recordResult(record.getId().getValue(), result);
            }
            return result;
        } catch (Exception error) {
            return Map.of("processed", false, "error", error.getMessage());
        }
    }

    /**
     * 查询最近处理结果，并把请求条数限制在固定上限。
     */
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

    /** 首次消费时创建组；已存在的 BUSYGROUP 属于正常并发启动。 */
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

    /**
     * 按白名单分派任务类型，未知类型不执行任何工具。
     */
    public Map<String, Object> process(OpsTaskRequest task) {
        if (AgentBudget.current() == null) return runtime.call(() -> process(task));
        AgentBudget.current().context(task);
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

    /**
     * 尽力保存有界结果预览；保存失败不撤销已经完成的工具业务操作。
     */
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

    /** 有网关时执行预定义只读工具，无网关时明确标为仅计划模式。 */
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

    /** 使用固定最小运维权限与确定性幂等键执行一个预定义工具。 */
    private Map<String, Object> executeTool(OpsToolCall call) {
        ToolCallContext context = opsContext().withIdempotencyKey(
                "ops:" + call.toolName() + ":" + Integer.toHexString(call.args().hashCode())
        );
        // 仍经统一网关执行，权限、限流、审计和错误标准化不会被后台任务绕过。
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

    /** @return 旧版只读诊断与地理维护所需的固定服务身份 */
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

    /** 旧版任务内部预定义的工具名与参数。 */
    private record OpsToolCall(String toolName, Map<String, Object> args) {
    }
}
