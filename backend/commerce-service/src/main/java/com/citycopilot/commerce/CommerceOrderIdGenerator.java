package com.citycopilot.commerce;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Durable MySQL hi/lo allocator.
 *
 * <p>One short database transaction reserves a configurable segment; IDs
 * inside that segment are served from memory. A crashed process only wastes
 * the unused tail and can never cause Redis failover to reissue accepted IDs.</p>
 */
@Component
final class CommerceOrderIdGenerator {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final int segmentSize;
    private final Map<String, LocalSegment> segments = new ConcurrentHashMap<>();

    CommerceOrderIdGenerator(
            JdbcTemplate jdbc,
            TransactionTemplate transactions,
            @Value("${citycopilot.commerce.id-segment-size:1024}") int segmentSize
    ) {
        if (segmentSize < 1 || segmentSize > 1_048_576) {
            throw new IllegalArgumentException("Commerce ID segment size must be 1..1048576");
        }
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.segmentSize = segmentSize;
    }

    long nextId() {
        return nextId("order");
    }

    long nextVoucherId() {
        return nextId("voucher");
    }

    private long nextId(String namespace) {
        return segments.computeIfAbsent(namespace, ignored -> new LocalSegment())
                .next(namespace);
    }

    private long allocateUpperBound(String namespace) {
        Long upperBound = transactions.execute(status -> {
            jdbc.update("""
                    INSERT IGNORE INTO commerce_id_segment(namespace,next_value)
                    VALUES (?,0)
                    """, namespace);
            Long current = jdbc.queryForObject("""
                    SELECT next_value FROM commerce_id_segment
                    WHERE namespace=?
                    FOR UPDATE
                    """, Long.class, namespace);
            if (current == null || current < 0) {
                throw new IllegalStateException("Commerce ID segment state is invalid");
            }
            final long next;
            try {
                next = Math.addExact(current, segmentSize);
            } catch (ArithmeticException overflow) {
                throw new IllegalStateException("Commerce ID sequence is exhausted", overflow);
            }
            int updated = jdbc.update("""
                    UPDATE commerce_id_segment
                    SET next_value=?,update_time=CURRENT_TIMESTAMP(3)
                    WHERE namespace=? AND next_value=?
                    """, next, namespace, current);
            if (updated != 1) {
                throw new IllegalStateException("Commerce ID segment allocation lost its database lock");
            }
            return next;
        });
        if (upperBound == null || upperBound < segmentSize) {
            throw new IllegalStateException("Unable to allocate a durable Commerce ID segment");
        }
        return upperBound;
    }

    private final class LocalSegment {
        private long next;
        private int remaining;

        synchronized long next(String namespace) {
            if (remaining == 0) {
                long allocatedUpperBound = allocateUpperBound(namespace);
                next = allocatedUpperBound - segmentSize + 1L;
                remaining = segmentSize;
            }
            long value = next;
            remaining--;
            if (remaining > 0) {
                next = Math.incrementExact(next);
            }
            return value;
        }
    }
}
