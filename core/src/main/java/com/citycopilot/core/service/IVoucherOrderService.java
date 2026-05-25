package com.citycopilot.core.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.citycopilot.core.agent.AgentTool;
import com.citycopilot.core.dto.Result;
import com.citycopilot.core.entity.VoucherOrder;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface IVoucherOrderService extends IService<VoucherOrder> {

    @AgentTool(
            name = "place_seckill_order",
            description = "Place a seckill voucher order for the current user",
            requiredPermissions = {"order:write"},
            rateLimitPerMinute = 20,
            idempotent = false
    )
    Result seckillVoucher(Long voucherId);

    @AgentTool(name = "get_my_orders", description = "List current user's voucher orders")
    Result queryMyOrders(Integer current);

    Result queryOrder(Long orderId);

    Result queryOrderForAdmin(Long orderId);

    @AgentTool(
            name = "cancel_order",
            description = "Cancel current user's voucher order",
            requiredPermissions = {"order:write"},
            idempotent = false
    )
    Result cancelOrder(Long orderId);

    @AgentTool(
            name = "pay_order",
            description = "Pay a voucher order",
            requiredPermissions = {"order:write"},
            idempotent = false
    )
    Result payOrder(Long orderId, Integer payType);

    Result useOrder(Long orderId);

    @AgentTool(
            name = "refund_order",
            description = "Refund a paid voucher order",
            requiredPermissions = {"order:write"},
            idempotent = false
    )
    Result refundOrder(Long orderId);

    void createVoucherOrder(VoucherOrder voucherOrder);
}
