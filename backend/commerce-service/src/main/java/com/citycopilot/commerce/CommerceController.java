package com.citycopilot.commerce;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/commerce/v1")
public class CommerceController {
    private final CommerceOrderService orders;
    private final CommerceVoucherService vouchers;
    private final CommerceConsistencyService consistency;

    public CommerceController(
            CommerceOrderService orders,
            CommerceVoucherService vouchers,
            CommerceConsistencyService consistency
    ) {
        this.orders = orders;
        this.vouchers = vouchers;
        this.consistency = consistency;
    }

    @PostMapping("/seckill/{voucherId}")
    Map<String, Object> seckill(
            @PathVariable long voucherId,
            @RequestHeader("X-CityCopilot-User-Id") long userId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey
    ) {
        return orders.seckill(voucherId, userId, idempotencyKey);
    }

    @GetMapping("/orders/{orderId}")
    Map<String, Object> order(
            @PathVariable long orderId,
            @RequestHeader("X-CityCopilot-User-Id") long userId,
            @RequestParam(defaultValue = "false") boolean admin
    ) {
        return orders.order(orderId, userId, admin);
    }

    @GetMapping("/orders")
    List<Map<String, Object>> myOrders(
            @RequestHeader("X-CityCopilot-User-Id") long userId,
            @RequestParam(defaultValue = "1") int page
    ) {
        return orders.myOrders(userId, page);
    }

    @PostMapping("/orders/{orderId}/actions")
    Map<String, Object> transition(
            @PathVariable long orderId,
            @RequestHeader("X-CityCopilot-User-Id") long userId,
            @RequestParam(defaultValue = "false") boolean admin,
            @RequestBody ActionRequest request
    ) {
        return orders.transition(orderId, userId, request.action(), request.payType(), admin);
    }

    record ActionRequest(String action, Integer payType) {}

    @GetMapping("/vouchers")
    List<Map<String, Object>> vouchers(
            @RequestParam(required = false) Long shopId,
            @RequestParam(defaultValue = "false") boolean admin,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Integer status
    ) {
        if (!admin && shopId == null) {
            throw new CommerceApiException("COMMERCE_INVALID_ARGUMENT", "shopId is required", 400);
        }
        return admin ? vouchers.adminList(shopId, keyword, status) : vouchers.list(shopId);
    }

    @PostMapping("/vouchers")
    Map<String, Object> createVoucher(
            @RequestHeader("X-CityCopilot-User-Id") long userId,
            @RequestHeader(value = "X-CityCopilot-Admin", defaultValue = "false") boolean admin,
            @RequestBody Map<String, Object> request
    ) {
        return Map.of("id", vouchers.create(request, userId, admin));
    }

    @PostMapping("/vouchers/{voucherId}")
    Map<String, Object> updateVoucher(
            @PathVariable long voucherId,
            @RequestHeader("X-CityCopilot-User-Id") long userId,
            @RequestHeader(value = "X-CityCopilot-Admin", defaultValue = "false") boolean admin,
            @RequestBody Map<String, Object> request
    ) {
        return vouchers.update(voucherId, request, userId, admin);
    }

    @GetMapping("/consistency/{voucherId}")
    Map<String, Object> consistency(@PathVariable long voucherId) {
        return consistency.snapshot(voucherId);
    }

    @PostMapping("/consistency/{voucherId}/repair")
    Map<String, Object> repair(
            @PathVariable long voucherId,
            @RequestBody Map<String, String> request
    ) {
        return consistency.repair(voucherId, request.get("expectedEvidenceHash"));
    }

    @GetMapping("/consistency/{voucherId}/verify")
    Map<String, Object> verify(@PathVariable long voucherId) {
        return consistency.verify(voucherId);
    }
}
