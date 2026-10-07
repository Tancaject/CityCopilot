package com.citycopilot.core.service.impl;

import com.citycopilot.core.commerce.CommercePortClient;
import com.citycopilot.core.dto.Result;
import com.citycopilot.core.service.IVoucherOrderService;
import org.springframework.stereotype.Service;

/**
 * Core 对外订单 API 的兼容门面。
 *
 * <p>Commerce 服务是订单唯一写入方。本类不保留本地 Mapper、Redis Worker 或降级写路径，
 * 因此 Commerce 不可用时会关闭失败，不会重新启用已退役的 Core 写入链路。</p>
 */
@Service
public class VoucherOrderServiceImpl implements IVoucherOrderService {

    /** 版本化 Commerce HTTP 端口，负责身份透传、错误映射和结果适配。 */
    private final CommercePortClient commercePortClient;

    /** @param commercePortClient Commerce 服务客户端 */
    public VoucherOrderServiceImpl(CommercePortClient commercePortClient) {
        this.commercePortClient = commercePortClient;
    }

    @Override
    public Result seckillVoucher(Long voucherId, String idempotencyKey) {
        // 秒杀准入必须由 Commerce 单写者完成，Core 只透传同一业务重试的幂等键。
        return commercePortClient.seckill(voucherId, idempotencyKey);
    }

    @Override
    public Result queryMyOrders(Integer current) {
        return commercePortClient.myOrders(current);
    }

    @Override
    public Result queryOrder(Long orderId) {
        return commercePortClient.order(orderId, false);
    }

    @Override
    public Result queryOrderForAdmin(Long orderId) {
        return commercePortClient.order(orderId, true);
    }

    @Override
    public Result cancelOrder(Long orderId) {
        // transition 统一封装 Commerce 的订单状态机动作，布尔值表示普通用户视角。
        return commercePortClient.transition(orderId, "cancel", null, false);
    }

    @Override
    public Result payOrder(Long orderId, Integer payType) {
        return commercePortClient.transition(orderId, "pay", payType, false);
    }

    @Override
    public Result useOrder(Long orderId) {
        // 核销属于管理操作，使用管理员视角调用 Commerce 状态机。
        return commercePortClient.transition(orderId, "use", null, true);
    }

    @Override
    public Result refundOrder(Long orderId) {
        return commercePortClient.transition(orderId, "refund", null, false);
    }
}
