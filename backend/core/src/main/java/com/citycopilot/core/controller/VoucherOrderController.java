package com.citycopilot.core.controller;


import com.citycopilot.core.commerce.CommercePortClient;
import com.citycopilot.core.dto.Result;
import com.citycopilot.core.security.PermissionCodes;
import com.citycopilot.core.security.RequirePermission;
import com.citycopilot.core.service.IVoucherOrderService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestHeader;

import java.net.URI;

/**
 * <p>
 *  前端控制器
 * </p>
 *
 */
@RestController
@RequestMapping("/voucher-order")
public class VoucherOrderController {
    private final IVoucherOrderService voucherOrderService;

    public VoucherOrderController(IVoucherOrderService voucherOrderService) {
        this.voucherOrderService = voucherOrderService;
    }

    @PostMapping("seckill/{id}")
    public ResponseEntity<Result> seckillVoucher(
            @PathVariable("id") Long voucherId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey
    ) {
        Result result = voucherOrderService.seckillVoucher(voucherId, idempotencyKey);
        HttpStatus status = "COMMERCE_ORDER_ACCEPTED".equals(result.getCode())
                ? HttpStatus.ACCEPTED
                : HttpStatus.OK;
        ResponseEntity.BodyBuilder response = ResponseEntity.status(status);
        if (result.getData() != null) {
            response.location(URI.create("/voucher-order/" + result.getData()));
        }
        return response.body(result);
    }

    @ExceptionHandler(CommercePortClient.CommerceHttpException.class)
    ResponseEntity<Result> commerceFailure(CommercePortClient.CommerceHttpException error) {
        ResponseEntity.BodyBuilder response = ResponseEntity.status(error.status());
        if (error.retryAfter() != null && !error.retryAfter().isBlank()) {
            response.header(HttpHeaders.RETRY_AFTER, error.retryAfter());
        }
        return response.body(Result.fail(error.code(), error.getMessage()));
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
