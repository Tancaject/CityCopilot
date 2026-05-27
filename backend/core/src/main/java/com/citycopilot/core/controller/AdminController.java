package com.citycopilot.core.controller;

import com.citycopilot.core.agent.AgentTool;
import com.citycopilot.core.dto.Result;
import com.citycopilot.core.security.PermissionCodes;
import com.citycopilot.core.security.RequirePermission;
import com.citycopilot.core.service.IBlogService;
import com.citycopilot.core.service.IShopService;
import com.citycopilot.core.service.IUserService;
import com.citycopilot.core.service.IVoucherService;
import com.citycopilot.core.service.IVoucherOrderService;
import com.citycopilot.core.service.OperationLogService;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.LocalDateTime;
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
    private IVoucherService voucherService;
    @Resource
    private IVoucherOrderService voucherOrderService;

    @GetMapping("/ops/summary")
    @RequirePermission(PermissionCodes.ADMIN_ACCESS)
    @AgentTool(
            name = "ops_summary",
            description = "Get local-life operations summary",
            requiredPermissions = {PermissionCodes.ADMIN_ACCESS}
    )
    public Result opsSummary() {
        LocalDateTime today = LocalDate.now().atStartOfDay();
        Long totalShops = shopService.query().count();
        Long missingGeo = shopService.query().and(wrapper -> wrapper.isNull("x").or().isNull("y")).count();
        Long pendingGeo = shopService.query().eq("geo_status", "pending").count();
        Long verifiedGeo = shopService.query().eq("geo_status", "verified").count();
        Long totalOrders = voucherOrderService.query().count();
        Long todayOrders = voucherOrderService.query().ge("create_time", today).count();
        Long unpaidOrders = voucherOrderService.query().eq("status", 1).count();
        Long cancelledOrders = voucherOrderService.query().eq("status", 4).count();
        Long totalVouchers = voucherService.query().count();
        Long seckillVouchers = voucherService.query().eq("type", 1).count();
        Long activeVouchers = voucherService.query().eq("status", 1).count();
        Long todayBlogs = blogService.query().ge("create_time", today).count();
        Long failedAuditsToday = operationLogService.query().eq("success", false).ge("create_time", today).count();
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("users", userService.query().count());
        summary.put("shops", totalShops);
        summary.put("blogs", blogService.query().count());
        summary.put("todayBlogs", todayBlogs);
        summary.put("orders", totalOrders);
        summary.put("todayOrders", todayOrders);
        summary.put("unpaidOrders", unpaidOrders);
        summary.put("cancelledOrders", cancelledOrders);
        summary.put("vouchers", totalVouchers);
        summary.put("seckillVouchers", seckillVouchers);
        summary.put("activeVouchers", activeVouchers);
        summary.put("missingGeo", missingGeo);
        summary.put("pendingGeo", pendingGeo);
        summary.put("verifiedGeo", verifiedGeo);
        summary.put("failedAuditsToday", failedAuditsToday);
        return Result.ok(summary);
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
