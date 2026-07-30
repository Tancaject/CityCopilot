package com.citycopilot.core.service.impl;

import com.citycopilot.core.commerce.CommercePortClient;
import com.citycopilot.core.dto.Result;
import com.citycopilot.core.service.IVoucherOrderService;
import org.springframework.stereotype.Service;

/**
 * Compatibility facade for the public Core order API.
 *
 * <p>Commerce Service is the sole order writer. This facade deliberately has no
 * local mapper, Redis worker, or fallback switch so a Commerce outage fails
 * closed instead of reactivating the retired Core writer.</p>
 */
@Service
public class VoucherOrderServiceImpl implements IVoucherOrderService {
    private final CommercePortClient commercePortClient;

    public VoucherOrderServiceImpl(CommercePortClient commercePortClient) {
        this.commercePortClient = commercePortClient;
    }

    @Override
    public Result seckillVoucher(Long voucherId) {
        return seckillVoucher(voucherId, null);
    }

    @Override
    public Result seckillVoucher(Long voucherId, String idempotencyKey) {
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
        return commercePortClient.transition(orderId, "cancel", null, false);
    }

    @Override
    public Result payOrder(Long orderId, Integer payType) {
        return commercePortClient.transition(orderId, "pay", payType, false);
    }

    @Override
    public Result useOrder(Long orderId) {
        return commercePortClient.transition(orderId, "use", null, true);
    }

    @Override
    public Result refundOrder(Long orderId) {
        return commercePortClient.transition(orderId, "refund", null, false);
    }
}
