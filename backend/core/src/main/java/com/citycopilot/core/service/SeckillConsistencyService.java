package com.citycopilot.core.service;

import com.citycopilot.contract.commerce.CommerceContracts.ConsistencyRepairResult;
import com.citycopilot.contract.tool.AgentTool;
import com.citycopilot.contract.tool.ToolApprovalPolicy;
import com.citycopilot.contract.tool.ToolAudience;
import com.citycopilot.contract.tool.ToolRiskLevel;
import com.citycopilot.contract.tool.ToolSideEffect;
import com.citycopilot.core.commerce.CommercePortClient;
import com.citycopilot.core.security.PermissionCodes;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 秒杀一致性运维工具门面，把证据采集、修复预览、执行和复核转交 Commerce 服务。
 *
 * <p>Core 只负责工具权限和参数边界，不直接修改 Commerce 的 Redis 或 MySQL 数据。</p>
 */
@Service
public class SeckillConsistencyService {

    /** Commerce 运维接口客户端。 */
    private final CommercePortClient commercePortClient;

    /** @param commercePortClient Commerce 服务客户端 */
    public SeckillConsistencyService(CommercePortClient commercePortClient) {
        this.commercePortClient = commercePortClient;
    }

    /**
     * 采集单张秒杀券在 MySQL、Redis、Reservation、订单和 Outbox 中的有界证据。
     *
     * @param voucherId 秒杀券主键
     * @return 一致性快照及证据哈希
     */
    @AgentTool(
            name = "get_seckill_consistency_snapshot",
            description = "Collect bounded MySQL, Redis, Reservation, order, and Outbox evidence for one voucher",
            requiredPermissions = {PermissionCodes.ADMIN_ACCESS},
            audience = ToolAudience.OPS,
            scope = "ops:seckill-consistency",
            rateLimitPerMinute = 30
    )
    public Map<String, Object> snapshot(Long voucherId) {
        requireVoucherId(voucherId);
        return commercePortClient.consistency(voucherId);
    }

    /**
     * 基于最新快照生成只读修复预览。
     *
     * @param voucherId 秒杀券主键
     * @return 当前库存、目标库存、阻断原因和短期有效的证据哈希
     */
    @AgentTool(
            name = "preview_seckill_stock_repair",
            description = "Preview a bounded Redis stock repair from fresh consistency evidence without writing",
            requiredPermissions = {PermissionCodes.ADMIN_ACCESS},
            audience = ToolAudience.OPS,
            scope = "ops:seckill-consistency",
            rateLimitPerMinute = 20
    )
    public Map<String, Object> previewRepair(Long voucherId) {
        Map<String, Object> snapshot = snapshot(voucherId);
        Map<String, Object> preview = new LinkedHashMap<>();
        preview.put("voucherId", voucherId);
        preview.put("currentRedisStock", snapshot.get("redisStock"));
        preview.put("targetRedisStock", snapshot.get("expectedRedisStock"));
        preview.put("consistent", snapshot.get("consistent"));
        Object conflicts = snapshot.get("conflicts");
        boolean hasConflicts = conflicts instanceof List<?> values && !values.isEmpty();
        Object inFlightValue = snapshot.get("voucherInFlight");
        long inFlight = inFlightValue instanceof Number number ? number.longValue() : 0L;
        preview.put("voucherInFlight", inFlight);
        preview.put("blocked", hasConflicts || inFlight != 0L);
        preview.put("evidenceHash", snapshot.get("evidenceHash"));
        preview.put("evidenceExpiresAt", Instant.now().plusSeconds(120).toString());
        return preview;
    }

    /**
     * 在证据哈希仍匹配且没有在途冲突时修复 Redis 库存。
     *
     * @param voucherId 秒杀券主键
     * @param expectedEvidenceHash 操作者预览时获得的证据哈希，防止基于陈旧证据写入
     * @return Commerce 的修复结果
     */
    @AgentTool(
            name = "repair_seckill_stock",
            description = "Repair one Redis stock key only when fresh evidence hash still matches and no Reservation conflict exists",
            requiredPermissions = {PermissionCodes.ADMIN_ACCESS, PermissionCodes.ORDER_WRITE},
            audience = ToolAudience.OPS,
            sideEffect = ToolSideEffect.IDEMPOTENT_WRITE,
            risk = ToolRiskLevel.HIGH,
            approval = ToolApprovalPolicy.OPS_APPROVAL,
            scope = "ops:seckill-consistency",
            rateLimitPerMinute = 10
    )
    public ConsistencyRepairResult repairStock(Long voucherId, String expectedEvidenceHash) {
        requireVoucherId(voucherId);
        return commercePortClient.repairConsistency(voucherId, expectedEvidenceHash);
    }

    /**
     * 重新独立采集证据并验证修复后置条件。
     *
     * @param voucherId 秒杀券主键
     * @return 最新一致性结论
     */
    @AgentTool(
            name = "verify_seckill_consistency",
            description = "Independently recollect consistency evidence and evaluate the repair postcondition",
            requiredPermissions = {PermissionCodes.ADMIN_ACCESS},
            audience = ToolAudience.OPS,
            scope = "ops:seckill-consistency",
            rateLimitPerMinute = 30
    )
    public Map<String, Object> verify(Long voucherId) {
        requireVoucherId(voucherId);
        return commercePortClient.verifyConsistency(voucherId);
    }

    /**
     * 拒绝空值和非正券主键，避免向远端发出无意义运维请求。
     *
     * @param voucherId 待校验券主键
     */
    private void requireVoucherId(Long voucherId) {
        if (voucherId == null || voucherId <= 0) {
            throw new IllegalArgumentException("voucherId must be positive");
        }
    }
}
