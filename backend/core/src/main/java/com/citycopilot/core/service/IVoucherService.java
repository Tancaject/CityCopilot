package com.citycopilot.core.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.citycopilot.contract.tool.AgentTool;
import com.citycopilot.core.dto.Result;
import com.citycopilot.core.entity.Voucher;

/**
 * <p>
 *  服务类
 * </p>
 *
 */
public interface IVoucherService extends IService<Voucher> {

    @AgentTool(name = "list_vouchers_by_shop", description = "List vouchers available for a shop")
    Result queryVoucherOfShop(Long shopId);

    Result queryAdminVouchers(Integer current, Long shopId, String keyword, Integer status);

    Result createVoucher(Voucher voucher);

    Result updateVoucher(Voucher voucher);

    Result updateVoucherStatus(Long id, Integer status);

    void addSeckillVoucher(Voucher voucher);
}
