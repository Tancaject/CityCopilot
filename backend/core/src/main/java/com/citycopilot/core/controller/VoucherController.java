package com.citycopilot.core.controller;


import com.citycopilot.core.dto.Result;
import com.citycopilot.core.entity.Voucher;
import com.citycopilot.core.security.PermissionCodes;
import com.citycopilot.core.security.RequirePermission;
import com.citycopilot.core.service.IVoucherService;
import org.springframework.web.bind.annotation.*;

import jakarta.annotation.Resource;

/**
 * <p>
 *  前端控制器
 * </p>
 *
 */
@RestController
@RequestMapping("/voucher")
public class VoucherController {

    @Resource
    private IVoucherService voucherService;

    /**
     * 新增普通券
     * @param voucher 优惠券信息
     * @return 优惠券id
     */
    @PostMapping
    @RequirePermission(PermissionCodes.VOUCHER_WRITE)
    public Result addVoucher(@RequestBody Voucher voucher) {
        voucher.setType(0);
        return voucherService.createVoucher(voucher);
    }

    /**
     * 新增秒杀券
     * @param voucher 优惠券信息，包含秒杀信息
     * @return 优惠券id
     */
    @PostMapping("seckill")
    @RequirePermission(PermissionCodes.VOUCHER_WRITE)
    public Result addSeckillVoucher(@RequestBody Voucher voucher) {
        voucher.setType(1);
        return voucherService.createVoucher(voucher);
    }

    /**
     * 查询店铺的优惠券列表
     * @param shopId 店铺id
     * @return 优惠券列表
     */
    @GetMapping("/list/{shopId}")
    public Result queryVoucherOfShop(@PathVariable("shopId") Long shopId) {
       return voucherService.queryVoucherOfShop(shopId);
    }
}
