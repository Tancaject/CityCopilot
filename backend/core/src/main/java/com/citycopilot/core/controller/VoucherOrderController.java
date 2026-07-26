package com.citycopilot.core.controller;


import com.citycopilot.core.dto.Result;
import com.citycopilot.core.security.PermissionCodes;
import com.citycopilot.core.security.RequirePermission;
import com.citycopilot.core.service.IVoucherOrderService;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestHeader;

/**
 * <p>
 *  前端控制器
 * </p>
 *
 */
@RestController
@RequestMapping("/voucher-order")
public class VoucherOrderController {
    @Resource
    private IVoucherOrderService voucherOrderService;

    @PostMapping("seckill/{id}")
    public Result seckillVoucher(
            @PathVariable("id") Long voucherId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey
    ) {
        return voucherOrderService.seckillVoucher(voucherId, idempotencyKey);
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
