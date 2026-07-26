package com.citycopilot.agent.gateway;

import com.citycopilot.agent.tool.AgentToolRegistry;
import com.citycopilot.contract.tool.AgentTool;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolGatewayTest {

    @Test
    void rejectsMissingRequiredArgumentsAndWritesAudit() {
        List<ToolCallAudit> audits = new ArrayList<>();
        ToolGateway gateway = new ToolGateway(
                AgentToolRegistry.fromBeans(List.of(new DemoTools())),
                audits::add
        );

        ToolResult result = gateway.invoke(
                "get_shop_detail",
                Map.of(),
                new ToolCallContext("user-1", Set.of())
        );

        assertFalse(result.ok());
        assertEquals("invalid_args", result.error().code());
        assertEquals(1, audits.size());
        assertEquals("get_shop_detail", audits.get(0).toolName());
        assertFalse(audits.get(0).success());
    }

    @Test
    void coercesStringNumbersAndAllowsPermittedWriteTool() {
        ToolGateway gateway = new ToolGateway(
                AgentToolRegistry.fromBeans(List.of(new DemoTools())),
                ToolAuditSink.noop()
        );

        ToolResult result = gateway.invoke(
                "place_seckill_order",
                Map.of("voucherId", "9"),
                new ToolCallContext("user-1", Set.of("order:write"))
        );

        assertTrue(result.ok());
        assertEquals("order-9", result.data());
    }

    @Test
    void auditsArgumentsContainingNullValuesWithoutOverwritingSuccess() {
        List<ToolCallAudit> audits = new ArrayList<>();
        ToolGateway gateway = new ToolGateway(
                AgentToolRegistry.fromBeans(List.of(new DemoTools())),
                audits::add
        );

        ToolResult result = gateway.invoke(
                "search_geo_poi",
                new java.util.LinkedHashMap<>(Map.of("keywords", "hotpot", "city", "Shanghai")) {{
                    put("adcode", null);
                }},
                new ToolCallContext("user-1", Set.of())
        );

        assertTrue(result.ok());
        assertEquals("hotpot@Shanghai", result.data());
        assertEquals(1, audits.size());
        assertTrue(audits.get(0).args().containsKey("adcode"));
    }

    @Test
    void recordsToolCallMetrics() {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        ToolGateway gateway = new ToolGateway(
                AgentToolRegistry.fromBeans(List.of(new DemoTools())),
                ToolAuditSink.noop(),
                ToolExecutionGuard.direct(),
                meterRegistry
        );

        ToolResult result = gateway.invoke(
                "get_shop_detail",
                Map.of("shopId", 1L),
                new ToolCallContext("user-1", Set.of())
        );

        assertTrue(result.ok());
        assertEquals(1.0, meterRegistry.get("citycopilot.tool.call.count")
                .tag("tool", "get_shop_detail")
                .tag("outcome", "success")
                .counter()
                .count());
        assertEquals(1, meterRegistry.get("citycopilot.tool.call.duration")
                .tag("tool", "get_shop_detail")
                .tag("outcome", "success")
                .timer()
                .count());
    }

    static final class DemoTools {
        @AgentTool(name = "get_shop_detail", description = "Get shop detail")
        public String detail(Long shopId) {
            return "shop-" + shopId;
        }

        @AgentTool(
                name = "place_seckill_order",
                description = "Place order",
                requiredPermissions = {"order:write"},
                idempotent = false
        )
        public String order(Long voucherId) {
            return "order-" + voucherId;
        }

        @AgentTool(name = "search_geo_poi", description = "Search POI")
        public String poi(String keywords, String city, String adcode) {
            return keywords + "@" + city;
        }
    }
}
