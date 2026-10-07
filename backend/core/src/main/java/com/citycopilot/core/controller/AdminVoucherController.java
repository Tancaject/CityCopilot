package com.citycopilot.core.controller;

import com.citycopilot.core.dto.Result;
import com.citycopilot.core.dto.VoucherRequest;
import com.citycopilot.contract.security.PermissionCodes;
import com.citycopilot.core.security.RequirePermission;
import com.citycopilot.core.service.IVoucherService;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 运营端优惠券查询、创建、更新及上下架接口，整类要求优惠券写权限。 */
@RestController
@RequestMapping("/admin/vouchers")
@RequirePermission(PermissionCodes.VOUCHER_WRITE)
public class AdminVoucherController {

    /** 将 Core 请求转发到 Commerce 权威优惠券 API 的服务。 */
    @Resource
    private IVoucherService voucherService;

    /**
     * @param current 页码，从 1 开始
     * @param shopId 可选店铺筛选
     * @param keyword 可选标题关键词
     * @param status 可选状态码
     * @return 分页优惠券列表
     */
    @GetMapping
    public Result queryVouchers(
            @RequestParam(value = "current", defaultValue = "1") Integer current,
            @RequestParam(value = "shopId", required = false) Long shopId,
            @RequestParam(value = "keyword", required = false) String keyword,
            @RequestParam(value = "status", required = false) Integer status
    ) {
        return voucherService.queryAdminVouchers(current, shopId, keyword, status);
    }

    /**
     * @param voucher 新优惠券字段
     * @return Commerce 创建结果
     */
    @PostMapping
    public Result createVoucher(@RequestBody VoucherRequest voucher) {
        return voucherService.createVoucher(voucher);
    }

    /**
     * @param id 路径中的优惠券主键，以此覆盖请求体 ID
     * @param voucher 待更新字段
     * @return Commerce 更新结果
     */
    @PutMapping("/{id}")
    public Result updateVoucher(@PathVariable("id") Long id, @RequestBody VoucherRequest voucher) {
        voucher.setId(id);
        return voucherService.updateVoucher(voucher);
    }

    /**
     * @param id 优惠券主键
     * @param status 新状态码
     * @return 状态更新结果
     */
    @PutMapping("/{id}/status")
    public Result updateVoucherStatus(@PathVariable("id") Long id, @RequestParam("status") Integer status) {
        return voucherService.updateVoucherStatus(id, status);
    }
}
