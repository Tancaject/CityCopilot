package com.citycopilot.core.service;

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
 * Core-side tool facade for consistency operations owned by Commerce Service.
 */
@Service
public class SeckillConsistencyService {
    private final CommercePortClient commercePortClient;

    public SeckillConsistencyService(CommercePortClient commercePortClient) {
        this.commercePortClient = commercePortClient;
    }

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
    public Map<String, Object> repairStock(Long voucherId, String expectedEvidenceHash) {
        requireVoucherId(voucherId);
        return commercePortClient.repairConsistency(voucherId, expectedEvidenceHash);
    }

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

    private void requireVoucherId(Long voucherId) {
        if (voucherId == null || voucherId <= 0) {
            throw new IllegalArgumentException("voucherId must be positive");
        }
    }
}
