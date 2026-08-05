package com.citycopilot.core.projection;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * MySQL-backed lease with a monotonically increasing fencing token. The lease
 * is shared by the projector and full rebuilds, so only one external projection
 * writer is active across Core replicas.
 */
@Service
public class ShopProjectionLeaseService {

    public static final String WRITER_LEASE = "shop-projection-writer";
    public static final String CUTOVER_BARRIER = "shop-projection-cutover";

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final String ownerId = UUID.randomUUID().toString();

    @Autowired
    public ShopProjectionLeaseService(JdbcTemplate jdbc, PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    ShopProjectionLeaseService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.transactions = null;
    }

    public Optional<Lease> tryAcquire(String leaseName, Duration ttl) {
        jdbc.update("""
                INSERT IGNORE INTO shop_projection_lease(lease_name, fencing_token)
                VALUES (?, 0)
                """, leaseName);
        long ttlMicros = positiveMicros(ttl);
        int acquired = jdbc.update("""
                UPDATE shop_projection_lease
                SET owner_id=?, fencing_token=fencing_token+1,
                    lease_until=TIMESTAMPADD(MICROSECOND, ?, CURRENT_TIMESTAMP(3))
                WHERE lease_name=?
                  AND (lease_until IS NULL OR lease_until<CURRENT_TIMESTAMP(3))
                """, ownerId, ttlMicros, leaseName);
        if (acquired != 1) {
            return Optional.empty();
        }
        List<Long> tokens = jdbc.queryForList("""
                SELECT fencing_token FROM shop_projection_lease
                WHERE lease_name=? AND owner_id=? AND lease_until>CURRENT_TIMESTAMP(3)
                """, Long.class, leaseName, ownerId);
        return tokens.size() == 1
                ? Optional.of(new Lease(leaseName, ownerId, tokens.get(0), ttl))
                : Optional.empty();
    }

    public final class Lease implements AutoCloseable {
        private final String leaseName;
        private final String leaseOwner;
        private final long fencingToken;
        private final Duration ttl;
        private boolean closed;

        private Lease(String leaseName, String leaseOwner, long fencingToken, Duration ttl) {
            this.leaseName = leaseName;
            this.leaseOwner = leaseOwner;
            this.fencingToken = fencingToken;
            this.ttl = ttl;
        }

        public long fencingToken() {
            return fencingToken;
        }

        public void assertHeld() {
            Integer held = jdbc.queryForObject("""
                    SELECT COUNT(*) FROM shop_projection_lease
                    WHERE lease_name=? AND owner_id=? AND fencing_token=?
                      AND lease_until>CURRENT_TIMESTAMP(3)
                    """, Integer.class, leaseName, leaseOwner, fencingToken);
            if (held == null || held != 1) {
                throw new ProjectionLeaseLostException(leaseName, fencingToken);
            }
        }

        public void renew() {
            int renewed = jdbc.update("""
                    UPDATE shop_projection_lease
                    SET lease_until=TIMESTAMPADD(MICROSECOND, ?, CURRENT_TIMESTAMP(3))
                    WHERE lease_name=? AND owner_id=? AND fencing_token=?
                      AND lease_until>CURRENT_TIMESTAMP(3)
                    """, positiveMicros(ttl), leaseName, leaseOwner, fencingToken);
            if (renewed != 1) {
                throw new ProjectionLeaseLostException(leaseName, fencingToken);
            }
        }

        /**
         * Holds a row lock on the lease while publishing an external pointer
         * (ES alias or Redis active generation). A successor cannot acquire a
         * higher fencing token until the pointer switch has completed.
         */
        public void executeFenced(Runnable publication) {
            if (transactions == null) {
                assertHeld();
                publication.run();
                return;
            }
            transactions.executeWithoutResult(status -> {
                List<Long> held = jdbc.queryForList("""
                        SELECT fencing_token FROM shop_projection_lease
                        WHERE lease_name=? AND owner_id=? AND fencing_token=?
                          AND lease_until>CURRENT_TIMESTAMP(3)
                        FOR UPDATE
                        """, Long.class, leaseName, leaseOwner, fencingToken);
                if (held.size() != 1) {
                    throw new ProjectionLeaseLostException(leaseName, fencingToken);
                }
                publication.run();
            });
        }

        /**
         * Closes the outbox watermark while holding the writer fence. Outbox
         * appends take a shared lock on the barrier row, so this transaction
         * waits for already-appended changes and briefly blocks new appends.
         */
        public <T> T executeCutoverFenced(Supplier<T> operation) {
            if (transactions == null) {
                assertHeld();
                return operation.get();
            }
            return transactions.execute(status -> {
                assertHeldForUpdate();
                List<String> barrier = jdbc.queryForList("""
                        SELECT lease_name FROM shop_projection_lease
                        WHERE lease_name=?
                        FOR UPDATE
                        """, String.class, CUTOVER_BARRIER);
                if (barrier.size() != 1) {
                    throw new IllegalStateException(
                            "Projection cutover barrier is missing; apply the shop projection migration");
                }
                return operation.get();
            });
        }

        private void assertHeldForUpdate() {
            List<Long> held = jdbc.queryForList("""
                    SELECT fencing_token FROM shop_projection_lease
                    WHERE lease_name=? AND owner_id=? AND fencing_token=?
                      AND lease_until>CURRENT_TIMESTAMP(3)
                    FOR UPDATE
                    """, Long.class, leaseName, leaseOwner, fencingToken);
            if (held.size() != 1) {
                throw new ProjectionLeaseLostException(leaseName, fencingToken);
            }
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            jdbc.update("""
                    UPDATE shop_projection_lease
                    SET owner_id=NULL, lease_until=NULL
                    WHERE lease_name=? AND owner_id=? AND fencing_token=?
                    """, leaseName, leaseOwner, fencingToken);
        }
    }

    private static long positiveMicros(Duration ttl) {
        if (ttl == null || ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException("lease ttl must be positive");
        }
        return Math.max(1L, ttl.toNanos() / 1_000L);
    }

    public static final class ProjectionLeaseLostException extends IllegalStateException {
        ProjectionLeaseLostException(String leaseName, long fencingToken) {
            super("Projection lease lost: " + leaseName + "#" + fencingToken);
        }
    }
}
