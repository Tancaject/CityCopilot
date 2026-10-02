package com.citycopilot.core.event;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 使用 MySQL Inbox 表对跨服务事件做去重、载荷冲突隔离和聚合版本水位判断。
 *
 * <p>事件登记、版本推进和最终状态更新在同一事务中完成，数据库唯一键是重复投递的最终边界。</p>
 */
@Repository
public class InboxRepository {
    /** 执行 Inbox 与聚合版本水位 SQL。 */
    private final JdbcTemplate jdbcTemplate;

    /** @param jdbcTemplate Core 数据库访问入口 */
    public InboxRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 原子登记并分类一条事件，不负责执行具体业务投影。
     *
     * @param consumerGroup 消费者组，参与 Inbox 去重范围
     * @param eventId 事件唯一标识
     * @param payloadHash 原始消息载荷哈希，用于识别 eventId 冲突
     * @param eventType 事件类型
     * @param aggregateType 聚合类型
     * @param aggregateId 聚合主键
     * @param aggregateVersion 聚合单调版本
     * @return PROCESSED、DUPLICATE、STALE 或 QUARANTINED
     */
    @Transactional
    public InboxOutcome process(
            String consumerGroup,
            String eventId,
            String payloadHash,
            String eventType,
            String aggregateType,
            String aggregateId,
            long aggregateVersion
    ) {
        // INSERT IGNORE 让数据库唯一键原子判定“首次事件”与“重复 eventId”。
        int inserted = jdbcTemplate.update("""
                INSERT IGNORE INTO processed_event(
                    consumer_group, event_id, payload_hash, event_type, aggregate_id,
                    aggregate_version, status
                ) VALUES (?, ?, ?, ?, ?, ?, 'RECEIVED')
                """,
                consumerGroup,
                eventId,
                payloadHash,
                eventType,
                aggregateId,
                aggregateVersion
        );
        if (inserted == 0) {
            List<ExistingEvent> existing = jdbcTemplate.query("""
                    SELECT payload_hash, status
                    FROM processed_event
                    WHERE consumer_group=? AND event_id=?
                    """,
                    (rs, row) -> new ExistingEvent(
                            rs.getString("payload_hash"),
                            rs.getString("status")
                    ),
                    consumerGroup,
                    eventId
            );
            if (!existing.isEmpty() && existing.get(0).payloadHash().equals(payloadHash)) {
                return InboxOutcome.DUPLICATE;
            }
            // 相同 eventId 却有不同载荷不能按普通重试处理，必须隔离以免污染投影。
            jdbcTemplate.update("""
                    UPDATE processed_event
                    SET status='QUARANTINED',
                        quarantine_reason='same eventId received with a different payload hash',
                        processed_at=CURRENT_TIMESTAMP(3)
                    WHERE consumer_group=? AND event_id=?
                    """, consumerGroup, eventId);
            return InboxOutcome.QUARANTINED;
        }

        Long currentVersion = jdbcTemplate.queryForObject("""
                SELECT MAX(current_version)
                FROM event_projection_version
                WHERE consumer_group=? AND aggregate_type=? AND aggregate_id=?
                """, Long.class, consumerGroup, aggregateType, aggregateId);
        InboxOutcome outcome = currentVersion != null && aggregateVersion < currentVersion
                ? InboxOutcome.STALE
                : InboxOutcome.PROCESSED;
        if (outcome == InboxOutcome.PROCESSED) {
            // GREATEST 保证并发或乱序消息只能向前推进聚合水位。
            jdbcTemplate.update("""
                    INSERT INTO event_projection_version(
                        consumer_group, aggregate_type, aggregate_id, current_version
                    ) VALUES (?, ?, ?, ?)
                    ON DUPLICATE KEY UPDATE
                        current_version=GREATEST(current_version, VALUES(current_version))
                    """, consumerGroup, aggregateType, aggregateId, aggregateVersion);
        }
        jdbcTemplate.update("""
                UPDATE processed_event
                SET status=?, processed_at=CURRENT_TIMESTAMP(3)
                WHERE consumer_group=? AND event_id=?
                """, outcome.name(), consumerGroup, eventId);
        return outcome;
    }

    /**
     * @param payloadHash 已登记载荷哈希
     * @param status Inbox 当前处理状态
     */
    private record ExistingEvent(String payloadHash, String status) {
    }
}
