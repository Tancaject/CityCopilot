package com.citycopilot.core.service;

import com.citycopilot.contract.tool.AgentTool;
import com.citycopilot.contract.tool.ToolApprovalPolicy;
import com.citycopilot.contract.tool.ToolRiskLevel;
import com.citycopilot.contract.tool.ToolSideEffect;
import com.citycopilot.core.dto.Result;

/**
 * <p>
 *  服务类
 * </p>
 *
 */
public interface IVoucherOrderService {

    @AgentTool(
            name = "place_seckill_order",
            description = "Place a seckill voucher order for the current user",
            requiredPermissions = {"order:write"},
            rateLimitPerMinute = 20,
            idempotent = false,
            sideEffect = ToolSideEffect.NON_IDEMPOTENT_WRITE,
            risk = ToolRiskLevel.HIGH,
            approval = ToolApprovalPolicy.USER_CONFIRMATION,
            scope = "commerce:order"
    )
    Result seckillVoucher(Long voucherId);

    Result seckillVoucher(Long voucherId, String idempotencyKey);

    @AgentTool(name = "get_my_orders", description = "List current user's voucher orders")
    Result queryMyOrders(Integer current);

    Result queryOrder(Long orderId);

    Result queryOrderForAdmin(Long orderId);

    @AgentTool(
            name = "cancel_order",
            description = "Cancel current user's voucher order",
            requiredPermissions = {"order:write"},
            idempotent = false,
            sideEffect = ToolSideEffect.NON_IDEMPOTENT_WRITE,
            risk = ToolRiskLevel.HIGH,
            approval = ToolApprovalPolicy.USER_CONFIRMATION,
            scope = "commerce:order"
    )
    Result cancelOrder(Long orderId);

    @AgentTool(
            name = "pay_order",
            description = "Pay a voucher order",
            requiredPermissions = {"order:write"},
            idempotent = false,
            sideEffect = ToolSideEffect.NON_IDEMPOTENT_WRITE,
            risk = ToolRiskLevel.HIGH,
            approval = ToolApprovalPolicy.USER_CONFIRMATION,
            scope = "commerce:payment"
    )
    Result payOrder(Long orderId, Integer payType);

    Result useOrder(Long orderId);

    @AgentTool(
            name = "refund_order",
            description = "Refund a paid voucher order",
            requiredPermissions = {"order:write"},
            idempotent = false,
            sideEffect = ToolSideEffect.NON_IDEMPOTENT_WRITE,
            risk = ToolRiskLevel.HIGH,
            approval = ToolApprovalPolicy.USER_CONFIRMATION,
            scope = "commerce:refund"
    )
    Result refundOrder(Long orderId);
}
