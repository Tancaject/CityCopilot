package com.citycopilot.contract.commerce;

import java.util.Date;

/** Core 与 Commerce 的交易 HTTP 契约，不包含持久化实体或框架类型。 */
public final class CommerceContracts {
    private CommerceContracts() {}

    /** 受理只承诺同一订单身份；state=processing 时订单可能尚未落库。 */
    public record AdmissionAccepted(String orderId, String state, String code, String idempotencyKey) {}

    public record OrderActionRequest(String action, Integer payType) {}

    /** 订单卡片使用成交快照与整数分金额；金额包装类型保留旧响应字段缺失，订单 ID 始终是字符串。 */
    public record OrderSummary(String id, long userId, long voucherId, long shopId,
                               String shopName, String voucherTitle, String voucherSubTitle,
                               String voucherRules, Integer payValue, Integer actualValue,
                               int status, String state, String statusText, int version,
                               Date createTime, Date updateTime) {}

    /** Date 保留已有 HTTP 时间编码；inventoryRecovery 仅在返库存尚未完成时有值。 */
    public record OrderView(String id, long userId, long voucherId, long shopId,
                            String shopName, String voucherTitle, String voucherSubTitle,
                            String voucherRules, int payValue, int actualValue, Integer payType,
                            int status, String state, String statusText, int version,
                            Date createTime, Date payTime, Date useTime, Date refundTime,
                            Date updateTime, String inventoryRecovery) {}

    /** Voucher ID 保留数值编码；普通券的库存及秒杀时间窗为 null，金额单位为分。 */
    public record VoucherView(long id, long shopId, String title, String subTitle, String rules,
                              int payValue, int actualValue, int type, int status, int version,
                              Date createTime, Date updateTime, Integer stock, Date beginTime, Date endTime) {}

    public record VoucherCreated(long id) {}

    /** 包装类型保留上游字段缺失，避免适配层把缺失指标静默解释为零。 */
    public record OperationsSummary(Long orders, Long todayOrders, Long unpaidOrders, Long cancelledOrders,
                                    Long vouchers, Long seckillVouchers, Long activeVouchers) {}

    public record DeadLetterView(String recordId, String error, int retryCount,
                                 Date createdAt, Date replayedAt, String recoveryPhase) {}

    /** already_replayed 仅含 status/recordId；processing 带幂等键，completed 带 version。 */
    public record DeadLetterReplayResult(String status, String deadLetterRecordId, String orderId,
                                         String state, String code, String idempotencyKey, Integer version) {}

    public record AdmissionRepairRequeued(String status, String orderId) {}

    public record ConsistencyRepairRequest(String expectedEvidenceHash) {}

    public record ConsistencyRepairResult(long voucherId, int targetRedisStock, int rebuiltBuyerCount,
                                          int restoredRecoveryRows, String beforeEvidenceHash,
                                          String afterEvidenceHash, boolean verified) {}

    /** HTTP 状态与 Retry-After 属于传输层；后者只提供退避时间，code 与操作幂等协议共同决定重试语义。 */
    public record CommerceError(boolean success, String code, String message) {}
}
