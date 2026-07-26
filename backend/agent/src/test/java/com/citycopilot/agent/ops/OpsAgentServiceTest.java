package com.citycopilot.agent.ops;

import com.citycopilot.agent.gateway.ToolGateway;
import com.citycopilot.agent.tool.AgentToolRegistry;
import com.citycopilot.contract.tool.AgentTool;
import com.citycopilot.contract.security.PermissionCodes;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OpsAgentServiceTest {

    @Test
    void buildsGeoCalibrationPlanFromPayload() {
        OpsAgentService service = new OpsAgentService(null);

        Map<String, Object> result = service.process(new OpsTaskRequest(
                "geo_calibration",
                Map.of("city", "上海", "limit", 25, "onlyMissing", true)
        ));

        assertEquals(true, result.get("processed"));
        assertEquals("geo_calibration_plan_ready", result.get("action"));
        assertEquals("上海", result.get("scopeCity"));
        assertEquals(25, result.get("limit"));
        assertEquals("start_batch_geocode", result.get("recommendedTool"));
        assertEquals("legacy_plan_only", result.get("executionMode"));
    }

    @Test
    void buildsOrderAnomalyScanSignals() {
        OpsAgentService service = new OpsAgentService(null);

        Map<String, Object> result = service.process(new OpsTaskRequest(
                "order_anomaly_scan",
                Map.of("windowMinutes", 30)
        ));

        assertEquals(true, result.get("processed"));
        assertEquals(30, result.get("windowMinutes"));
        assertTrue(result.toString().contains("paid_without_stock_deduction"));
        assertEquals("plan_only", result.get("executionMode"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void executesDailyReportToolsWhenGatewayIsAvailable() {
        ToolGateway gateway = new ToolGateway(AgentToolRegistry.fromBeans(List.of(new OpsTools())));
        OpsAgentService service = new OpsAgentService(null, gateway);

        Map<String, Object> result = service.process(new OpsTaskRequest("daily_report", Map.of("date", "2026-05-25")));

        assertEquals(true, result.get("processed"));
        assertEquals("live_tools", result.get("executionMode"));
        assertEquals("success", result.get("executionStatus"));
        List<Map<String, Object>> executions = (List<Map<String, Object>>) result.get("toolExecutions");
        assertEquals(3, executions.size());
        assertEquals("ops_summary", executions.get(0).get("tool"));
        assertEquals(true, executions.get(0).get("ok"));
    }

    @Test
    void legacyGeoCalibrationNeverExecutesWriteToolsWithoutCaseApproval() {
        ToolGateway gateway = new ToolGateway(AgentToolRegistry.fromBeans(List.of(new OpsTools())));
        OpsAgentService service = new OpsAgentService(null, gateway);

        Map<String, Object> result = service.process(new OpsTaskRequest(
                "geo_calibration",
                Map.of("city", "上海", "keyword", "火锅", "limit", 25, "onlyMissing", true)
        ));

        assertEquals("legacy_plan_only", result.get("executionMode"));
        assertEquals("ops_case_v2", result.get("migrationTarget"));
        assertEquals(false, result.containsKey("toolExecutions"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void executesOrderAnomalyScanToolsWhenGatewayIsAvailable() {
        ToolGateway gateway = new ToolGateway(AgentToolRegistry.fromBeans(List.of(new OpsTools())));
        OpsAgentService service = new OpsAgentService(null, gateway);

        Map<String, Object> result = service.process(new OpsTaskRequest(
                "order_anomaly_scan",
                Map.of("windowMinutes", 30)
        ));

        assertEquals("live_tools", result.get("executionMode"));
        assertEquals("success", result.get("executionStatus"));
        List<Map<String, Object>> executions = (List<Map<String, Object>>) result.get("toolExecutions");
        assertEquals("query_audit_logs", executions.get(0).get("tool"));
        assertEquals("ops_summary", executions.get(1).get("tool"));
    }

    @Test
    void rejectsUnknownTaskTypes() {
        OpsAgentService service = new OpsAgentService(null);

        Map<String, Object> result = service.process(new OpsTaskRequest("unknown", Map.of()));

        assertEquals(false, result.get("processed"));
        assertEquals("unknown_ops_task", result.get("error"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void returnsRecentResultsFromRedisList() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        ListOperations<String, String> listOperations = mock(ListOperations.class);
        when(redisTemplate.opsForList()).thenReturn(listOperations);
        when(listOperations.range(OpsAgentService.RESULT_KEY, 0, 2L))
                .thenReturn(List.of("{\"type\":\"daily_report\"}", "{\"type\":\"geo_calibration\"}"));
        OpsAgentService service = new OpsAgentService(redisTemplate);

        Map<String, Object> result = service.recentResults(3);

        assertEquals(true, result.get("available"));
        assertEquals(3, result.get("limit"));
        List<String> rows = (List<String>) result.get("results");
        assertEquals(2, rows.size());
        assertTrue(rows.get(0).contains("daily_report"));
    }

    @Test
    void reportsUnavailableRecentResultsWhenRedisIsMissing() {
        OpsAgentService service = new OpsAgentService(null);

        Map<String, Object> result = service.recentResults(500);

        assertEquals(false, result.get("available"));
        assertEquals(100, result.get("limit"));
        assertEquals("redis_unavailable", result.get("reason"));
    }

    static final class OpsTools {
        @AgentTool(
                name = "ops_summary",
                description = "Get ops summary",
                requiredPermissions = {PermissionCodes.ADMIN_ACCESS}
        )
        public Map<String, Object> opsSummary() {
            return Map.of("orders", 12);
        }

        @AgentTool(
                name = "get_audit_retention_status",
                description = "Get audit retention status",
                requiredPermissions = {PermissionCodes.AUDIT_READ}
        )
        public Map<String, Object> retentionStatus() {
            return Map.of("retentionDays", 30);
        }

        @AgentTool(
                name = "list_geo_batch_jobs",
                description = "List geo batch jobs",
                requiredPermissions = {PermissionCodes.GEO_READ}
        )
        public Map<String, Object> queryJobs(Integer current, String status) {
            return Map.of("current", current == null ? 0 : current);
        }

        @AgentTool(
                name = "start_batch_geocode",
                description = "Start geocode job",
                requiredPermissions = {PermissionCodes.GEO_WRITE}
        )
        public Map<String, Object> startGeocodeJob(
                String city,
                String keyword,
                Boolean verified,
                Boolean onlyMissing,
                Integer limit
        ) {
            return Map.of(
                    "city", city,
                    "keyword", keyword,
                    "verified", verified,
                    "onlyMissing", onlyMissing,
                    "limit", limit
            );
        }

        @AgentTool(
                name = "query_audit_logs",
                description = "Query audit logs",
                requiredPermissions = {PermissionCodes.AUDIT_READ}
        )
        public Map<String, Object> queryLogs(
                Integer current,
                String module,
                String action,
                String targetType,
                Long targetId
        ) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("current", current);
            result.put("module", module);
            result.put("action", action);
            result.put("targetType", targetType);
            result.put("targetId", targetId);
            return result;
        }
    }
}
