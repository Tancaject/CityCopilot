package com.citycopilot.core.service;

import com.citycopilot.contract.tool.AgentTool;
import com.citycopilot.contract.tool.ToolApprovalPolicy;
import com.citycopilot.contract.tool.ToolAudience;
import com.citycopilot.contract.tool.ToolRiskLevel;
import com.citycopilot.contract.tool.ToolSideEffect;
import com.citycopilot.core.commerce.CommercePortClient;
import com.citycopilot.core.security.PermissionCodes;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * Operator-tool facade for Commerce-owned dead letters.
 *
 * <p>Reservation recovery is performed inside Commerce Service. Core does not
 * poll or replay the retired Core order stream.</p>
 */
@Service
public class SeckillRecoveryService {
    private final CommercePortClient commercePortClient;

    public SeckillRecoveryService(CommercePortClient commercePortClient) {
        this.commercePortClient = commercePortClient;
    }

    @AgentTool(
            name = "list_seckill_dead_letters",
            description = "List recent Commerce seckill dead letters for operator diagnosis",
            requiredPermissions = {PermissionCodes.ADMIN_ACCESS},
            audience = ToolAudience.OPS,
            scope = "ops:seckill",
            rateLimitPerMinute = 20
    )
    public List<Map<String, Object>> listDeadLetters(Integer limit) {
        return commercePortClient.deadLetters(limit);
    }

    @AgentTool(
            name = "replay_seckill_dead_letter",
            description = "Replay one Commerce seckill dead letter after operator approval; replay is idempotent",
            requiredPermissions = {PermissionCodes.ADMIN_ACCESS},
            audience = ToolAudience.OPS,
            sideEffect = ToolSideEffect.IDEMPOTENT_WRITE,
            risk = ToolRiskLevel.HIGH,
            approval = ToolApprovalPolicy.OPS_APPROVAL,
            scope = "ops:seckill",
            rateLimitPerMinute = 10
    )
    public Map<String, Object> replayDeadLetter(String deadLetterRecordId) {
        return commercePortClient.replayDeadLetter(deadLetterRecordId);
    }
}
