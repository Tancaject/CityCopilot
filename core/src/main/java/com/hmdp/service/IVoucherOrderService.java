package com.hmdp.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.citycopilot.core.agent.AgentTool;
import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;

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

    Result cancelOrder(Long orderId);

    Result payOrder(Long orderId, Integer payType);

    Result useOrder(Long orderId);

    Result refundOrder(Long orderId);

    void createVoucherOrder(VoucherOrder voucherOrder);
}
