package com.citycopilot.core.controller;

import com.citycopilot.contract.commerce.CommerceContracts.OperationsSummary;
import com.citycopilot.core.commerce.CommercePortClient;
import com.citycopilot.core.dto.Result;
import com.citycopilot.contract.security.PermissionCodes;
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
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 后台运营概览与审计日志控制器。
 *
 * <p>类级权限允许审计只读访问；运营汇总另需后台访问权限，清理日志另需审计写权限。
 * 本地用户、店铺和内容指标在 Core 统计，订单指标必须从 Commerce 单写者获取。</p>
 */
@RestController
@RequestMapping("/admin")
@RequirePermission(PermissionCodes.AUDIT_READ)
public class AdminController {

    /** 操作日志查询、导出、保留状态和清理服务。 */
    @Resource
    private OperationLogService operationLogService;
    /** 本地店铺及 GEO 状态统计服务。 */
    @Resource
    private IShopService shopService;
    /** 本地用户数量统计服务。 */
    @Resource
    private IUserService userService;
    /** 本地笔记数量统计服务。 */
    @Resource
    private IBlogService blogService;
    /** Commerce 运维端口，提供订单和优惠券的权威统计。 */
    @Resource
    private CommercePortClient commercePortClient;

    /** 运营汇总缓存时长：每次计算要全表扫描 Commerce 订单并做多次 COUNT，看板接受这一量级的延迟。 */
    private static final long SUMMARY_TTL_NANOS = TimeUnit.SECONDS.toNanos(30);
    /** 最近一次成功的汇总；失败结果不缓存，下一次请求重新计算。 */
    private volatile CachedSummary cachedSummary;
    /** 缓存失效时只让一个请求重算，其余请求等待它的结果，避免并发重复扫描。 */
    private final Object summaryRefresh = new Object();

    /**
     * 汇总当天运营指标。
     *
     * <p>“当天”以 Core 系统业务时区为准。Commerce 调用失败或响应结构非法时不拼接部分数据，
     * 避免后台把缺失订单指标误当成零。成功结果缓存 30 秒，指标最多滞后这么久。</p>
     *
     * @return Core 本地指标与 Commerce 权威交易指标组成的概览
     */
    @GetMapping("/ops/summary")
    @RequirePermission(PermissionCodes.ADMIN_ACCESS)
    public Result opsSummary() {
        ZoneId businessZone = ZoneId.systemDefault();
        LocalDate businessDate = LocalDate.now(businessZone);
        CachedSummary cached = cachedSummary;
        if (cached != null && cached.freshFor(businessDate)) return Result.ok(cached.summary());
        synchronized (summaryRefresh) {
            cached = cachedSummary;
            if (cached != null && cached.freshFor(businessDate)) return Result.ok(cached.summary());
            Result result = computeOpsSummary(businessZone, businessDate);
            if (Boolean.TRUE.equals(result.getSuccess())) {
                cachedSummary = new CachedSummary(businessDate, System.nanoTime(), result.getData());
            }
            return result;
        }
    }

    private Result computeOpsSummary(ZoneId businessZone, LocalDate businessDate) {
        LocalDateTime today = businessDate.atStartOfDay();
        // 使用同一业务日的半开区间 [当天零点, 次日零点)，避免跨时区统计漂移。
        Result commerceResult = commercePortClient.operationsSummary(
                businessDate.atStartOfDay(businessZone).toInstant(),
                businessDate.plusDays(1).atStartOfDay(businessZone).toInstant()
        );
        if (!Boolean.TRUE.equals(commerceResult.getSuccess())) {
            return commerceResult;
        }
        if (!(commerceResult.getData() instanceof OperationsSummary commerce)) {
            return Result.fail(
                    "COMMERCE_INVALID_RESPONSE",
                    "Commerce operations summary is invalid"
            );
        }
        Long totalShops = shopService.countShops();
        Long missingGeo = shopService.countShopsMissingGeo();
        Long pendingGeo = shopService.countShopsByGeoStatus("pending");
        Long verifiedGeo = shopService.countShopsByGeoStatus("verified");
        Long todayBlogs = blogService.countBlogsCreatedSince(today);
        Long failedAuditsToday = operationLogService.query().eq("success", false).ge("create_time", today).count();
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("users", userService.countUsers());
        summary.put("shops", totalShops);
        summary.put("blogs", blogService.countBlogs());
        summary.put("todayBlogs", todayBlogs);
        summary.put("orders", metric(commerce.orders(), "orders"));
        summary.put("todayOrders", metric(commerce.todayOrders(), "todayOrders"));
        summary.put("unpaidOrders", metric(commerce.unpaidOrders(), "unpaidOrders"));
        summary.put("cancelledOrders", metric(commerce.cancelledOrders(), "cancelledOrders"));
        summary.put("vouchers", metric(commerce.vouchers(), "vouchers"));
        summary.put("seckillVouchers", metric(commerce.seckillVouchers(), "seckillVouchers"));
        summary.put("activeVouchers", metric(commerce.activeVouchers(), "activeVouchers"));
        summary.put("missingGeo", missingGeo);
        summary.put("pendingGeo", pendingGeo);
        summary.put("verifiedGeo", verifiedGeo);
        summary.put("failedAuditsToday", failedAuditsToday);
        return Result.ok(Collections.unmodifiableMap(summary));
    }

    /** 跨过业务日零点的缓存不再使用，避免“今日”指标沿用前一天的统计区间。 */
    private record CachedSummary(LocalDate businessDate, long computedAtNanos, Object summary) {
        boolean freshFor(LocalDate date) {
            return businessDate.equals(date) && System.nanoTime() - computedAtNanos < SUMMARY_TTL_NANOS;
        }
    }

    /**
     * 从 Commerce 响应中读取必需的数值指标。
     *
     * @param value Commerce 指标值
     * @param name 指标字段名
     * @return 指标的长整型值
     * @throws IllegalStateException 字段缺失或不是数值时抛出，防止静默展示错误报表
     */
    private long metric(Long value, String name) {
        if (value != null) {
            return value;
        }
        throw new IllegalStateException("Commerce operations summary is missing metric: " + name);
    }

    /**
     * 分页筛选审计日志。
     *
     * @param current 页码，默认第 1 页
     * @param module 可选业务模块
     * @param action 可选操作编码
     * @param targetType 可选目标类型
     * @param targetId 可选目标主键
     * @return 符合条件的审计日志分页结果
     */
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

    /**
     * 导出符合筛选条件的审计日志数据。
     *
     * @param module 可选业务模块
     * @param action 可选操作编码
     * @param targetType 可选目标类型
     * @param targetId 可选目标主键
     * @return 审计服务生成的导出内容
     */
    @GetMapping("/audit-logs/export")
    public Result exportAuditLogs(
            @RequestParam(value = "module", required = false) String module,
            @RequestParam(value = "action", required = false) String action,
            @RequestParam(value = "targetType", required = false) String targetType,
            @RequestParam(value = "targetId", required = false) Long targetId
    ) {
        return Result.ok(operationLogService.exportLogs(module, action, targetType, targetId));
    }

    /**
     * 查询审计日志保留策略及当前状态。
     *
     * @return 保留天数、边界时间等状态信息
     */
    @GetMapping("/audit-logs/status")
    public Result auditStatus() {
        return Result.ok(operationLogService.retentionStatus());
    }

    /**
     * 按保留策略删除过期审计日志，需要审计写权限。
     *
     * @return 本次清理结果和受影响数量
     */
    @PostMapping("/audit-logs/cleanup")
    @RequirePermission(PermissionCodes.AUDIT_WRITE)
    public Result cleanupAuditLogs() {
        return Result.ok(operationLogService.cleanupExpiredLogs());
    }
}
