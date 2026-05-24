package com.hmdp.controller;


import com.hmdp.dto.Result;
import com.hmdp.security.PermissionCodes;
import com.hmdp.security.RequirePermission;
import com.hmdp.service.IVoucherOrderService;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * <p>
 *  前端控制器
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@RestController
@RequestMapping("/voucher-order")
public class VoucherOrderController {
    @Resource
    private IVoucherOrderService voucherOrderService;

    @PostMapping("seckill/{id}")
    public Result seckillVoucher(@PathVariable("id") Long voucherId) {
        return voucherOrderService.seckillVoucher(voucherId);
    }

    @GetMapping("of/me")
    public Result queryMyOrders(@RequestParam(value = "current", defaultValue = "1") Integer current) {
        return voucherOrderService.queryMyOrders(current);
    }

    @GetMapping("{id}")
    public Result queryOrder(@PathVariable("id") Long orderId) {
        return voucherOrderService.queryOrder(orderId);
    }

    @PostMapping("{id}/cancel")
    public Result cancelOrder(@PathVariable("id") Long orderId) {
        return voucherOrderService.cancelOrder(orderId);
    }

    @PostMapping("{id}/pay")
    public Result payOrder(
            @PathVariable("id") Long orderId,
            @RequestParam(value = "payType", defaultValue = "1") Integer payType
    ) {
        return voucherOrderService.payOrder(orderId, payType);
    }

    @PostMapping("{id}/use")
    @RequirePermission(PermissionCodes.ORDER_WRITE)
    public Result useOrder(@PathVariable("id") Long orderId) {
        return voucherOrderService.useOrder(orderId);
    }

    @PostMapping("{id}/refund")
    public Result refundOrder(@PathVariable("id") Long orderId) {
        return voucherOrderService.refundOrder(orderId);
    }
}
