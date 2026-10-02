package com.citycopilot.commerce;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collections;

/** 只移动已发布事件正文；在线保留事件身份、聚合唯一约束与发布历史。 */
@Component
@ConditionalOnProperty(prefix = "citycopilot.commerce.outbox-archive", name = "enabled", havingValue = "true")
public class CommerceOutboxArchive {
    private static final Logger log = LoggerFactory.getLogger(CommerceOutboxArchive.class);
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final int retentionDays;
    private final int batchSize;

    public CommerceOutboxArchive(JdbcTemplate jdbc, TransactionTemplate transactions,
            @Value("${citycopilot.commerce.outbox-archive.retention-days:-1}") int retentionDays,
            @Value("${citycopilot.commerce.outbox-archive.batch-size:100}") int batchSize) {
        if (retentionDays < 0) throw new IllegalArgumentException(
                "Outbox archival requires citycopilot.commerce.outbox-archive.retention-days >= 0; set the online replay window before enabling archival");
        if (batchSize < 1 || batchSize > 500) throw new IllegalArgumentException(
                "citycopilot.commerce.outbox-archive.batch-size must be between 1 and 500");
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.retentionDays = retentionDays;
        this.batchSize = batchSize;
        log.info("Commerce Outbox archival enabled: retentionDaysAfterPublication={}, batchSize={}", retentionDays, batchSize);
    }

    @Scheduled(initialDelayString = "${citycopilot.commerce.outbox-archive.interval-ms:60000}",
            fixedDelayString = "${citycopilot.commerce.outbox-archive.interval-ms:60000}")
    public ArchiveResult archive() {
        ArchiveResult result = transactions.execute(tx -> {
            Timestamp cutoff = jdbc.queryForObject(
                    "SELECT TIMESTAMPADD(DAY, ?, CURRENT_TIMESTAMP(3))", Timestamp.class, -retentionDays);
            var ids = jdbc.queryForList("""
                    SELECT event_id FROM commerce_outbox_event
                    WHERE status='PUBLISHED' AND payload_archived_at IS NULL AND published_at < ?
                    ORDER BY published_at,event_id LIMIT ? FOR UPDATE SKIP LOCKED
                    """, String.class, cutoff, batchSize);
            if (ids.isEmpty()) return new ArchiveResult(cutoff.toInstant(), batchSize, 0);
            String placeholders = String.join(",", Collections.nCopies(ids.size(), "?"));
            // 原样复制正文和摘要后才移除热表正文；两个写入共享事务，冲突或失败都不丢原文。
            jdbc.update("""
                    INSERT INTO commerce_outbox_payload_archive(event_id,payload_json,payload_sha256)
                    SELECT event_id,payload_json,SHA2(CAST(payload_json AS BINARY),256)
                    FROM commerce_outbox_event WHERE event_id IN (
                    """ + placeholders + ")", ids.toArray());
            int archived = jdbc.update("""
                    UPDATE commerce_outbox_event SET payload_json=NULL,payload_archived_at=CURRENT_TIMESTAMP(3)
                    WHERE event_id IN (
                    """ + placeholders + ")", ids.toArray());
            return new ArchiveResult(cutoff.toInstant(), batchSize, archived);
        });
        if (result.archived() > 0) log.info("Commerce Outbox archival: cutoff={}, batchSize={}, archived={}",
                result.cutoff(), result.batchSize(), result.archived());
        return result;
    }

    public record ArchiveResult(Instant cutoff, int batchSize, int archived) {}
}
