package com.citycopilot.commerce;

import io.lettuce.core.AbstractRedisClient;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisCommandExecutionException;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import io.lettuce.core.cluster.RedisClusterClient;
import io.lettuce.core.cluster.SlotHash;
import io.lettuce.core.cluster.api.StatefulRedisClusterConnection;
import io.lettuce.core.cluster.models.partitions.RedisClusterNode;
import io.lettuce.core.codec.ByteArrayCodec;
import io.lettuce.core.protocol.ConnectionIntent;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.function.Function;

/**
 * 在同一条物理 Lettuce 主节点连接上执行 Redis 写入和 {@code WAIT} 副本确认。
 *
 * <p>Spring Data Cluster 的通用命令接口会把路由键加入参数，无法路由无键的 WAIT。本组件因此先按
 * key slot 找到当前主节点，再在其专用连接上连续执行写入和 WAIT；非 Lettuce 工厂无法保证这种
 * 连接绑定，故直接拒绝。</p>
 */
@Component
final class CommerceRedisReplicationExecutor {
    private static final Logger log = LoggerFactory.getLogger(CommerceRedisReplicationExecutor.class);
    /** Redis 写入副本确认结果与耗时的指标名。 */
    private static final String METRIC = "citycopilot.commerce.redis.replication.confirmation";
    /** 保留全局幂等身份并续期；已存在时用同值写产生可由 WAIT 覆盖的新复制偏移。 */
    private static final byte[] RESERVE_GLOBAL_IDENTITY_SCRIPT = """
            local existing = redis.call('get', KEYS[1])
            if not existing then
              redis.call('set', KEYS[1], ARGV[1], 'EX', ARGV[2])
              return ARGV[1]
            end
            redis.call('set', KEYS[1], existing, 'EX', ARGV[2])
            return existing
            """.getBytes(StandardCharsets.UTF_8);
    /**
     * 全局幂等身份的保留期。每次受理都写一个键（约 200 字节），永久保留会让 Redis 随订单量增长直到写满；
     * 默认 160MB 上限下保留 7 天只够约 75 万次受理。只有账本仍为 PREPARED 时才会读到本键，且以账本订单号
     * 重建，过期不改变身份，因此保留期只影响内存，取 1 天。
     */
    private static final long GLOBAL_IDENTITY_TTL_SECONDS = Duration.ofDays(1).toSeconds();
    /** 控制面 SET NX；同值已存在仍执行同值写以便重新确认复制。 */
    private static final byte[] SET_IF_ABSENT_SCRIPT = """
            local existing = redis.call('get', KEYS[1])
            if not existing then
              redis.call('set', KEYS[1], ARGV[1])
              return 1
            end
            if existing == ARGV[1] then
              redis.call('set', KEYS[1], existing)
              return 2
            end
            return 0
            """.getBytes(StandardCharsets.UTF_8);
    /** 控制面比较并更新；目标值已存在时同样重写并确认复制。 */
    private static final byte[] COMPARE_AND_SET_SCRIPT = """
            local current = redis.call('get', KEYS[1])
            if current == ARGV[1] then
              redis.call('set', KEYS[1], ARGV[2])
              return 1
            end
            if current == ARGV[2] then
              redis.call('set', KEYS[1], current)
              return 2
            end
            return 0
            """.getBytes(StandardCharsets.UTF_8);
    /** 仅删除预期所有者；键已不存在时用 set/delete 空操作获得复制偏移。 */
    private static final byte[] DELETE_IF_EQUALS_SCRIPT = """
            local current = redis.call('get', KEYS[1])
            if current == ARGV[1] then
              redis.call('del', KEYS[1])
              return 1
            end
            if not current then
              redis.call('set', KEYS[1], ARGV[1])
              redis.call('del', KEYS[1])
              return 2
            end
            return 0
            """.getBytes(StandardCharsets.UTF_8);

    /** 按路由键返回绑定到当前物理主节点的命令连接。 */
    private final NodeBoundConnectionProvider connections;
    /** 记录副本确认结果、拓扑重试和耗时。 */
    private final MeterRegistry metrics;
    /** 至少需要确认的副本数。 */
    private final int requiredReplicas;
    /** WAIT 最长阻塞时间。 */
    private final long waitTimeoutMillis;

    @Autowired
    CommerceRedisReplicationExecutor(
            RedisConnectionFactory connectionFactory,
            MeterRegistry metrics,
            @Value("${citycopilot.commerce.redis-required-replicas:0}") int requiredReplicas,
            @Value("${citycopilot.commerce.redis-replication-timeout-ms:1000}") long waitTimeoutMillis
    ) {
        this(lettuceProvider(connectionFactory), metrics, requiredReplicas, waitTimeoutMillis);
    }

    CommerceRedisReplicationExecutor(
            NodeBoundConnectionProvider connections,
            MeterRegistry metrics,
            int requiredReplicas,
            long waitTimeoutMillis
    ) {
        if (requiredReplicas < 0 || requiredReplicas > 16) {
            throw new IllegalArgumentException("Redis required replicas must be 0..16");
        }
        if (waitTimeoutMillis < 1 || waitTimeoutMillis > 60_000) {
            throw new IllegalArgumentException("Redis replication timeout must be 1..60000ms");
        }
        this.connections = connections;
        this.metrics = metrics;
        this.requiredReplicas = requiredReplicas;
        this.waitTimeoutMillis = waitTimeoutMillis;
    }

    /**
     * 保留用户幂等键对应的订单身份（{@link #GLOBAL_IDENTITY_TTL_SECONDS} 内有效），并确认写入复制。
     *
     * @param key 全局幂等身份键
     * @param candidateIdentity 候选订单/Voucher 身份编码
     * @return Redis 中最终保留的身份，可能来自并发先行请求
     */
    String reserveGlobalIdentity(String key, String candidateIdentity) {
        return execute("global_idempotency", key, commands -> {
            byte[] rawKey = bytes(key);
            byte[] candidate = bytes(candidateIdentity);
            byte[] existing = commands.evalValue(
                    RESERVE_GLOBAL_IDENTITY_SCRIPT,
                    new byte[][]{rawKey},
                    new byte[][]{candidate, bytes(Long.toString(GLOBAL_IDENTITY_TTL_SECONDS))}
            );
            if (existing == null) {
                throw new IllegalStateException("Redis did not retain the global admission identity");
            }
            confirmReplication(commands, "global_idempotency");
            return text(existing);
        });
    }

    /**
     * 不覆盖冲突所有者地创建控制面值；Lua 写入和 WAIT 始终使用该键当前主节点的同一连接。
     *
     * @return CHANGED、ALREADY_APPLIED 或 CONFLICT
     */
    ReplicatedMutation setIfAbsent(String operation, String key, String value) {
        return execute(operation, key, commands -> {
            Long result = commands.eval(
                    SET_IF_ABSENT_SCRIPT,
                    new byte[][]{bytes(key)},
                    new byte[][]{bytes(value)}
            );
            ReplicatedMutation mutation = ReplicatedMutation.from(result);
            if (mutation != ReplicatedMutation.CONFLICT) {
                confirmReplication(commands, operation);
            }
            return mutation;
        });
    }

    /**
     * 原子比较并修改控制值；重复已提交转换会同值写入并 WAIT，使恢复后的运维任务重新取得复制证明。
     */
    ReplicatedMutation compareAndSet(
            String operation,
            String key,
            String expected,
            String next
    ) {
        return execute(operation, key, commands -> {
            Long result = commands.eval(
                    COMPARE_AND_SET_SCRIPT,
                    new byte[][]{bytes(key)},
                    new byte[][]{bytes(expected), bytes(next)}
            );
            ReplicatedMutation mutation = ReplicatedMutation.from(result);
            if (mutation != ReplicatedMutation.CONFLICT) {
                confirmReplication(commands, operation);
            }
            return mutation;
        });
    }

    /**
     * 仅删除预期所有者并在同一连接确认最终缺失；键已缺失时执行原子 set/delete 空操作供 WAIT 覆盖。
     */
    ReplicatedMutation deleteIfEquals(
            String operation,
            String key,
            String expected
    ) {
        return execute(operation, key, commands -> {
            Long result = commands.eval(
                    DELETE_IF_EQUALS_SCRIPT,
                    new byte[][]{bytes(key)},
                    new byte[][]{bytes(expected)}
            );
            ReplicatedMutation mutation = ReplicatedMutation.from(result);
            if (mutation != ReplicatedMutation.CONFLICT) {
                confirmReplication(commands, operation);
            }
            return mutation;
        });
    }

    /**
     * 在路由键主节点执行多键 Lua，并对表示“写入成功/幂等成功”的返回码做副本确认。
     *
     * @param operation 指标和错误中的操作名
     * @param routingKey 用于选择主节点的键
     * @param script 待执行脚本
     * @param keys 脚本键，必须全部与 routingKey 同槽
     * @param arguments ARGV 参数
     * @return Lua 整数结果
     */
    Long executeReplicatedScript(
            String operation,
            String routingKey,
            DefaultRedisScript<Long> script,
            List<String> keys,
            String... arguments
    ) {
        requireSameSlot(routingKey, keys);
        return execute(operation, routingKey, commands -> {
            Long result = commands.eval(
                    bytes(script.getScriptAsString()),
                    keys.stream().map(this::bytes).toArray(byte[][]::new),
                    Arrays.stream(arguments).map(this::bytes).toArray(byte[][]::new)
            );
            if (result != null && (result == 0L || result == 3L)) {
                confirmReplication(commands, operation);
            }
            return result;
        });
    }

    /**
     * 在键所属主节点执行回调；遇 MOVED/ASK 时刷新拓扑并仅重试一次。
     */
    private <T> T execute(String operation, String routingKey, Function<NodeBoundCommands, T> callback) {
        Timer.Sample sample = Timer.start(metrics);
        String outcome = "failure";
        try {
            T result = connections.execute(routingKey, callback);
            outcome = "success";
            return result;
        } catch (RuntimeException firstFailure) {
            if (!isMoved(firstFailure) || !connections.refreshTopology()) {
                outcome = replicationFailure(firstFailure) ? "insufficient" : "failure";
                throw firstFailure;
            }
            count(operation, "topology_retry");
            try {
                T result = connections.execute(routingKey, callback);
                outcome = "success_after_topology_refresh";
                return result;
            } catch (RuntimeException retryFailure) {
                outcome = replicationFailure(retryFailure)
                        ? "insufficient_after_topology_refresh"
                        : "failure_after_topology_refresh";
                throw retryFailure;
            }
        } finally {
            count(operation, outcome);
            sample.stop(Timer.builder("citycopilot.commerce.redis.replication.duration")
                    .tag("operation", operation)
                    .register(metrics));
        }
    }

    /** 在写入所用的同一连接上执行 WAIT，确认达到配置的副本数。 */
    private void confirmReplication(NodeBoundCommands commands, String operation) {
        if (requiredReplicas == 0) return;
        Long confirmed = commands.waitForReplication(requiredReplicas, waitTimeoutMillis);
        if (confirmed == null || confirmed < requiredReplicas) {
            log.error(
                    "Redis {} write reached only {} of {} required replicas before {}ms",
                    operation,
                    confirmed,
                    requiredReplicas,
                    waitTimeoutMillis
            );
            throw new RedisReplicationInsufficientException(
                    "Redis write did not reach the required replica count"
            );
        }
    }

    /** 在发送 Lua 前本地拒绝跨 slot 键，避免部分逻辑在 Cluster 中不可执行。 */
    private void requireSameSlot(String routingKey, List<String> keys) {
        int expected = SlotHash.getSlot(bytes(routingKey));
        for (String key : keys) {
            if (SlotHash.getSlot(bytes(key)) != expected) {
                throw new IllegalArgumentException("Redis script keys do not share the routing key slot");
            }
        }
    }

    private boolean isMoved(Throwable failure) {
        Throwable cursor = failure;
        while (cursor != null) {
            if (cursor instanceof RedisCommandExecutionException
                    && cursor.getMessage() != null
                    && (cursor.getMessage().startsWith("MOVED ")
                    || cursor.getMessage().startsWith("ASK "))) {
                return true;
            }
            cursor = cursor.getCause();
        }
        return false;
    }

    private boolean replicationFailure(Throwable failure) {
        Throwable cursor = failure;
        while (cursor != null) {
            if (cursor instanceof RedisReplicationInsufficientException) return true;
            cursor = cursor.getCause();
        }
        return false;
    }

    private void count(String operation, String outcome) {
        metrics.counter(METRIC, "operation", operation, "outcome", outcome).increment();
    }

    private byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private String text(byte[] value) {
        return new String(value, StandardCharsets.UTF_8);
    }

    private static NodeBoundConnectionProvider lettuceProvider(RedisConnectionFactory factory) {
        if (!(factory instanceof LettuceConnectionFactory lettuce)) {
            throw new IllegalStateException(
                    "Commerce replicated admission requires LettuceConnectionFactory"
            );
        }
        return new LettuceNodeBoundConnectionProvider(lettuce.getRequiredNativeClient());
    }

    /** 关闭本组件持有的独立 Lettuce 连接。 */
    @PreDestroy
    void close() {
        connections.close();
    }

    /** 按路由键提供主节点绑定命令的抽象，便于兼容单机、Cluster 与测试替身。 */
    interface NodeBoundConnectionProvider {
        /** 在 routingKey 当前主节点连接上执行回调。 */
        <T> T execute(String routingKey, Function<NodeBoundCommands, T> callback);

        default boolean refreshTopology() {
            return false;
        }

        default void close() {}
    }

    /** 本组件所需的最小 Redis 命令集合，保证 eval 与 WAIT 共享物理连接。 */
    interface NodeBoundCommands {
        Long eval(byte[] script, byte[][] keys, byte[][] arguments);

        byte[] evalValue(byte[] script, byte[][] keys, byte[][] arguments);

        Long waitForReplication(int replicas, long timeoutMillis);
    }

    /** Lettuce 实现：单机复用连接，Cluster 按 slot 获取具体主节点写连接。 */
    private static final class LettuceNodeBoundConnectionProvider
            implements NodeBoundConnectionProvider {
        /** Lettuce 单机或 Cluster 客户端实例。 */
        private final AbstractRedisClient client;
        /** 单机模式复用的二进制命令连接。 */
        private volatile StatefulRedisConnection<byte[], byte[]> standalone;
        /** Cluster 模式复用的拓扑连接，用于按 slot 取得主节点连接。 */
        private volatile StatefulRedisClusterConnection<byte[], byte[]> cluster;

        private LettuceNodeBoundConnectionProvider(AbstractRedisClient client) {
            if (!(client instanceof RedisClient) && !(client instanceof RedisClusterClient)) {
                throw new IllegalStateException(
                        "Unsupported Lettuce client for Commerce replicated admission: "
                                + client.getClass().getName()
                );
            }
            this.client = client;
        }

        @Override
        public <T> T execute(String routingKey, Function<NodeBoundCommands, T> callback) {
            return callback.apply(commands(routingKey));
        }

        /** 根据路由键解析当前主节点；找不到可写主节点时失败关闭。 */
        private NodeBoundCommands commands(String routingKey) {
            if (client instanceof RedisClient redisClient) {
                return new LettuceNodeBoundCommands(standalone(redisClient).sync());
            }
            StatefulRedisClusterConnection<byte[], byte[]> connection = cluster(
                    (RedisClusterClient) client
            );
            int slot = SlotHash.getSlot(routingKey);
            RedisClusterNode master = connection.getPartitions().getMasterBySlot(slot);
            if (master == null || master.getNodeId() == null) {
                throw new IllegalStateException("Redis Cluster has no master for Commerce key slot " + slot);
            }
            StatefulRedisConnection<byte[], byte[]> nodeConnection = connection.getConnection(
                    master.getNodeId(),
                    ConnectionIntent.WRITE
            );
            if (nodeConnection == null || !nodeConnection.isOpen()) {
                throw new IllegalStateException("Redis Cluster master connection is unavailable");
            }
            return new LettuceNodeBoundCommands(nodeConnection.sync());
        }

        private synchronized StatefulRedisConnection<byte[], byte[]> standalone(RedisClient redisClient) {
            if (standalone == null || !standalone.isOpen()) {
                standalone = redisClient.connect(ByteArrayCodec.INSTANCE);
            }
            return standalone;
        }

        private synchronized StatefulRedisClusterConnection<byte[], byte[]> cluster(
                RedisClusterClient redisClient
        ) {
            if (cluster == null || !cluster.isOpen()) {
                cluster = redisClient.connect(ByteArrayCodec.INSTANCE);
            }
            return cluster;
        }

        @Override
        public boolean refreshTopology() {
            if (!(client instanceof RedisClusterClient clusterClient)) return false;
            clusterClient.reloadPartitions();
            return true;
        }

        @Override
        public synchronized void close() {
            if (standalone != null) standalone.close();
            if (cluster != null) cluster.close();
        }
    }

    /**
     * 绑定某个主节点的同步命令适配器。
     *
     * @param delegate Lettuce 同步命令连接
     */
    private record LettuceNodeBoundCommands(RedisCommands<byte[], byte[]> delegate)
            implements NodeBoundCommands {
        @Override
        public Long eval(byte[] script, byte[][] keys, byte[][] arguments) {
            return delegate.eval(script, ScriptOutputType.INTEGER, keys, arguments);
        }

        @Override
        public byte[] evalValue(byte[] script, byte[][] keys, byte[][] arguments) {
            return delegate.eval(script, ScriptOutputType.VALUE, keys, arguments);
        }

        @Override
        public Long waitForReplication(int replicas, long timeoutMillis) {
            return delegate.waitForReplication(replicas, timeoutMillis);
        }
    }

    /** WAIT 未达到要求副本数。 */
    static final class RedisReplicationInsufficientException extends RuntimeException {
        RedisReplicationInsufficientException(String message) {
            super(message);
        }
    }

    /** 控制面幂等写的三态结果。 */
    enum ReplicatedMutation {
        /** 当前值属于其他所有者或不等于预期值。 */
        CONFLICT,
        /** 本次调用改变了值。 */
        CHANGED,
        /** 目标状态原本已存在，本次仅重新确认复制。 */
        ALREADY_APPLIED;

        private static ReplicatedMutation from(Long result) {
            if (result == null) {
                throw new IllegalStateException("Redis routing mutation returned no result");
            }
            return switch (result.intValue()) {
                case 0 -> CONFLICT;
                case 1 -> CHANGED;
                case 2 -> ALREADY_APPLIED;
                default -> throw new IllegalStateException(
                        "Redis routing mutation returned an invalid result: " + result
                );
            };
        }
    }
}
