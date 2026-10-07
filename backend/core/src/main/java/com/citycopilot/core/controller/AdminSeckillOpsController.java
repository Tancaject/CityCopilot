package com.citycopilot.core.controller;

import com.citycopilot.contract.commerce.CommerceContracts.ConsistencyRepairResult;
import com.citycopilot.contract.commerce.CommerceContracts.DeadLetterReplayResult;
import com.citycopilot.contract.commerce.CommerceContracts.DeadLetterView;
import com.citycopilot.contract.commerce.CommerceContracts.PaymentReview;
import com.citycopilot.contract.security.PermissionCodes;
import com.citycopilot.core.dto.Result;
import com.citycopilot.core.security.RequirePermission;
import com.citycopilot.core.service.SeckillConsistencyService;
import com.citycopilot.core.service.SeckillRecoveryService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestClientException;

import java.util.List;
import java.util.Map;

/**
 * 秒杀库存一致性与死信的人工运维入口。
 *
 * <p>流程为“读快照取证据哈希 → 带哈希修复 → 复核”；证据过期或存在在途冲突时由 Commerce 拒绝。</p>
 */
@RestController
@RequestMapping("/admin/seckill")
@RequirePermission(PermissionCodes.ADMIN_ACCESS)
public class AdminSeckillOpsController {

    private final SeckillConsistencyService consistency;
    private final SeckillRecoveryService recovery;

    public AdminSeckillOpsController(SeckillConsistencyService consistency, SeckillRecoveryService recovery) {
        this.consistency = consistency;
        this.recovery = recovery;
    }

    /** @return 指定券的一致性证据及证据哈希 */
    @GetMapping("/consistency/{voucherId}")
    public Map<String, Object> snapshot(@PathVariable Long voucherId) {
        return consistency.snapshot(voucherId);
    }

    /** 使用刚读取的证据哈希修复 Redis 库存。 */
    @PostMapping("/consistency/{voucherId}/repair")
    @RequirePermission({PermissionCodes.ADMIN_ACCESS, PermissionCodes.ORDER_WRITE})
    public ConsistencyRepairResult repair(@PathVariable Long voucherId, @RequestBody RepairRequest request) {
        return consistency.repairStock(voucherId, request.expectedEvidenceHash());
    }

    /** @return 重新采集证据后的一致性结论 */
    @GetMapping("/consistency/{voucherId}/verify")
    public Map<String, Object> verify(@PathVariable Long voucherId) {
        return consistency.verify(voucherId);
    }

    /** @return 近期秒杀死信 */
    @GetMapping("/dead-letters")
    public List<DeadLetterView> deadLetters(@RequestParam(required = false) Integer limit) {
        return recovery.listDeadLetters(limit);
    }

    /** 幂等重放一条死信。 */
    @PostMapping("/dead-letters/{sourceId}/replay")
    @RequirePermission({PermissionCodes.ADMIN_ACCESS, PermissionCodes.ORDER_WRITE})
    public DeadLetterReplayResult replay(@PathVariable String sourceId) {
        return recovery.replayDeadLetter(sourceId);
    }

    @GetMapping("/payments")
    public List<PaymentReview> paymentIssues() { return recovery.paymentIssues(); }

    @GetMapping("/payments/{paymentId}")
    public PaymentReview paymentReview(@PathVariable String paymentId) { return recovery.paymentReview(paymentId); }

    @PostMapping("/payments/{paymentId}/repair")
    @RequirePermission({PermissionCodes.ADMIN_ACCESS, PermissionCodes.ORDER_WRITE})
    public PaymentReview repairPayment(@PathVariable String paymentId, @RequestBody RepairRequest request) {
        return recovery.repairPayment(paymentId, request.expectedEvidenceHash());
    }

    /** 保留 Commerce 的拒绝状态码（如证据哈希已过期），让操作者重新取证。 */
    @ExceptionHandler(RestClientResponseException.class)
    ResponseEntity<Result> commerceRejected(RestClientResponseException error) {
        return ResponseEntity.status(error.getStatusCode())
                .body(Result.fail("COMMERCE_OPS_REJECTED", error.getResponseBodyAsString()));
    }

    /** 死信接口不可用时明确返回 503，而不是包装成业务失败。 */
    @ExceptionHandler({IllegalStateException.class, RestClientException.class})
    ResponseEntity<Result> commerceUnavailable(RuntimeException error) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(Result.fail("COMMERCE_UNAVAILABLE", error.getMessage()));
    }

    public record RepairRequest(String expectedEvidenceHash) {}
}
