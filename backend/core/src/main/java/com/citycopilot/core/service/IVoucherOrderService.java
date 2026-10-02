package com.citycopilot.core.service;

import com.citycopilot.contract.tool.AgentTool;
import com.citycopilot.contract.tool.ToolApprovalPolicy;
import com.citycopilot.contract.tool.ToolRiskLevel;
import com.citycopilot.contract.tool.ToolSideEffect;
import com.citycopilot.core.dto.Result;

/**
 * 优惠券订单兼容服务接口。
 *
 * <p>Core 不直接写订单；实现层通过 CommercePort 将秒杀、查询和状态迁移交给 Commerce 服务。</p>
 */
public interface IVoucherOrderService {

    /**
     * 不显式传入 Commerce 幂等键的兼容秒杀入口。
     *
     * <p>Agent/MCP 调用仍由工具执行层提供幂等保护；需要确认远端准入结果时应使用带键重载。</p>
     *
     * @param voucherId 秒杀券主键
     * @return Commerce 返回的准入或失败结果
     */
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

    /**
     * 使用调用方提供的幂等键提交秒杀请求。
     *
     * @param voucherId 秒杀券主键
     * @param idempotencyKey 同一次业务重试必须复用的幂等键
     * @return Commerce 返回的准入或失败结果
     */
    Result seckillVoucher(Long voucherId, String idempotencyKey);

    /**
     * 分页查询当前用户的订单。
     *
     * @param current 从 1 开始的页码
     * @return 当前页订单
     */
    @AgentTool(name = "get_my_orders", description = "List current user's voucher orders")
    Result queryMyOrders(Integer current);

    /**
     * 查询当前用户可见的订单详情。
     *
     * @param orderId 订单主键
     * @return 订单详情
     */
    Result queryOrder(Long orderId);

    /**
     * 查询管理员视角的订单详情。
     *
     * @param orderId 订单主键
     * @return 订单详情
     */
    Result queryOrderForAdmin(Long orderId);

    /**
     * 取消当前用户的订单。
     *
     * @param orderId 待取消订单主键
     * @return 状态迁移结果
     */
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

    /**
     * 支付订单。
     *
     * @param orderId 订单主键
     * @param payType 支付方式编码
     * @return 状态迁移结果
     */
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

    /**
     * 核销订单。
     *
     * @param orderId 待核销订单主键
     * @return 状态迁移结果
     */
    Result useOrder(Long orderId);

    /**
     * 为已支付订单发起退款。
     *
     * @param orderId 待退款订单主键
     * @return 状态迁移结果
     */
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
