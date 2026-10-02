package com.citycopilot.agent.ops.v2;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collections;

/** 只分离不可再推进 Case 的过期证据正文，保留审批/执行链上的原身份与摘要。 */
@Component
@ConditionalOnProperty(prefix = "citycopilot.ops-v2.evidence-archive", name = "enabled", havingValue = "true")
public class OpsEvidenceArchive {
    private static final Logger log = LoggerFactory.getLogger(OpsEvidenceArchive.class);
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final int retentionDays;
    private final int batchSize;

    public OpsEvidenceArchive(JdbcTemplate jdbc, PlatformTransactionManager transactions,
            @Value("${citycopilot.ops-v2.evidence-archive.retention-days:-1}") int retentionDays,
            @Value("${citycopilot.ops-v2.evidence-archive.batch-size:100}") int batchSize) {
        if (retentionDays < 0) throw new IllegalArgumentException(
                "Ops evidence archival requires citycopilot.ops-v2.evidence-archive.retention-days >= 0; set the online audit window before enabling archival");
        if (batchSize < 1 || batchSize > 500) throw new IllegalArgumentException(
                "citycopilot.ops-v2.evidence-archive.batch-size must be between 1 and 500");
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(transactions);
        this.retentionDays = retentionDays;
        this.batchSize = batchSize;
        log.info("Ops evidence archival enabled: retentionDaysAfterResolution={}, batchSize={}", retentionDays, batchSize);
    }

    @Scheduled(initialDelay = 60_000, fixedDelay = 60_000)
    public void archive() {
        ArchiveResult result = transactions.execute(tx -> {
            Timestamp cutoff = jdbc.queryForObject(
                    "SELECT TIMESTAMPADD(DAY, ?, CURRENT_TIMESTAMP(3))", Timestamp.class, -retentionDays);
            var ids = jdbc.queryForList("""
                    SELECT e.id FROM ops_case c
                    JOIN ops_evidence e ON e.case_id=c.id
                    WHERE c.status IN ('SUCCEEDED','CANCELLED') AND c.resolved_at < ?
                      AND e.expires_at < CURRENT_TIMESTAMP(3) AND e.payload_archived_at IS NULL
                    ORDER BY c.resolved_at,c.id,e.id LIMIT ? FOR UPDATE SKIP LOCKED
                    """, String.class, cutoff, batchSize);
            if (ids.isEmpty()) return new ArchiveResult(cutoff.toInstant(), 0);
            String placeholders = String.join(",", Collections.nCopies(ids.size(), "?"));
            // 原文与热表指针同事务更新；保留 payload_hash，不重算计划或审批绑定。
            jdbc.update("""
                    INSERT INTO ops_evidence_payload_archive(evidence_id,payload_json,payload_sha256)
                    SELECT id,payload_json,SHA2(CAST(payload_json AS BINARY),256)
                    FROM ops_evidence WHERE id IN (
                    """ + placeholders + ")", ids.toArray());
            int archived = jdbc.update("""
                    UPDATE ops_evidence SET payload_json=NULL,payload_archived_at=CURRENT_TIMESTAMP(3)
                    WHERE id IN (
                    """ + placeholders + ")", ids.toArray());
            return new ArchiveResult(cutoff.toInstant(), archived);
        });
        if (result.archived() > 0) log.info("Ops evidence archival: cutoff={}, batchSize={}, archived={}",
                result.cutoff(), batchSize, result.archived());
    }

    private record ArchiveResult(Instant cutoff, int archived) {}
}
