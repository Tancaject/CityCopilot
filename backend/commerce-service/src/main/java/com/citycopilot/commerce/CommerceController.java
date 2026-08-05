package com.citycopilot.commerce;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/commerce/v1")
@ConditionalOnProperty(
        prefix = "citycopilot.commerce",
        name = "api-enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class CommerceController {
    private final CommerceOrderService orders;
    private final CommerceVoucherService vouchers;
    private final CommerceConsistencyService consistency;
    private final CommerceOperationsService operations;
    private final CommerceRoutingGuard routing;

    public CommerceController(
            CommerceOrderService orders,
            CommerceVoucherService vouchers,
            CommerceConsistencyService consistency,
            CommerceOperationsService operations,
            CommerceRoutingGuard routing
    ) {
        this.orders = orders;
        this.vouchers = vouchers;
        this.consistency = consistency;
        this.operations = operations;
        this.routing = routing;
    }

    @PostMapping("/seckill/{voucherId}")
    ResponseEntity<Map<String, Object>> seckill(
            @PathVariable long voucherId,
            @RequestHeader("X-CityCopilot-User-Id") long userId,
            @RequestHeader("Idempotency-Key") String idempotencyKey
    ) {
        routing.requireAdmissionActive(voucherId);
        Map<String, Object> result = orders.seckill(voucherId, userId, idempotencyKey);
        ResponseEntity.BodyBuilder response = "COMMERCE_ORDER_ACCEPTED".equals(result.get("code"))
                ? ResponseEntity.status(HttpStatus.ACCEPTED)
                : ResponseEntity.ok();
        Object orderId = result.get("orderId");
        if (orderId != null) {
            response.location(URI.create("/commerce/v1/orders/" + orderId));
        }
        return response.body(result);
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

    @GetMapping("/ops/summary")
    Map<String, Object> operationsSummary(
            @RequestParam String from,
            @RequestParam String to
    ) {
        try {
            return operations.summary(
                    java.time.Instant.parse(from),
                    java.time.Instant.parse(to)
            );
        } catch (java.time.format.DateTimeParseException error) {
            throw new CommerceApiException(
                    "COMMERCE_INVALID_ARGUMENT",
                    "from and to must be ISO-8601 instants",
                    400
            );
        }
    }

    @GetMapping("/ops/dead-letters")
    List<Map<String, Object>> deadLetters(
            @RequestHeader(value = "X-CityCopilot-Ops-Authorized", defaultValue = "false") boolean opsAuthorized,
            @RequestParam(defaultValue = "20") Integer limit
    ) {
        requireOpsAuthorization(opsAuthorized);
        return operations.deadLetters(limit);
    }

    @PostMapping("/ops/dead-letters/{sourceId}/replay")
    Map<String, Object> replayDeadLetter(
            @PathVariable String sourceId,
            @RequestHeader(value = "X-CityCopilot-Ops-Authorized", defaultValue = "false") boolean opsAuthorized,
            @RequestHeader(value = "X-CityCopilot-Actor", defaultValue = "unknown") String actor
    ) {
        requireOpsAuthorization(opsAuthorized);
        return operations.replayDeadLetter(sourceId, actor);
    }

    @PostMapping("/ops/admissions/{orderId}/requeue")
    Map<String, Object> requeueAdmissionRepair(
            @PathVariable long orderId,
            @RequestHeader(value = "X-CityCopilot-Ops-Authorized", defaultValue = "false") boolean opsAuthorized,
            @RequestHeader(value = "X-CityCopilot-Actor", defaultValue = "unknown") String actor
    ) {
        requireOpsAuthorization(opsAuthorized);
        return operations.requeueAdmissionRepair(orderId, actor);
    }

    private void requireOpsAuthorization(boolean authorized) {
        if (!authorized) {
            throw new CommerceApiException(
                    "COMMERCE_OPS_FORBIDDEN",
                    "Commerce operations authorization is required",
                    403
            );
        }
    }
}
