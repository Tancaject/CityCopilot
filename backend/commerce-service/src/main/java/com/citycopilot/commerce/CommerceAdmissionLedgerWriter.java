package com.citycopilot.commerce;

import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * 准入账本两次高频写（PREPARED 插入、PREPARED→ACCEPTED）的应用层组提交。
 *
 * <p>每次 MySQL 提交都要刷 redo 与 binlog，逐条自动提交时刷盘排队决定了秒杀写入上限。请求线程把写操作
 * 放进队列并阻塞到所在批次提交；刷写线程每次取走当前排队的全部操作，用一条多行语句、一次提交完成。
 * 不设攒批等待：低负载时每批一条，负载越高批越大。调用方返回时该行已持久化，语义与逐条提交相同。</p>
 *
 * <p>单条批次执行原有的单行语句。整批因死锁被回滚时拆开逐条重做，单条失败只影响它自己的调用方；
 * 其他失败（取连接超时、锁等待超时、连接中断）是系统性的，逐条重做只会把等待放大 N 倍，整批直接失败，
 * 调用方按原有语义返回可用同一幂等键重试的 503。批内按 order_id 升序，与 Worker 批量建单
 * {@code FOR UPDATE} 的加锁顺序一致；批量 ACCEPTED 仍可能与“Worker 持账本行等券行、单条落单持券行等账本行”
 * 构成死锁环，由 InnoDB 选一方回滚，本类按死锁拆开重做。</p>
 *
 * <p>调用方不得在持有账本行锁的事务内调用：刷写线程会等待调用方的锁，而调用方在等刷写结果，
 * InnoDB 看不到这种跨线程等待，只能等到锁等待超时。</p>
 */
@Component
final class CommerceAdmissionLedgerWriter {
    private static final Logger log = LoggerFactory.getLogger(CommerceAdmissionLedgerWriter.class);
    /** 每批刷写耗时与批大小的指标名。 */
    private static final String METRIC = "citycopilot.commerce.admission.ledger.flush";
    private static final int MYSQL_DEADLOCK = 1213;

    private final JdbcTemplate jdbc;
    private final MeterRegistry metrics;
    /** 一条多行语句最多携带的写操作数。 */
    private final int maxBatch;
    /** 等待刷写的操作；长度受 Commerce 入口并发上限约束，不另设上限。 */
    private final LinkedBlockingQueue<Write> queue = new LinkedBlockingQueue<>();
    private final List<Thread> flushers = new ArrayList<>();
    private volatile boolean running = true;

    @Autowired
    CommerceAdmissionLedgerWriter(
            JdbcTemplate jdbc,
            MeterRegistry metrics,
            @Value("${citycopilot.commerce.admission-ledger.flushers:8}") int flusherCount,
            @Value("${citycopilot.commerce.admission-ledger.max-batch:64}") int maxBatch
    ) {
        this(jdbc, metrics, maxBatch);
        if (flusherCount < 1 || flusherCount > 32) {
            throw new IllegalArgumentException("Admission ledger flushers must be 1..32");
        }
        for (int i = 0; i < flusherCount; i++) {
            Thread flusher = new Thread(this::flushLoop, "admission-ledger-" + i);
            flusher.setDaemon(true);
            flusher.start();
            flushers.add(flusher);
        }
    }

    private CommerceAdmissionLedgerWriter(JdbcTemplate jdbc, MeterRegistry metrics, int maxBatch) {
        if (maxBatch < 1 || maxBatch > 1_000) {
            throw new IllegalArgumentException("Admission ledger batch size must be 1..1000");
        }
        this.jdbc = jdbc;
        this.metrics = metrics;
        this.maxBatch = maxBatch;
    }

    /** 测试用：不启动刷写线程，由测试调用 {@link #flushPending()} 决定批次边界。 */
    static CommerceAdmissionLedgerWriter manual(JdbcTemplate jdbc, MeterRegistry metrics, int maxBatch) {
        return new CommerceAdmissionLedgerWriter(jdbc, metrics, maxBatch);
    }

    /** 插入 PREPARED 身份；同一用户幂等键已存在时被忽略，调用方须重读确认获胜者。返回时所在批次已提交。 */
    void insertPrepared(Identity identity) {
        submit(Kind.PREPARE, identity);
    }

    /**
     * 把身份完全一致的 PREPARED 行转为 ACCEPTED。返回时所在批次已提交。
     *
     * @return true 表示确定由本次写入改动了这一行；false 表示未改动或批内无法区分，须由调用方重读确认
     */
    boolean markAccepted(Identity identity) {
        return submit(Kind.ACCEPT, identity);
    }

    /** 当前排队的操作数。 */
    int pending() {
        return queue.size();
    }

    /** 在调用线程中刷完当前排队的操作；停机时与测试中使用。 */
    void flushPending() {
        List<Write> batch = new ArrayList<>();
        while (queue.drainTo(batch, maxBatch) > 0) {
            flush(batch);
            batch.clear();
        }
    }

    /** 停止刷写线程，并刷完停机前已入队的操作。 */
    @PreDestroy
    void close() {
        running = false;
        try {
            for (Thread flusher : flushers) flusher.join(TimeUnit.SECONDS.toMillis(10));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        flushPending();
    }

    private boolean submit(Kind kind, Identity identity) {
        Write write = new Write(kind, identity, new CompletableFuture<>());
        queue.add(write);
        // 停机后不再有刷写线程，入队者自己刷，保证每个操作都有人执行。
        if (!running) flushPending();
        try {
            return write.result().join();
        } catch (CompletionException failure) {
            if (failure.getCause() instanceof RuntimeException runtime) throw runtime;
            throw failure;
        }
    }

    private void flushLoop() {
        List<Write> batch = new ArrayList<>(maxBatch);
        while (running || !queue.isEmpty()) {
            try {
                Write first = queue.poll(100, TimeUnit.MILLISECONDS);
                if (first == null) continue;
                batch.add(first);
                queue.drainTo(batch, maxBatch - 1);
                flush(batch);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            } catch (Throwable unexpected) {
                // 刷写线程不能因意外错误静默退出，否则本批调用方会永久阻塞。
                batch.forEach(write -> write.result().completeExceptionally(unexpected));
                log.error("Admission ledger flusher failed unexpectedly", unexpected);
            } finally {
                batch.clear();
            }
        }
    }

    private void flush(List<Write> batch) {
        List<Write> prepares = batch.stream().filter(write -> write.kind() == Kind.PREPARE).toList();
        List<Write> accepts = batch.stream().filter(write -> write.kind() == Kind.ACCEPT).toList();
        if (!prepares.isEmpty()) execute("prepare", prepares, this::insertPreparedRows);
        if (!accepts.isEmpty()) execute("accept", accepts, this::updateAcceptedRows);
    }

    /** 整批执行；整批因死锁回滚时逐条重做，让每个调用方得到只属于自己的结果。 */
    private void execute(String operation, List<Write> writes, Function<List<Identity>, Boolean> statement) {
        Timer.Sample sample = Timer.start(metrics);
        String outcome = "committed";
        try {
            boolean certain = statement.apply(writes.stream().map(Write::identity).toList());
            writes.forEach(write -> write.result().complete(certain));
        } catch (RuntimeException failure) {
            if (writes.size() == 1 || !deadlock(failure)) {
                outcome = "failed";
                writes.forEach(write -> write.result().completeExceptionally(failure));
            } else {
                outcome = "split";
                log.warn("Admission ledger {} batch of {} lost a deadlock; retrying each write separately",
                        operation, writes.size(), failure);
                for (Write write : writes) {
                    try {
                        write.result().complete(statement.apply(List.of(write.identity())));
                    } catch (RuntimeException single) {
                        write.result().completeExceptionally(single);
                    }
                }
            }
        } finally {
            sample.stop(Timer.builder(METRIC).tag("operation", operation).tag("outcome", outcome).register(metrics));
            DistributionSummary.builder(METRIC + ".size").tag("operation", operation).register(metrics)
                    .record(writes.size());
        }
    }

    /** MySQL 1213：死锁检测选中本语句回滚，是行间冲突；拆开后各行加锁范围变小，可以各自完成。 */
    private static boolean deadlock(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql && sql.getErrorCode() == MYSQL_DEADLOCK) return true;
        }
        return false;
    }

    private boolean insertPreparedRows(List<Identity> identities) {
        List<Identity> rows = sorted(identities);
        List<Object> arguments = new ArrayList<>();
        for (Identity row : rows) {
            arguments.addAll(List.of(row.orderId(), row.userId(), row.idempotencyKey(), row.voucherId(),
                    row.requestFingerprint(), row.routingGeneration(), row.routingShardCount()));
        }
        jdbc.update("""
                INSERT IGNORE INTO commerce_admission_ledger(
                    order_id,user_id,idempotency_key,voucher_id,request_fingerprint,
                    routing_generation,physical_shard_count,status
                ) VALUES %s
                """.formatted(String.join(",", Collections.nCopies(rows.size(), "(?,?,?,?,?,?,?,'PREPARED')"))),
                arguments.toArray());
        return true;
    }

    private boolean updateAcceptedRows(List<Identity> identities) {
        List<Identity> rows = sorted(identities.stream().distinct().toList());
        if (rows.size() == 1) {
            Identity row = rows.get(0);
            return jdbc.update("""
                    UPDATE commerce_admission_ledger
                    SET status='ACCEPTED',accepted_at=COALESCE(accepted_at,CURRENT_TIMESTAMP(3)),
                        rejection_code=NULL,rejection_http_status=NULL,rejection_message=NULL,
                        last_error=NULL
                    WHERE order_id=? AND user_id=? AND voucher_id=? AND idempotency_key=?
                      AND request_fingerprint=?
                      AND routing_generation=? AND physical_shard_count=?
                      AND status='PREPARED'
                    """, row.orderId(), row.userId(), row.voucherId(), row.idempotencyKey(),
                    row.requestFingerprint(), row.routingGeneration(), row.routingShardCount()) == 1
                    && identities.size() == 1;
        }
        List<Object> arguments = new ArrayList<>();
        rows.forEach(row -> arguments.add(row.orderId()));
        for (Identity row : rows) {
            arguments.addAll(List.of(row.orderId(), row.userId(), row.voucherId(), row.idempotencyKey(),
                    row.requestFingerprint(), row.routingGeneration(), row.routingShardCount()));
        }
        // 强制主键 IN：按主键升序定位和加锁，元组 IN 逐行核对完整身份。不加提示时优化器会选
        // (routing_generation,physical_shard_count,status,…) 索引做范围扫描，RR 下的间隙锁会挡住新 PREPARED 插入。
        int changed = jdbc.update("""
                UPDATE commerce_admission_ledger FORCE INDEX (PRIMARY)
                SET status='ACCEPTED',accepted_at=COALESCE(accepted_at,CURRENT_TIMESTAMP(3)),
                    rejection_code=NULL,rejection_http_status=NULL,rejection_message=NULL,
                    last_error=NULL
                WHERE order_id IN (%s)
                  AND (order_id,user_id,voucher_id,idempotency_key,request_fingerprint,
                       routing_generation,physical_shard_count) IN (%s)
                  AND status='PREPARED'
                """.formatted(CommerceOrderService.placeholders(rows.size(), 1),
                        CommerceOrderService.placeholders(rows.size(), 7)),
                arguments.toArray());
        // 只有全部命中且批内没有重复身份时，才能确定每个调用方的行都由本次写入改动。
        return changed == rows.size() && rows.size() == identities.size();
    }

    private static List<Identity> sorted(List<Identity> identities) {
        return identities.stream().sorted(Comparator.comparingLong(Identity::orderId)).toList();
    }

    private enum Kind { PREPARE, ACCEPT }

    private record Write(Kind kind, Identity identity, CompletableFuture<Boolean> result) { }

    /** 一条准入账本行的完整身份。 */
    record Identity(
            long orderId,
            long userId,
            String idempotencyKey,
            long voucherId,
            String requestFingerprint,
            String routingGeneration,
            int routingShardCount
    ) { }
}
