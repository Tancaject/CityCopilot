package com.citycopilot.commerce;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 消费者与订单事务共用 Recovery claim 所有权判断，沿用调用方的数据库事务。 */
@Repository
public class CommerceOrderRecoveryStore {
    /** Recovery 表中的显式状态机阶段。 */
    static final String PHASE_RECEIVED = "RECEIVED";
    /** 订单落库失败，仍需执行 Redis 准入补偿。 */
    static final String PHASE_COMPENSATION_REQUIRED = "COMPENSATION_REQUIRED";
    /** 订单、预留和 Outbox 已在 MySQL 事务中持久化。 */
    static final String PHASE_ORDER_CREATED = "ORDER_CREATED";
    /** 毒消息已写入死信记录，不再尝试创建订单。 */
    static final String PHASE_DEAD_LETTERED = "DEAD_LETTERED";
    /** 自动恢复无法安全判定，需要人工修复。 */
    static final String PHASE_REPAIR_REQUIRED = "REPAIR_REQUIRED";
    /** 取消或退款后的 Redis 库存恢复尚未完成。 */
    static final String PHASE_RESTORE_PENDING = "RESTORE_PENDING";
    /** Redis 库存恢复已幂等完成。 */
    static final String PHASE_RESTORED = "RESTORED";

    private final JdbcTemplate jdbc;

    public CommerceOrderRecoveryStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    /** 续期并确认 claim token 与阶段仍匹配；不匹配时抛出 LostRecoveryClaim。 */
    void requireOwnedPhase(String sourceId, String claimToken, String phase) {
        int owned = jdbc.update("""
                UPDATE commerce_order_recovery
                SET claimed_until=TIMESTAMPADD(SECOND, 120, CURRENT_TIMESTAMP(3))
                WHERE source_id=? AND claim_token=? AND phase=?
                """, sourceId, claimToken, phase);
        if (owned != 1) throw new LostRecoveryClaim();
    }

    static final class LostRecoveryClaim extends RuntimeException {}
}
