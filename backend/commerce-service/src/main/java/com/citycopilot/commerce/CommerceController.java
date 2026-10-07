package com.citycopilot.commerce;

import com.citycopilot.contract.commerce.CommerceContracts.AdmissionAccepted;
import com.citycopilot.contract.commerce.CommerceContracts.ConsistencyRepairRequest;
import com.citycopilot.contract.commerce.CommerceContracts.ConsistencyRepairResult;
import com.citycopilot.contract.commerce.CommerceContracts.OrderActionRequest;
import com.citycopilot.contract.commerce.CommerceContracts.OrderSummary;
import com.citycopilot.contract.commerce.CommerceContracts.OrderView;
import com.citycopilot.contract.commerce.CommerceContracts.VoucherView;
import com.citycopilot.contract.commerce.CommerceContracts.VoucherCreated;
import com.citycopilot.contract.commerce.CommerceContracts.OperationsSummary;
import com.citycopilot.contract.commerce.CommerceContracts.DeadLetterView;
import com.citycopilot.contract.commerce.CommerceContracts.DeadLetterReplayResult;
import com.citycopilot.contract.commerce.CommerceContracts.AdmissionRepairRequeued;
import com.citycopilot.contract.commerce.CommerceContracts.PaymentReview;
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

/**
 * Commerce 服务的版本化 HTTP 入口，负责参数接收、状态码选择和运维权限门禁。
 *
 * <p>业务规则、事务和恢复逻辑分别下沉到 Order、Voucher、Consistency 与 Operations 服务；
 * 控制器本身不直接访问 Redis 或 MySQL。</p>
 */
@RestController
@RequestMapping("/commerce/v1")
@ConditionalOnProperty(
        prefix = "citycopilot.commerce",
        name = "api-enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class CommerceController {
    /** 订单查询、状态流转及库存恢复。 */
    private final CommerceOrderService orders;
    private final CommerceOrderAdmissionService admissions;
    /** Voucher 查询与受权写入。 */
    private final CommerceVoucherService vouchers;
    /** MySQL/Redis 一致性取证、修复和验证。 */
    private final CommerceConsistencyService consistency;
    /** 死信、准入修复和运营汇总接口。 */
    private final CommerceOperationsService operations;
    private final CommercePaymentService payments;

    public CommerceController(
            CommerceOrderService orders,
            CommerceOrderAdmissionService admissions,
            CommerceVoucherService vouchers,
            CommerceConsistencyService consistency,
            CommerceOperationsService operations,
            CommercePaymentService payments
    ) {
        this.orders = orders;
        this.admissions = admissions;
        this.vouchers = vouchers;
        this.consistency = consistency;
        this.operations = operations;
        this.payments = payments;
    }

    /**
     * 提交秒杀请求，以幂等键执行持久化准入。
     *
     * @param voucherId 秒杀 Voucher ID
     * @param userId 调用方传递的用户 ID
     * @param idempotencyKey 客户端重试时必须复用的幂等键
     * @return 首次受理返回 202；幂等重放或已完成结果返回 200
     */
    @PostMapping("/seckill/{voucherId}")
    ResponseEntity<AdmissionAccepted> seckill(
            @PathVariable long voucherId,
            @RequestHeader("X-CityCopilot-User-Id") long userId,
            @RequestHeader("Idempotency-Key") String idempotencyKey
    ) {
        AdmissionAccepted result = admissions.seckill(voucherId, userId, idempotencyKey);
        ResponseEntity.BodyBuilder response = "COMMERCE_ORDER_ACCEPTED".equals(result.code())
                ? ResponseEntity.status(HttpStatus.ACCEPTED)
                : ResponseEntity.ok();
        String orderId = result.orderId();
        if (orderId != null) {
            // Location 指向后续查询资源，客户端无需猜测异步订单的查询地址。
            response.location(URI.create("/commerce/v1/orders/" + orderId));
        }
        return response.body(result);
    }

    /**
     * 查询单个订单；普通用户只能查看本人订单，管理员可跨用户查询。
     *
     * @param orderId 订单 ID
     * @param userId 当前用户 ID
     * @param admin 是否使用管理员查询范围
     * @return 订单快照
     */
    @GetMapping("/orders/{orderId}")
    OrderView order(
            @PathVariable long orderId,
            @RequestHeader("X-CityCopilot-User-Id") long userId,
            @RequestParam(defaultValue = "false") boolean admin,
            @RequestParam(defaultValue = "false") boolean merchant
    ) {
        return orders.order(orderId, userId, admin, merchant);
    }

    /**
     * 分页查询当前用户订单。
     *
     * @param userId 当前用户 ID
     * @param page 从 1 开始的页码
     * @return 当前页订单，固定每页 10 条
     */
    @GetMapping("/orders")
    List<OrderSummary> myOrders(
            @RequestHeader("X-CityCopilot-User-Id") long userId,
            @RequestParam(defaultValue = "1") int page
    ) {
        return orders.myOrders(userId, page);
    }

    /**
     * 执行支付、取消、核销或退款状态流转。
     *
     * @param orderId 订单 ID
     * @param userId 当前用户 ID
     * @param admin 是否以管理员身份操作
     * @param request 动作名及可选支付方式
     * @return 流转后的订单；库存恢复未完成时带恢复状态
     */
    @PostMapping("/orders/{orderId}/actions")
    OrderView transition(
            @PathVariable long orderId,
            @RequestHeader("X-CityCopilot-User-Id") long userId,
            @RequestParam(defaultValue = "false") boolean admin,
            @RequestParam(defaultValue = "false") boolean merchant,
            @RequestBody OrderActionRequest request
    ) {
        return switch (request.action()) {
            case "pay" -> payments.start(orderId, userId, request.payType(), admin, merchant);
            case "refund" -> payments.refund(orderId, userId, admin, merchant);
            default -> orders.transition(orderId, userId, request.action(), admin, merchant);
        };
    }

    /**
     * 查询 Voucher；普通调用必须限定店铺，管理员可附加关键字和状态筛选。
     *
     * @param shopId 店铺 ID
     * @param admin 是否使用管理员查询
     * @param keyword 标题关键字
     * @param status Voucher 状态
     * @return 匹配的 Voucher 列表
     */
    @GetMapping("/vouchers")
    List<VoucherView> vouchers(
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

    /**
     * 创建 Voucher，写入前由 Place 服务确认店铺存在性和操作者权限。
     *
     * @param userId 操作者用户 ID
     * @param admin 是否为管理员
     * @param request Voucher 字段
     * @return 新建 Voucher ID
     */
    @PostMapping("/vouchers")
    VoucherCreated createVoucher(
            @RequestHeader("X-CityCopilot-User-Id") long userId,
            @RequestHeader(value = "X-CityCopilot-Admin", defaultValue = "false") boolean admin,
            @RequestBody Map<String, Object> request
    ) {
        return vouchers.create(request, userId, admin);
    }

    /**
     * 更新 Voucher；秒杀活动更新会先关闭 Redis 元数据，再于事务提交后重新发布。
     *
     * @param voucherId Voucher ID
     * @param userId 操作者用户 ID
     * @param admin 是否为管理员
     * @param request 待更新字段
     * @return 更新后的 Voucher
     */
    @PostMapping("/vouchers/{voucherId}")
    VoucherView updateVoucher(
            @PathVariable long voucherId,
            @RequestHeader("X-CityCopilot-User-Id") long userId,
            @RequestHeader(value = "X-CityCopilot-Admin", defaultValue = "false") boolean admin,
            @RequestBody Map<String, Object> request
    ) {
        return vouchers.update(voucherId, request, userId, admin);
    }

    /** @return 指定 Voucher 的 MySQL/Redis 一致性证据和证据哈希 */
    @GetMapping("/consistency/{voucherId}")
    Map<String, Object> consistency(@PathVariable long voucherId) {
        return consistency.snapshot(voucherId);
    }

    /**
     * 使用调用方刚读取的证据哈希修复 Redis 库存和买家集合。
     *
     * @param voucherId Voucher ID
     * @param request 必须包含 {@code expectedEvidenceHash}
     * @return 修复前后证据摘要
     */
    @PostMapping("/consistency/{voucherId}/repair")
    ConsistencyRepairResult repair(
            @PathVariable long voucherId,
            @RequestBody ConsistencyRepairRequest request
    ) {
        return consistency.repair(voucherId, request.expectedEvidenceHash());
    }

    /** @return 修复后复核结果及完整快照 */
    @GetMapping("/consistency/{voucherId}/verify")
    Map<String, Object> verify(@PathVariable long voucherId) {
        return consistency.verify(voucherId);
    }

    /**
     * 汇总指定时间窗的订单和 Voucher 运营指标。
     *
     * @param from 包含端的 ISO-8601 时间
     * @param to 排除端的 ISO-8601 时间
     * @return 运营汇总
     */
    @GetMapping("/ops/summary")
    OperationsSummary operationsSummary(
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

    /**
     * 查询最近死信。
     *
     * @param opsAuthorized 上游完成运维鉴权后设置的标志
     * @param limit 返回条数，服务端限制在 1 到 100
     * @return 死信及恢复阶段
     */
    @GetMapping("/ops/dead-letters")
    List<DeadLetterView> deadLetters(
            @RequestHeader(value = "X-CityCopilot-Ops-Authorized", defaultValue = "false") boolean opsAuthorized,
            @RequestParam(defaultValue = "20") Integer limit
    ) {
        requireOpsAuthorization(opsAuthorized);
        return operations.deadLetters(limit);
    }

    /**
     * 以原订单身份重新执行一条死信准入，并记录运维审计事件。
     *
     * @param sourceId 死信来源 ID
     * @param opsAuthorized 运维鉴权标志
     * @param actor 操作者标识
     * @return 重放结果
     */
    @PostMapping("/ops/dead-letters/{sourceId}/replay")
    DeadLetterReplayResult replayDeadLetter(
            @PathVariable String sourceId,
            @RequestHeader(value = "X-CityCopilot-Ops-Authorized", defaultValue = "false") boolean opsAuthorized,
            @RequestHeader(value = "X-CityCopilot-Actor", defaultValue = "unknown") String actor
    ) {
        requireOpsAuthorization(opsAuthorized);
        return operations.replayDeadLetter(sourceId, actor);
    }

    /**
     * 将已进入人工修复态的持久化准入重新放回自动恢复队列。
     *
     * @param orderId 订单 ID
     * @param opsAuthorized 运维鉴权标志
     * @param actor 操作者标识
     * @return 重新排队结果
     */
    @PostMapping("/ops/admissions/{orderId}/requeue")
    AdmissionRepairRequeued requeueAdmissionRepair(
            @PathVariable long orderId,
            @RequestHeader(value = "X-CityCopilot-Ops-Authorized", defaultValue = "false") boolean opsAuthorized,
            @RequestHeader(value = "X-CityCopilot-Actor", defaultValue = "unknown") String actor
    ) {
        requireOpsAuthorization(opsAuthorized);
        return operations.requeueAdmissionRepair(orderId, actor);
    }

    /** 拒绝未经过上游运维授权的高风险修复操作。 */
    @GetMapping("/ops/payments")
    List<PaymentReview> paymentIssues(
            @RequestHeader(value = "X-CityCopilot-Ops-Authorized", defaultValue = "false") boolean opsAuthorized) {
        requireOpsAuthorization(opsAuthorized);
        return payments.issues();
    }

    @GetMapping("/ops/payments/{paymentId}")
    PaymentReview paymentReview(@PathVariable String paymentId,
            @RequestHeader(value = "X-CityCopilot-Ops-Authorized", defaultValue = "false") boolean opsAuthorized) {
        requireOpsAuthorization(opsAuthorized);
        return payments.review(paymentId);
    }

    @PostMapping("/ops/payments/{paymentId}/repair")
    PaymentReview repairPayment(@PathVariable String paymentId, @RequestBody ConsistencyRepairRequest request,
            @RequestHeader(value = "X-CityCopilot-Ops-Authorized", defaultValue = "false") boolean opsAuthorized) {
        requireOpsAuthorization(opsAuthorized);
        return payments.repair(paymentId, request.expectedEvidenceHash());
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
