package com.citycopilot.core.service.impl;

import com.citycopilot.core.commerce.CommercePortClient;
import com.citycopilot.core.dto.Result;
import com.citycopilot.core.dto.VoucherRequest;
import com.citycopilot.core.service.IVoucherService;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Core 优惠券 API 的兼容门面，把券查询和写入统一转交 Commerce 服务。
 */
@Service
public class VoucherServiceImpl implements IVoucherService {

    /** Commerce 服务客户端，保证券与订单共享同一业务所有权边界。 */
    private final CommercePortClient commercePortClient;

    /** @param commercePortClient Commerce 服务客户端 */
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

    /**
     * 将 Core DTO 转成 Commerce 接口接受的稀疏字段映射。
     *
     * @param voucher Core 券请求
     * @return 仅包含非空字段的请求体，避免更新时用空值覆盖远端数据
     */
    private Map<String, Object> voucherPayload(VoucherRequest voucher) {
        Map<String, Object> payload = new LinkedHashMap<>();
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

    /**
     * 仅在字段有值时加入请求体。
     *
     * @param payload 待构造的请求体
     * @param key Commerce 字段名
     * @param value 字段值
     */
    private void putIfPresent(Map<String, Object> payload, String key, Object value) {
        if (value != null) {
            payload.put(key, value);
        }
    }
}
