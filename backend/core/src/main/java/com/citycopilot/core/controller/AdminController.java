package com.citycopilot.core.controller;

import com.citycopilot.contract.tool.AgentTool;
import com.citycopilot.contract.tool.ToolAudience;
import com.citycopilot.core.commerce.CommercePortClient;
import com.citycopilot.core.dto.Result;
import com.citycopilot.core.security.PermissionCodes;
import com.citycopilot.core.security.RequirePermission;
import com.citycopilot.core.service.IBlogService;
import com.citycopilot.core.service.IShopService;
import com.citycopilot.core.service.IUserService;
import com.citycopilot.core.service.OperationLogService;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/admin")
@RequirePermission(PermissionCodes.AUDIT_READ)
public class AdminController {

    @Resource
    private OperationLogService operationLogService;
    @Resource
    private IShopService shopService;
    @Resource
    private IUserService userService;
    @Resource
    private IBlogService blogService;
    @Resource
    private CommercePortClient commercePortClient;

    @GetMapping("/ops/summary")
    @RequirePermission(PermissionCodes.ADMIN_ACCESS)
    @AgentTool(
            name = "ops_summary",
            description = "Get local-life operations summary",
            requiredPermissions = {PermissionCodes.ADMIN_ACCESS},
            audience = ToolAudience.OPS,
            scope = "ops:summary"
    )
    public Result opsSummary() {
        ZoneId businessZone = ZoneId.systemDefault();
        LocalDate businessDate = LocalDate.now(businessZone);
        LocalDateTime today = businessDate.atStartOfDay();
        Result commerceResult = commercePortClient.operationsSummary(
                businessDate.atStartOfDay(businessZone).toInstant(),
                businessDate.plusDays(1).atStartOfDay(businessZone).toInstant()
        );
        if (!Boolean.TRUE.equals(commerceResult.getSuccess())) {
            return commerceResult;
        }
        if (!(commerceResult.getData() instanceof Map<?, ?> commerce)) {
            return Result.fail(
                    "COMMERCE_INVALID_RESPONSE",
                    "Commerce operations summary is invalid"
            );
        }
        Long totalShops = shopService.query().count();
        Long missingGeo = shopService.query().and(wrapper -> wrapper.isNull("x").or().isNull("y")).count();
        Long pendingGeo = shopService.query().eq("geo_status", "pending").count();
        Long verifiedGeo = shopService.query().eq("geo_status", "verified").count();
        Long todayBlogs = blogService.query().ge("create_time", today).count();
        Long failedAuditsToday = operationLogService.query().eq("success", false).ge("create_time", today).count();
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("users", userService.query().count());
        summary.put("shops", totalShops);
        summary.put("blogs", blogService.query().count());
        summary.put("todayBlogs", todayBlogs);
        summary.put("orders", metric(commerce, "orders"));
        summary.put("todayOrders", metric(commerce, "todayOrders"));
        summary.put("unpaidOrders", metric(commerce, "unpaidOrders"));
        summary.put("cancelledOrders", metric(commerce, "cancelledOrders"));
        summary.put("vouchers", metric(commerce, "vouchers"));
        summary.put("seckillVouchers", metric(commerce, "seckillVouchers"));
        summary.put("activeVouchers", metric(commerce, "activeVouchers"));
        summary.put("missingGeo", missingGeo);
        summary.put("pendingGeo", pendingGeo);
        summary.put("verifiedGeo", verifiedGeo);
        summary.put("failedAuditsToday", failedAuditsToday);
        return Result.ok(summary);
    }

    private long metric(Map<?, ?> summary, String name) {
        Object value = summary.get(name);
        if (value instanceof Number number) {
            return number.longValue();
        }
        throw new IllegalStateException("Commerce operations summary is missing metric: " + name);
    }

    @GetMapping("/audit-logs")
    public Result queryAuditLogs(
            @RequestParam(value = "current", defaultValue = "1") Integer current,
            @RequestParam(value = "module", required = false) String module,
            @RequestParam(value = "action", required = false) String action,
            @RequestParam(value = "targetType", required = false) String targetType,
            @RequestParam(value = "targetId", required = false) Long targetId
    ) {
        return Result.ok(operationLogService.queryLogs(current, module, action, targetType, targetId));
    }

    @GetMapping("/audit-logs/export")
    public Result exportAuditLogs(
            @RequestParam(value = "module", required = false) String module,
            @RequestParam(value = "action", required = false) String action,
            @RequestParam(value = "targetType", required = false) String targetType,
            @RequestParam(value = "targetId", required = false) Long targetId
    ) {
        return Result.ok(operationLogService.exportLogs(module, action, targetType, targetId));
    }

    @GetMapping("/audit-logs/status")
    public Result auditStatus() {
        return Result.ok(operationLogService.retentionStatus());
    }

    @PostMapping("/audit-logs/cleanup")
    @RequirePermission(PermissionCodes.AUDIT_WRITE)
    public Result cleanupAuditLogs() {
        return Result.ok(operationLogService.cleanupExpiredLogs());
    }
}
