package com.citycopilot.core.controller;


import com.citycopilot.core.dto.Result;
import com.citycopilot.core.dto.VoucherRequest;
import com.citycopilot.contract.security.PermissionCodes;
import com.citycopilot.core.security.RequirePermission;
import com.citycopilot.core.service.IVoucherService;
import org.springframework.web.bind.annotation.*;

import jakarta.annotation.Resource;

/**
 * 前台优惠券查询及兼容创建接口。
 *
 * <p>Core 只负责 HTTP 门面，优惠券权威写入和存储由 {@link IVoucherService} 转发到 Commerce。</p>
 */
@RestController
@RequestMapping("/voucher")
public class VoucherController {

    /** Commerce 优惠券端口的 Core 适配服务。 */
    @Resource
    private IVoucherService voucherService;

    /**
     * 新增普通券
     * @param voucher 优惠券信息
     * @return 优惠券id
     */
    @PostMapping
    @RequirePermission(PermissionCodes.VOUCHER_WRITE)
    public Result addVoucher(@RequestBody VoucherRequest voucher) {
        // 明确覆盖客户端 type，保证该路由只能创建普通券。
        voucher.setType(0);
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
