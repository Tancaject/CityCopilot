package com.citycopilot.commerce;

import com.citycopilot.contract.commerce.CommerceContracts.OrderView;
import com.citycopilot.contract.commerce.CommerceContracts.PaymentReview;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 模拟渠道的支付单和回调都持久化；所有交易写入按订单、支付单的顺序加锁。 */
@Service
public class CommercePaymentService {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final CommerceOrderService orders;
    private final CommerceJobLease leases;
    private final byte[] signingKey;
    private final boolean workerEnabled;

    public CommercePaymentService(JdbcTemplate jdbc, TransactionTemplate transactions,
            CommerceOrderService orders, CommerceJobLease leases,
            @Value("${citycopilot.commerce.payment-signing-secret:}") String signingSecret,
            @Value("${citycopilot.commerce.stream-worker-enabled:${citycopilot.commerce.stream-enabled:true}}") boolean workerEnabled) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.orders = orders;
        this.leases = leases;
        this.signingKey = signingSecret.getBytes(StandardCharsets.UTF_8);
        this.workerEnabled = workerEnabled;
    }

    public record Callback(String paymentId, String transactionId, int amount) {}

    public OrderView start(long orderId, long userId, Integer payType, boolean admin, boolean merchant) {
        requireCustomer(merchant, admin);
        requireSigningKey();
        if (payType != null && payType != 1) {
            throw new CommerceApiException("COMMERCE_PAYMENT_TYPE_UNSUPPORTED", "模拟支付仅支持支付方式 1", 400);
        }
        if ("processing".equals(orders.order(orderId, userId, admin, false).state())) {
            throw new CommerceApiException("COMMERCE_ORDER_PROCESSING", "订单仍在生成中，请等待订单确认后再支付", 409);
        }
        transactions.executeWithoutResult(tx -> {
            int state = lockOrder(orderId);
            if (state == 2) return;
            if (state != 1) throw stateConflict();
            jdbc.update("""
                    INSERT INTO commerce_payment(payment_id,order_id,amount,pay_type,status,callback_due_at)
                    SELECT ?,id,pay_value_snapshot,1,'PENDING',CURRENT_TIMESTAMP(3)+INTERVAL 1 SECOND
                    FROM commerce_order WHERE id=?
                    ON DUPLICATE KEY UPDATE order_id=VALUES(order_id)
                    """, UUID.randomUUID().toString(), orderId);
        });
        return orders.order(orderId, userId, admin, false);
    }

    public OrderView refund(long orderId, long userId, boolean admin, boolean merchant) {
        requireCustomer(merchant, admin);
        OrderView before = orders.order(orderId, userId, admin, false);
        if (before.status() == 6) return before;
        return orders.transition(orderId, userId, "refund", admin, false);
    }

    public String callback(Callback callback, String signature) {
        if (callback.paymentId() == null || !callback.paymentId().matches("[A-Za-z0-9:-]{1,64}")
                || callback.transactionId() == null || !callback.transactionId().matches("[A-Za-z0-9:-]{1,96}")
                || callback.amount() < 0) {
            throw new CommerceApiException("COMMERCE_PAYMENT_CALLBACK_INVALID", "支付回调字段不合法，请核对支付单号、流水号和金额", 400);
        }
        if (signature == null || !MessageDigest.isEqual(sign(callback).getBytes(StandardCharsets.US_ASCII),
                signature.getBytes(StandardCharsets.US_ASCII))) {
            throw new CommerceApiException("COMMERCE_PAYMENT_SIGNATURE_INVALID", "支付回调验签失败，请核对渠道签名密钥和回调内容", 401);
        }
        try {
            return transactions.execute(tx -> {
                Map<String, Object> payment = lockPayment(callback.paymentId());
                if (((Number) payment.get("amount")).intValue() != callback.amount()) {
                    throw new CommerceApiException("COMMERCE_PAYMENT_AMOUNT_MISMATCH", "支付回调金额与订单成交金额不一致", 409);
                }
                String previousTransaction = (String) payment.get("channel_transaction_id");
                if (previousTransaction != null) {
                    if (!previousTransaction.equals(callback.transactionId())) {
                        throw new CommerceApiException("COMMERCE_PAYMENT_TRANSACTION_CONFLICT", "支付单已绑定其他渠道流水，请核对渠道记录", 409);
                    }
                    return (String) payment.get("callback_result");
                }
                long orderId = ((Number) payment.get("order_id")).longValue();
                int changed = jdbc.update("""
                        UPDATE commerce_order SET status=2,pay_type=?,pay_time=CURRENT_TIMESTAMP(3),version=version+1
                        WHERE id=? AND status=1
                        """, payment.get("pay_type"), orderId);
                String result = changed == 1 ? "PAID" : "REFUND_PENDING";
                if (changed == 1) {
                    long owner = jdbc.queryForObject("SELECT user_id FROM commerce_order WHERE id=?", Long.class, orderId);
                    orders.appendStateEvent(orderId, owner, "pay", 2);
                }
                jdbc.update("""
                        UPDATE commerce_payment SET channel_transaction_id=?,callback_result=?,status=?,callback_due_at=NULL
                        WHERE payment_id=?
                        """, callback.transactionId(), result, result, callback.paymentId());
                return result;
            });
        } catch (DuplicateKeyException conflict) {
            throw new CommerceApiException("COMMERCE_PAYMENT_TRANSACTION_CONFLICT", "渠道流水已用于另一支付单，请核对渠道记录", 409);
        }
    }

    String sign(Callback callback) {
        requireSigningKey();
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(signingKey, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal((callback.paymentId() + "\n" + callback.transactionId()
                    + "\n" + callback.amount()).getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException error) {
            throw new IllegalStateException("Unable to sign simulated payment callback", error);
        }
    }

    @Scheduled(fixedDelay = 1000, initialDelay = 1000)
    public void processPending() {
        if (!workerEnabled) return;
        leases.acquire("simulated-payments").ifPresent(lease -> {
            List<Callback> due = jdbc.query("""
                    SELECT payment_id,amount FROM commerce_payment
                    WHERE callback_result IS NULL AND callback_due_at<=CURRENT_TIMESTAMP(3)
                    ORDER BY callback_due_at,payment_id LIMIT 100
                    """, (row, index) -> new Callback(row.getString("payment_id"),
                    "sim:" + row.getString("payment_id"), row.getInt("amount")));
            RuntimeException firstFailure = null;
            for (Callback callback : due) {
                try {
                    lease.runFenced(() -> callback(callback, sign(callback)));
                } catch (CommerceJobLease.LeaseLostException lost) {
                    throw lost;
                } catch (RuntimeException failure) {
                    var identified = new IllegalStateException("Simulated payment callback failed: " + callback.paymentId(), failure);
                    if (firstFailure == null) firstFailure = identified;
                    else firstFailure.addSuppressed(identified);
                }
            }
            List<String> refunds = jdbc.queryForList("""
                    SELECT p.payment_id FROM commerce_payment p JOIN commerce_order o ON o.id=p.order_id
                    WHERE p.status='REFUND_PENDING' AND o.status=4 ORDER BY p.payment_id LIMIT 100
                    """, String.class);
            for (String paymentId : refunds) {
                try {
                    lease.runFenced(() -> {
                        lockPayment(paymentId);
                        jdbc.update("UPDATE commerce_payment SET status='REFUNDED' WHERE payment_id=? AND status='REFUND_PENDING'", paymentId);
                    });
                } catch (CommerceJobLease.LeaseLostException lost) {
                    throw lost;
                } catch (RuntimeException failure) {
                    var identified = new IllegalStateException("Simulated payment refund failed: " + paymentId, failure);
                    if (firstFailure == null) firstFailure = identified;
                    else firstFailure.addSuppressed(identified);
                }
            }
            if (firstFailure != null) throw firstFailure;
        });
    }

    @Scheduled(cron = "0 0 3 * * *", zone = "Asia/Shanghai")
    public void reconcile() {
        if (!workerEnabled) return;
        leases.acquire("payment-reconciliation").ifPresent(lease -> {
            String after = "";
            while (true) {
                List<String> ids = jdbc.queryForList("SELECT payment_id FROM commerce_payment WHERE payment_id>? ORDER BY payment_id LIMIT 100",
                        String.class, after);
                if (ids.isEmpty()) return;
                for (String id : ids) lease.runFenced(() -> {
                    lockPayment(id);
                    jdbc.update("UPDATE commerce_payment SET reconciliation_issue=?,reconciled_at=CURRENT_TIMESTAMP(3) WHERE payment_id=?",
                            review(id, true).issue(), id);
                });
                after = ids.get(ids.size() - 1);
            }
        });
    }

    public List<PaymentReview> issues() {
        return jdbc.queryForList("""
                SELECT p.payment_id FROM commerce_payment p JOIN commerce_order o ON o.id=p.order_id
                WHERE NOT ((p.status='PENDING' AND o.status=1) OR (p.status='CLOSED' AND o.status=4)
                    OR (p.status='PAID' AND o.status IN (2,3)) OR (p.status='REFUND_PENDING' AND o.status IN (4,5))
                    OR (p.status='REFUNDED' AND o.status IN (4,6)))
                ORDER BY p.updated_at,p.payment_id LIMIT 100
                """, String.class).stream().map(this::review).toList();
    }

    public PaymentReview review(String paymentId) {
        return review(paymentId, false);
    }

    private PaymentReview review(String paymentId, boolean locked) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT p.*,o.status AS order_status,o.version AS order_version
                FROM commerce_payment p JOIN commerce_order o ON o.id=p.order_id WHERE p.payment_id=?
                """ + (locked ? " FOR UPDATE" : ""), paymentId);
        if (rows.isEmpty()) throw new CommerceApiException("COMMERCE_PAYMENT_NOT_FOUND", "支付单不存在", 404);
        Map<String, Object> row = rows.get(0);
        int orderState = ((Number) row.get("order_status")).intValue();
        String state = (String) row.get("status");
        boolean consistent = switch (state) {
            case "PENDING" -> orderState == 1;
            case "CLOSED" -> orderState == 4;
            case "PAID" -> orderState == 2 || orderState == 3;
            case "REFUND_PENDING" -> orderState == 4 || orderState == 5;
            case "REFUNDED" -> orderState == 4 || orderState == 6;
            default -> false;
        };
        String facts = paymentId + "|" + row.get("order_id") + "|" + state + "|" + orderState + "|"
                + row.get("order_version") + "|" + row.get("amount") + "|" + row.get("channel_transaction_id") + "|" + row.get("callback_result");
        try {
            return new PaymentReview(paymentId, String.valueOf(row.get("order_id")), state, orderState,
                    ((Number) row.get("amount")).intValue(), (String) row.get("channel_transaction_id"),
                    consistent ? null : "PAYMENT_ORDER_STATE_MISMATCH",
                    HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(facts.getBytes(StandardCharsets.UTF_8))));
        } catch (GeneralSecurityException error) {
            throw new IllegalStateException("Unable to hash payment evidence", error);
        }
    }

    public PaymentReview repair(String paymentId, String expectedEvidenceHash) {
        return transactions.execute(tx -> {
            Map<String, Object> payment = lockPayment(paymentId);
            PaymentReview evidence = review(paymentId, true);
            if (!evidence.evidenceHash().equals(expectedEvidenceHash)) {
                throw new CommerceApiException("COMMERCE_STALE_EVIDENCE", "支付状态已变化，请重新读取对账证据后修复", 409);
            }
            if (evidence.issue() == null) return evidence;
            if ("REFUNDED".equals(payment.get("status"))) {
                throw new CommerceApiException("COMMERCE_PAYMENT_REPAIR_UNSUPPORTED", "支付单已退款，不能恢复收款状态，请核对订单与库存恢复记录", 409);
            }
            if (payment.get("channel_transaction_id") == null) {
                throw new CommerceApiException("COMMERCE_PAYMENT_REPAIR_REQUIRES_CHANNEL_EVIDENCE", "缺少渠道收款记录，不能自动修复，请先核对模拟渠道回调", 409);
            }
            String target = switch (evidence.orderStatus()) {
                case 2, 3 -> "PAID";
                case 4, 5 -> "REFUND_PENDING";
                case 6 -> "REFUNDED";
                default -> throw new CommerceApiException("COMMERCE_PAYMENT_REPAIR_UNSUPPORTED", "待支付订单与渠道记录冲突，请先核对回调处理记录", 409);
            };
            jdbc.update("UPDATE commerce_payment SET status=?,reconciliation_issue=NULL,reconciled_at=CURRENT_TIMESTAMP(3) WHERE payment_id=?",
                    target, paymentId);
            return review(paymentId, true);
        });
    }

    private Map<String, Object> lockPayment(String paymentId) {
        List<Long> ids = jdbc.queryForList("SELECT order_id FROM commerce_payment WHERE payment_id=?", Long.class, paymentId);
        if (ids.isEmpty()) throw new CommerceApiException("COMMERCE_PAYMENT_NOT_FOUND", "支付单不存在", 404);
        lockOrder(ids.get(0));
        return jdbc.queryForMap("SELECT * FROM commerce_payment WHERE payment_id=? FOR UPDATE", paymentId);
    }

    private int lockOrder(long orderId) {
        return jdbc.queryForObject("SELECT status FROM commerce_order WHERE id=? FOR UPDATE", Integer.class, orderId);
    }

    private void requireSigningKey() {
        if (signingKey.length < 32) throw new CommerceApiException("COMMERCE_PAYMENT_NOT_CONFIGURED",
                "模拟支付尚未配置，请管理员设置至少 32 字节的 CITYCOPILOT_PAYMENT_SIGNING_SECRET 后重试", 503);
    }

    private static void requireCustomer(boolean merchant, boolean admin) {
        if (merchant && !admin) throw new CommerceApiException("COMMERCE_MERCHANT_ACTION_FORBIDDEN", "商家不能支付或退款顾客订单", 403);
    }

    private static CommerceApiException stateConflict() {
        return new CommerceApiException("COMMERCE_ORDER_STATE_CONFLICT", "当前订单不能发起支付，请刷新订单状态", 409);
    }
}
