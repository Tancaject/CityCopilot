package com.citycopilot.core.service;

import com.citycopilot.contract.tool.AgentTool;
import com.citycopilot.contract.commerce.CommerceContracts.DeadLetterView;
import com.citycopilot.contract.commerce.CommerceContracts.DeadLetterReplayResult;
import com.citycopilot.contract.tool.ToolApprovalPolicy;
import com.citycopilot.contract.tool.ToolAudience;
import com.citycopilot.contract.tool.ToolRiskLevel;
import com.citycopilot.contract.tool.ToolSideEffect;
import com.citycopilot.core.commerce.CommercePortClient;
import com.citycopilot.core.security.PermissionCodes;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Commerce 秒杀死信的运维工具门面。
 *
 * <p>Reservation 恢复在 Commerce 内完成；Core 不轮询或重放已经退役的本地订单 Stream。</p>
 */
@Service
public class SeckillRecoveryService {

    /** Commerce 死信查询与重放客户端。 */
    private final CommercePortClient commercePortClient;

    /** @param commercePortClient Commerce 服务客户端 */
    public SeckillRecoveryService(CommercePortClient commercePortClient) {
        this.commercePortClient = commercePortClient;
    }

    /**
     * 查询近期秒杀死信，供运维人员诊断失败原因。
     *
     * @param limit 最大返回条数；远端会进一步限制安全上限
     * @return 死信记录列表
     */
    @AgentTool(
            name = "list_seckill_dead_letters",
            description = "List recent Commerce seckill dead letters for operator diagnosis",
            requiredPermissions = {PermissionCodes.ADMIN_ACCESS},
            audience = ToolAudience.OPS,
            scope = "ops:seckill",
            rateLimitPerMinute = 20
    )
    public List<DeadLetterView> listDeadLetters(Integer limit) {
        return commercePortClient.deadLetters(limit);
    }

    /**
     * 在运维审批后按记录主键重放一条死信。
     *
     * @param deadLetterRecordId Commerce 死信记录主键
     * @return 幂等重放结果
     */
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
    public DeadLetterReplayResult replayDeadLetter(String deadLetterRecordId) {
        return commercePortClient.replayDeadLetter(deadLetterRecordId);
    }
}
