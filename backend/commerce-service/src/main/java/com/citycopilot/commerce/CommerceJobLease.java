package com.citycopilot.commerce;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Optional;
import java.util.UUID;

/** 扫描任务跨轮保留同一写者；业务分批执行，不持有整轮扫描的数据库事务。 */
@Component
public class CommerceJobLease {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final String owner = System.getenv().getOrDefault("HOSTNAME", "local") + ":" + UUID.randomUUID();

    public CommerceJobLease(JdbcTemplate jdbc, TransactionTemplate transactions) {
        this.jdbc = jdbc;
        this.transactions = transactions;
    }

    public Optional<Lease> acquire(String job) {
        jdbc.update("INSERT IGNORE INTO commerce_job_lease(job_name) VALUES (?)", job);
        int acquired = jdbc.update("""
                UPDATE commerce_job_lease
                SET fencing_token=fencing_token+IF(owner_id=? AND lease_until>CURRENT_TIMESTAMP(3),0,1),
                    owner_id=?,lease_until=TIMESTAMPADD(SECOND,30,CURRENT_TIMESTAMP(3))
                WHERE job_name=? AND (owner_id=? OR lease_until IS NULL OR lease_until<CURRENT_TIMESTAMP(3))
                """, owner, owner, job, owner);
        if (acquired == 0) return Optional.empty();
        var tokens = jdbc.queryForList("""
                SELECT fencing_token FROM commerce_job_lease
                WHERE job_name=? AND owner_id=? AND lease_until>CURRENT_TIMESTAMP(3)
                """, Long.class, job, owner);
        return tokens.isEmpty() ? Optional.empty() : Optional.of(new Lease(job, tokens.get(0)));
    }

    public class Lease {
        private final String job;
        private final long token;

        private Lease(String job, long token) {
            this.job = job;
            this.token = token;
        }

        public void runFenced(Runnable operation) {
            transactions.executeWithoutResult(tx -> {
                // 外部 Redis 写入期间锁住租约行，后继写者不能取得更高 token 后被旧写者覆盖。
                var held = jdbc.queryForList("""
                        SELECT fencing_token FROM commerce_job_lease
                        WHERE job_name=? AND owner_id=? AND fencing_token=? AND lease_until>CURRENT_TIMESTAMP(3)
                        FOR UPDATE
                        """, Long.class, job, owner, token);
                if (held.isEmpty()) throw new LeaseLostException(job);
                jdbc.update("""
                        UPDATE commerce_job_lease SET lease_until=TIMESTAMPADD(SECOND,30,CURRENT_TIMESTAMP(3))
                        WHERE job_name=? AND owner_id=? AND fencing_token=?
                        """, job, owner, token);
                operation.run();
            });
        }
    }

    static final class LeaseLostException extends IllegalStateException {
        private LeaseLostException(String job) {
            super("Commerce task lease lost: " + job);
        }
    }
}
