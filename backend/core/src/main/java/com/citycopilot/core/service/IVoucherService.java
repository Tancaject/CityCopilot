package com.citycopilot.core.service;

import com.citycopilot.contract.tool.AgentTool;
import com.citycopilot.core.dto.Result;
import com.citycopilot.core.dto.VoucherRequest;

/**
 * <p>
 *  服务类
 * </p>
 *
 */
public interface IVoucherService {

    @AgentTool(name = "list_vouchers_by_shop", description = "List vouchers available for a shop")
    Result queryVoucherOfShop(Long shopId);

    Result queryAdminVouchers(Integer current, Long shopId, String keyword, Integer status);

    Result createVoucher(VoucherRequest voucher);

    Result updateVoucher(VoucherRequest voucher);

    Result updateVoucherStatus(Long id, Integer status);

    void addSeckillVoucher(VoucherRequest voucher);
}
