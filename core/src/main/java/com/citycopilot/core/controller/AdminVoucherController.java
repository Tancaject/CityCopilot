package com.citycopilot.core.controller;

import com.citycopilot.core.dto.Result;
import com.citycopilot.core.entity.Voucher;
import com.citycopilot.core.security.PermissionCodes;
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

@RestController
@RequestMapping("/admin/vouchers")
@RequirePermission(PermissionCodes.VOUCHER_WRITE)
public class AdminVoucherController {

    @Resource
    private IVoucherService voucherService;

    @GetMapping
    public Result queryVouchers(
            @RequestParam(value = "current", defaultValue = "1") Integer current,
            @RequestParam(value = "shopId", required = false) Long shopId,
            @RequestParam(value = "keyword", required = false) String keyword,
            @RequestParam(value = "status", required = false) Integer status
    ) {
        return voucherService.queryAdminVouchers(current, shopId, keyword, status);
    }

    @PostMapping
    public Result createVoucher(@RequestBody Voucher voucher) {
        return voucherService.createVoucher(voucher);
    }

    @PutMapping("/{id}")
    public Result updateVoucher(@PathVariable("id") Long id, @RequestBody Voucher voucher) {
        voucher.setId(id);
        return voucherService.updateVoucher(voucher);
    }

    @PutMapping("/{id}/status")
    public Result updateVoucherStatus(@PathVariable("id") Long id, @RequestParam("status") Integer status) {
        return voucherService.updateVoucherStatus(id, status);
    }
}
