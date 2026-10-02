package com.citycopilot.commerce;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 基于 MySQL 的持久化号段 ID 生成器。
 *
 * <p>短事务先独占并预留一段 ID，进程再从内存逐个发放。实例崩溃最多浪费未用尾段，
 * 不会因 Redis 故障切换而重新发出已经准入的订单 ID。</p>
 */
@Component
final class CommerceOrderIdGenerator {
    /** 锁定并推进持久化号段游标。 */
    private final JdbcTemplate jdbc;
    /** 保证每次号段预留在独立数据库事务内完成。 */
    private final TransactionTemplate transactions;
    /** 单次从 MySQL 预留的 ID 数量。 */
    private final int segmentSize;
    /** 按订单、Voucher 等命名空间隔离的本地号段。 */
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

    /** @return 下一个全局唯一的订单 ID */
    long nextId() {
        return nextId("order");
    }

    /** @return 下一个全局唯一的 Voucher ID */
    long nextVoucherId() {
        return nextId("voucher");
    }

    /**
     * 从指定命名空间的本地号段取号；号段耗尽时自动向 MySQL 申请下一段。
     *
     * @param namespace ID 业务命名空间
     * @return 该命名空间中的下一个 ID
     */
    private long nextId(String namespace) {
        return segments.computeIfAbsent(namespace, ignored -> new LocalSegment())
                .next(namespace);
    }

    /**
     * 在短事务中锁定号段行并推进上界。
     *
     * @param namespace ID 业务命名空间
     * @return 新号段的包含式上界
     */
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
            // FOR UPDATE 串行化同一命名空间的号段分配，防止多实例拿到重叠区间。
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
            // 条件更新再次确认游标未被改写；异常行数表示锁或数据状态不符合预期。
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
        /** 下一次将发放的 ID。 */
        private long next;
        /** 当前内存号段尚未发放的 ID 数量。 */
        private int remaining;

        /**
         * 串行发放当前命名空间的本地 ID，并在耗尽时申请新号段。
         *
         * @param namespace ID 业务命名空间
         * @return 下一个 ID
         */
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
