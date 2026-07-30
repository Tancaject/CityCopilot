package com.citycopilot.core.service.impl;

import com.citycopilot.core.commerce.CommercePortClient;
import com.citycopilot.core.dto.Result;
import com.citycopilot.core.dto.VoucherRequest;
import com.citycopilot.core.service.IVoucherService;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Compatibility facade for voucher APIs owned by Commerce Service.
 */
@Service
public class VoucherServiceImpl implements IVoucherService {
    private final CommercePortClient commercePortClient;

    public VoucherServiceImpl(CommercePortClient commercePortClient) {
        this.commercePortClient = commercePortClient;
    }

    @Override
    public Result queryVoucherOfShop(Long shopId) {
        return commercePortClient.vouchers(shopId, false, null, null);
    }

    @Override
    public Result queryAdminVouchers(Integer current, Long shopId, String keyword, Integer status) {
        return commercePortClient.vouchers(shopId, true, keyword, status);
    }

    @Override
    public Result createVoucher(VoucherRequest voucher) {
        if (voucher == null) {
            return Result.fail("BAD_REQUEST", "优惠券不能为空");
        }
        return commercePortClient.createVoucher(voucherPayload(voucher));
    }

    @Override
    public Result updateVoucher(VoucherRequest voucher) {
        if (voucher == null || voucher.getId() == null) {
            return Result.fail("BAD_REQUEST", "优惠券 id 不能为空");
        }
        return commercePortClient.updateVoucher(voucher.getId(), voucherPayload(voucher));
    }

    @Override
    public Result updateVoucherStatus(Long id, Integer status) {
        if (id == null) {
            return Result.fail("BAD_REQUEST", "优惠券 id 不能为空");
        }
        if (status == null) {
            return Result.fail("BAD_REQUEST", "优惠券状态不能为空");
        }
        return commercePortClient.updateVoucher(id, Map.of("status", status));
    }

    @Override
    public void addSeckillVoucher(VoucherRequest voucher) {
        voucher.setType(1);
        Result result = createVoucher(voucher);
        if (!Boolean.TRUE.equals(result.getSuccess())) {
            throw new IllegalArgumentException(result.getErrorMsg());
        }
    }

    private Map<String, Object> voucherPayload(VoucherRequest voucher) {
        Map<String, Object> payload = new LinkedHashMap<>();
        putIfPresent(payload, "id", voucher.getId());
        putIfPresent(payload, "shopId", voucher.getShopId());
        putIfPresent(payload, "title", voucher.getTitle());
        putIfPresent(payload, "subTitle", voucher.getSubTitle());
        putIfPresent(payload, "rules", voucher.getRules());
        putIfPresent(payload, "payValue", voucher.getPayValue());
        putIfPresent(payload, "actualValue", voucher.getActualValue());
        putIfPresent(payload, "type", voucher.getType());
        putIfPresent(payload, "status", voucher.getStatus());
        putIfPresent(payload, "stock", voucher.getStock());
        putIfPresent(payload, "beginTime", voucher.getBeginTime());
        putIfPresent(payload, "endTime", voucher.getEndTime());
        return payload;
    }

    private void putIfPresent(Map<String, Object> payload, String key, Object value) {
        if (value != null) {
            payload.put(key, value);
        }
    }
}
