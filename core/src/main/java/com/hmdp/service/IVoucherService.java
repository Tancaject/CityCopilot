package com.hmdp.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.hmdp.dto.Result;
import com.hmdp.entity.Voucher;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface IVoucherService extends IService<Voucher> {

    Result queryVoucherOfShop(Long shopId);

    Result queryAdminVouchers(Integer current, Long shopId, String keyword, Integer status);

    Result createVoucher(Voucher voucher);

    Result updateVoucher(Voucher voucher);

    Result updateVoucherStatus(Long id, Integer status);

    void addSeckillVoucher(Voucher voucher);
}
