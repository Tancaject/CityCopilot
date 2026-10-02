package com.citycopilot.commerce.pricing;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Collections;

/** 仅回收过期且未被交易消费的报价；不参与预约、优惠券或幂等身份的保留策略。 */
@Component
@ConditionalOnProperty(prefix = "citycopilot.booking.quote-cleanup", name = "enabled", havingValue = "true")
public class BookingQuoteCleanup {
    private static final Logger log = LoggerFactory.getLogger(BookingQuoteCleanup.class);
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final int retentionDays;
    private final int batchSize;

    public BookingQuoteCleanup(JdbcTemplate jdbc, TransactionTemplate transactions,
            @Value("${citycopilot.booking.quote-cleanup.retention-days:-1}") int retentionDays,
            @Value("${citycopilot.booking.quote-cleanup.batch-size:100}") int batchSize) {
        if (retentionDays < 0) throw new IllegalArgumentException(
                "Quote cleanup requires citycopilot.booking.quote-cleanup.retention-days >= 0; set the retention period after expiry before enabling cleanup");
        if (batchSize < 1 || batchSize > 500) throw new IllegalArgumentException(
                "citycopilot.booking.quote-cleanup.batch-size must be between 1 and 500");
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.retentionDays = retentionDays;
        this.batchSize = batchSize;
        log.info("Booking quote cleanup enabled: retentionDaysAfterExpiry={}, batchSize={}", retentionDays, batchSize);
    }

    @Scheduled(initialDelayString = "${citycopilot.booking.quote-cleanup.interval-ms:60000}",
            fixedDelayString = "${citycopilot.booking.quote-cleanup.interval-ms:60000}")
    public CleanupResult cleanup() {
        CleanupResult result = transactions.execute(tx -> {
            LocalDateTime cutoff = jdbc.queryForObject(
                    "SELECT TIMESTAMPADD(DAY, ?, UTC_TIMESTAMP(3))", LocalDateTime.class, -retentionDays);
            // 只锁本批报价。正在暂留/改期的报价交给业务事务，不等待它或反向锁资源、名额和券。
            var ids = jdbc.queryForList("""
                    SELECT id FROM commerce_booking_quote
                    WHERE consumed_booking_id IS NULL AND expires_at < ?
                    ORDER BY expires_at,id LIMIT ? FOR UPDATE SKIP LOCKED
                    """, String.class, cutoff, batchSize);
            int deleted = ids.isEmpty() ? 0 : jdbc.update(
                    "DELETE FROM commerce_booking_quote WHERE id IN ("
                            + String.join(",", Collections.nCopies(ids.size(), "?")) + ")", ids.toArray());
            return new CleanupResult(cutoff.toInstant(ZoneOffset.UTC), batchSize, deleted);
        });
        if (result.deleted() > 0) log.info("Booking quote cleanup: cutoff={}, batchSize={}, deleted={}",
                result.cutoff(), result.batchSize(), result.deleted());
        return result;
    }

    public record CleanupResult(Instant cutoff, int batchSize, int deleted) {}
}
