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
 * 基于 MySQL 的店铺投影租约服务，并为每次成功获取分配单调递增的 fencing token。
 *
 * <p>增量 Projector 与 GEO/搜索全量重建共享该租约，保证所有 Core 副本中只有一个外部投影写者。</p>
 */
@Service
public class ShopProjectionLeaseService {

    /** 增量投影与全量重建共用的写者租约名。 */
    public static final String WRITER_LEASE = "shop-projection-writer";

    /** 关闭 Outbox 水位和发布新代际时使用的短时切换屏障。 */
    public static final String CUTOVER_BARRIER = "shop-projection-cutover";

    /** 执行租约行的原子 SQL 更新和加锁查询。 */
    private final JdbcTemplate jdbc;

    /** 在数据库事务内持有行锁并执行外部指针发布。 */
    private final TransactionTemplate transactions;

    /** 当前 Core 进程的随机租约所有者标识。 */
    private final String ownerId = UUID.randomUUID().toString();

    /**
     * @param jdbc 数据库访问模板
     * @param transactionManager 事务管理器，用于围绕 fencing 行锁建立临界区
     */
    @Autowired
    public ShopProjectionLeaseService(JdbcTemplate jdbc, PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    /**
     * 无事务模板的兼容构造器，供仓储级测试使用。
     *
     * @param jdbc 数据库访问模板
     */
    ShopProjectionLeaseService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.transactions = null;
    }

    /**
     * 尝试获取已过期的指定租约，并递增 fencing token。
     *
     * @param leaseName 稳定租约名
     * @param ttl 本次租约有效期，必须为正
     * @return 获取成功后的租约句柄；被其他实例持有时为空
     */
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

    /**
     * 一次已获取的租约句柄，封装续租、持有校验、受 fence 保护的执行和释放。
     */
    public final class Lease implements AutoCloseable {

        /** 租约行主键。 */
        private final String leaseName;

        /** 获取时写入数据库的所有者标识。 */
        private final String leaseOwner;

        /** 本次获取的单调 fencing token，用于拒绝旧写者。 */
        private final long fencingToken;

        /** 每次续租恢复的有效期。 */
        private final Duration ttl;

        /** 防止重复关闭时再次更新租约行。 */
        private boolean closed;

        private Lease(String leaseName, String leaseOwner, long fencingToken, Duration ttl) {
            this.leaseName = leaseName;
            this.leaseOwner = leaseOwner;
            this.fencingToken = fencingToken;
            this.ttl = ttl;
        }

        /** @return 本次租约的 fencing token */
        public long fencingToken() {
            return fencingToken;
        }

        /**
         * 校验当前所有者、token 和过期时间仍匹配。
         *
         * @throws ProjectionLeaseLostException 租约已过期或已被后继写者取得时抛出
         */
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

        /**
         * 在租约仍有效时延长过期时间。
         *
         * @throws ProjectionLeaseLostException 不能续租旧 token 时抛出
         */
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
         * 持有租约行锁期间发布外部指针，例如搜索别名或 Redis 活跃代际。
         *
         * <p>指针切换完成前，后继写者无法取得更高 fencing token。</p>
         *
         * @param publication 外部指针发布动作
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
         * 同时持有写者 fence 和切换屏障，关闭一个完整 Outbox 水位。
         *
         * <p>Outbox 追加持有屏障共享锁，因此本事务会等待已追加变更完成，并短暂阻塞新的追加。</p>
         *
         * @param operation 需要在封闭水位下执行的操作
         * @param <T> 操作返回类型
         * @return 操作结果
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

        /** 在事务行锁下再次确认租约仍归当前 token 所有。 */
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

        /**
         * 尽力提前释放当前租约；where 条件确保不会清除后继写者的租约。
         */
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

    /**
     * 将有效期转换为 MySQL TIMESTAMPADD 使用的正微秒数。
     *
     * @param ttl 租约有效期
     * @return 至少为 1 的微秒数
     */
    private static long positiveMicros(Duration ttl) {
        if (ttl == null || ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException("lease ttl must be positive");
        }
        return Math.max(1L, ttl.toNanos() / 1_000L);
    }

    /** 租约过期或 fencing token 已被后继写者取代时抛出的异常。 */
    public static final class ProjectionLeaseLostException extends IllegalStateException {
        ProjectionLeaseLostException(String leaseName, long fencingToken) {
            super("Projection lease lost: " + leaseName + "#" + fencingToken);
        }
    }
}
