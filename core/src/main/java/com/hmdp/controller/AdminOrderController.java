package com.hmdp.controller;

import com.hmdp.dto.Result;
import com.hmdp.security.PermissionCodes;
import com.hmdp.security.RequirePermission;
import com.hmdp.service.IVoucherOrderService;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin/orders")
@RequirePermission(PermissionCodes.ORDER_WRITE)
public class AdminOrderController {

    @Resource
    private IVoucherOrderService voucherOrderService;

    @GetMapping("/{id}")
    public Result queryOrder(@PathVariable("id") Long orderId) {
        return voucherOrderService.queryOrderForAdmin(orderId);
    }
}
