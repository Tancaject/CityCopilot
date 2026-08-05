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
import jakarta.annotation.PostConstruct;
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
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;

/**
 * Executes a Redis write and {@code WAIT} on one physical Lettuce connection.
 *
 * <p>Spring Data's Cluster {@code execute(command, key, args)} prepends the key
 * to the Redis command arguments; it cannot route the keyless WAIT command.
 * This component therefore resolves the key slot to its current master and
 * invokes both the write and WAIT on that master's dedicated Lettuce
 * {@link StatefulRedisConnection}. Non-Lettuce factories are rejected because
 * they cannot provide the required connection/node binding. In production
 * require-all mode, the same connection reads {@code CLUSTER REPLICAS} before
 * the write, waits for every configured replica, and verifies the replica
 * identity set again after WAIT. Hidden or changing topology fails closed.</p>
 */
@Component
final class CommerceRedisReplicationExecutor {
    private static final Logger log = LoggerFactory.getLogger(CommerceRedisReplicationExecutor.class);
    private static final String METRIC = "citycopilot.commerce.redis.replication.confirmation";
    private static final byte[] RESERVE_GLOBAL_IDENTITY_SCRIPT = """
            local existing = redis.call('get', KEYS[1])
            if not existing then
              redis.call('set', KEYS[1], ARGV[1])
              return ARGV[1]
            end
            redis.call('set', KEYS[1], existing)
            return existing
            """.getBytes(StandardCharsets.UTF_8);
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

    private final NodeBoundConnectionProvider connections;
    private final MeterRegistry metrics;
    private final int requiredReplicas;
    private final long waitTimeoutMillis;
    private final boolean requireAllReplicas;

    @Autowired
    CommerceRedisReplicationExecutor(
            RedisConnectionFactory connectionFactory,
            MeterRegistry metrics,
            @Value("${citycopilot.commerce.redis-required-replicas:0}") int requiredReplicas,
            @Value("${citycopilot.commerce.redis-replication-timeout-ms:1000}") long waitTimeoutMillis,
            @Value("${citycopilot.commerce.redis-require-all-replicas:false}") boolean requireAllReplicas
    ) {
        this(
                lettuceProvider(connectionFactory),
                metrics,
                requiredReplicas,
                waitTimeoutMillis,
                requireAllReplicas
        );
    }

    CommerceRedisReplicationExecutor(
            NodeBoundConnectionProvider connections,
            MeterRegistry metrics,
            int requiredReplicas,
            long waitTimeoutMillis
    ) {
        this(connections, metrics, requiredReplicas, waitTimeoutMillis, false);
    }

    CommerceRedisReplicationExecutor(
            NodeBoundConnectionProvider connections,
            MeterRegistry metrics,
            int requiredReplicas,
            long waitTimeoutMillis,
            boolean requireAllReplicas
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
        this.requireAllReplicas = requireAllReplicas;
    }

    @PostConstruct
    void validateRequiredReplicaTopology() {
        if (!requireAllReplicas) return;
        execute("startup_replica_topology", CommerceRedisKeyspace.ROUTING_CONTROL_KEY, commands -> {
            confirmationTarget(commands, "startup_replica_topology");
            return null;
        });
    }

    String reserveGlobalIdentity(String key, String candidateIdentity) {
        return execute("global_idempotency", key, commands -> {
            ReplicationConfirmationTarget confirmationTarget = confirmationTarget(
                    commands,
                    "global_idempotency"
            );
            byte[] rawKey = bytes(key);
            byte[] candidate = bytes(candidateIdentity);
            byte[] existing = commands.evalValue(
                    RESERVE_GLOBAL_IDENTITY_SCRIPT,
                    new byte[][]{rawKey},
                    new byte[][]{candidate}
            );
            if (existing == null) {
                throw new IllegalStateException("Redis did not retain the global admission identity");
            }
            confirmReplication(commands, "global_idempotency", confirmationTarget);
            return text(existing);
        });
    }

    /**
     * Creates a control-plane value without overwriting a conflicting owner.
     * Both the Lua write and WAIT execute on the key's current master
     * connection, including after a Redis Cluster topology refresh.
     */
    ReplicatedMutation setIfAbsent(String operation, String key, String value) {
        return execute(operation, key, commands -> {
            ReplicationConfirmationTarget confirmationTarget = confirmationTarget(commands, operation);
            Long result = commands.eval(
                    SET_IF_ABSENT_SCRIPT,
                    new byte[][]{bytes(key)},
                    new byte[][]{bytes(value)}
            );
            ReplicatedMutation mutation = ReplicatedMutation.from(result);
            if (mutation != ReplicatedMutation.CONFLICT) {
                confirmReplication(commands, operation, confirmationTarget);
            }
            return mutation;
        });
    }

    /**
     * Atomically changes one routing control value. Repeating an already
     * committed transition performs a same-value write and WAIT so a resumed
     * operator still obtains replica confirmation.
     */
    ReplicatedMutation compareAndSet(
            String operation,
            String key,
            String expected,
            String next
    ) {
        return execute(operation, key, commands -> {
            ReplicationConfirmationTarget confirmationTarget = confirmationTarget(commands, operation);
            Long result = commands.eval(
                    COMPARE_AND_SET_SCRIPT,
                    new byte[][]{bytes(key)},
                    new byte[][]{bytes(expected), bytes(next)}
            );
            ReplicatedMutation mutation = ReplicatedMutation.from(result);
            if (mutation != ReplicatedMutation.CONFLICT) {
                confirmReplication(commands, operation, confirmationTarget);
            }
            return mutation;
        });
    }

    /**
     * Deletes only the expected owner and confirms the final absence on the
     * same master connection. An already absent key receives an atomic
     * set/delete no-op so WAIT still covers a fresh replication offset.
     */
    ReplicatedMutation deleteIfEquals(
            String operation,
            String key,
            String expected
    ) {
        return execute(operation, key, commands -> {
            ReplicationConfirmationTarget confirmationTarget = confirmationTarget(commands, operation);
            Long result = commands.eval(
                    DELETE_IF_EQUALS_SCRIPT,
                    new byte[][]{bytes(key)},
                    new byte[][]{bytes(expected)}
            );
            ReplicatedMutation mutation = ReplicatedMutation.from(result);
            if (mutation != ReplicatedMutation.CONFLICT) {
                confirmReplication(commands, operation, confirmationTarget);
            }
            return mutation;
        });
    }

    Long executeReplicatedScript(
            String operation,
            String routingKey,
            DefaultRedisScript<Long> script,
            List<String> keys,
            String... arguments
    ) {
        requireSameSlot(routingKey, keys);
        return execute(operation, routingKey, commands -> {
            ReplicationConfirmationTarget confirmationTarget = confirmationTarget(commands, operation);
            Long result = commands.eval(
                    bytes(script.getScriptAsString()),
                    keys.stream().map(this::bytes).toArray(byte[][]::new),
                    Arrays.stream(arguments).map(this::bytes).toArray(byte[][]::new)
            );
            if (result != null && (result == 0L || result == 3L)) {
                confirmReplication(commands, operation, confirmationTarget);
            }
            return result;
        });
    }

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

    private ReplicationConfirmationTarget confirmationTarget(
            NodeBoundCommands commands,
            String operation
    ) {
        ReplicationConfirmationTarget target = commands.replicationConfirmationTarget(
                requiredReplicas,
                requireAllReplicas
        );
        if (target == null) {
            throw new RedisReplicationTopologyUnavailableException(
                    "Redis " + operation + " replication topology returned no target"
            );
        }
        if (target.replicaCount() < requiredReplicas) {
            throw new RedisReplicationInsufficientException(
                    "Redis topology has fewer replicas than the configured minimum"
            );
        }
        if (requireAllReplicas && target.replicaCount() == 0) {
            throw new RedisReplicationTopologyUnavailableException(
                    "Redis topology exposes no configured replica for " + operation
            );
        }
        return target;
    }

    private void confirmReplication(
            NodeBoundCommands commands,
            String operation,
            ReplicationConfirmationTarget targetBeforeWrite
    ) {
        if (targetBeforeWrite.replicaCount() == 0) return;
        Long confirmed = commands.waitForReplication(
                targetBeforeWrite.replicaCount(),
                waitTimeoutMillis
        );
        if (requireAllReplicas) {
            ReplicationConfirmationTarget targetAfterWait = confirmationTarget(commands, operation);
            if (!targetBeforeWrite.equals(targetAfterWait)) {
                throw new RedisReplicationTopologyUnavailableException(
                        "Redis replica topology changed during " + operation + " confirmation"
                );
            }
        }
        if (confirmed == null || confirmed < targetBeforeWrite.replicaCount()) {
            log.error(
                    "Redis {} write reached only {} of {} required replicas before {}ms",
                    operation,
                    confirmed,
                    targetBeforeWrite.replicaCount(),
                    waitTimeoutMillis
            );
            throw new RedisReplicationInsufficientException(
                    "Redis write did not reach the required replica count"
            );
        }
    }

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
            if (cursor instanceof RedisReplicationTopologyUnavailableException) return true;
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

    @PreDestroy
    void close() {
        connections.close();
    }

    interface NodeBoundConnectionProvider {
        <T> T execute(String routingKey, Function<NodeBoundCommands, T> callback);

        default boolean refreshTopology() {
            return false;
        }

        default void close() {}
    }

    interface NodeBoundCommands {
        Long eval(byte[] script, byte[][] keys, byte[][] arguments);

        byte[] evalValue(byte[] script, byte[][] keys, byte[][] arguments);

        Long waitForReplication(int replicas, long timeoutMillis);

        default ReplicationConfirmationTarget replicationConfirmationTarget(
                int minimumRequiredReplicas,
                boolean requireAllReplicas
        ) {
            if (requireAllReplicas) {
                throw new RedisReplicationTopologyUnavailableException(
                        "Redis provider does not expose configured replica topology"
                );
            }
            return new ReplicationConfirmationTarget(
                    minimumRequiredReplicas,
                    "configured-minimum:" + minimumRequiredReplicas
            );
        }
    }

    private static final class LettuceNodeBoundConnectionProvider
            implements NodeBoundConnectionProvider {
        private final AbstractRedisClient client;
        private volatile StatefulRedisConnection<byte[], byte[]> standalone;
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

        private NodeBoundCommands commands(String routingKey) {
            if (client instanceof RedisClient redisClient) {
                return new LettuceNodeBoundCommands(standalone(redisClient).sync(), null);
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
            return new LettuceNodeBoundCommands(nodeConnection.sync(), master.getNodeId());
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

    private record LettuceNodeBoundCommands(
            RedisCommands<byte[], byte[]> delegate,
            String masterNodeId
    )
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

        @Override
        public ReplicationConfirmationTarget replicationConfirmationTarget(
                int minimumRequiredReplicas,
                boolean requireAllReplicas
        ) {
            if (!requireAllReplicas) {
                return NodeBoundCommands.super.replicationConfirmationTarget(
                        minimumRequiredReplicas,
                        false
                );
            }
            if (masterNodeId == null || masterNodeId.isBlank()) {
                throw new RedisReplicationTopologyUnavailableException(
                        "Require-all replica confirmation needs Redis Cluster topology"
                );
            }
            List<String> replicaLines;
            try {
                replicaLines = delegate.clusterReplicas(masterNodeId);
            } catch (RuntimeException topologyFailure) {
                throw new RedisReplicationTopologyUnavailableException(
                        "Unable to read configured Redis Cluster replicas",
                        topologyFailure
                );
            }
            if (replicaLines == null) {
                throw new RedisReplicationTopologyUnavailableException(
                        "Redis Cluster returned no replica topology"
                );
            }
            Set<String> replicaIds = new TreeSet<>();
            for (String replicaLine : replicaLines) {
                String[] fields = replicaLine == null
                        ? new String[0]
                        : replicaLine.trim().split("\\s+");
                if (fields.length < 8) {
                    throw new RedisReplicationTopologyUnavailableException(
                            "Redis Cluster returned an unrecognized replica record"
                    );
                }
                Set<String> flags = Set.of(fields[2].split(","));
                if (!flags.contains("slave") && !flags.contains("replica")) {
                    throw new RedisReplicationTopologyUnavailableException(
                            "Redis Cluster replica record has no replica role"
                    );
                }
                if (!masterNodeId.equals(fields[3])
                        || fields[0].isBlank()
                        || masterNodeId.equals(fields[0])
                        || !replicaIds.add(fields[0])) {
                    throw new RedisReplicationTopologyUnavailableException(
                            "Redis Cluster replica ownership is inconsistent"
                    );
                }
            }
            if (replicaIds.isEmpty()) {
                throw new RedisReplicationTopologyUnavailableException(
                        "Redis Cluster master has no configured replica"
                );
            }
            return new ReplicationConfirmationTarget(
                    replicaIds.size(),
                    "cluster:" + masterNodeId + ":" + String.join(",", replicaIds)
            );
        }
    }

    static final class RedisReplicationInsufficientException extends RuntimeException {
        RedisReplicationInsufficientException(String message) {
            super(message);
        }
    }

    static final class RedisReplicationTopologyUnavailableException extends RuntimeException {
        RedisReplicationTopologyUnavailableException(String message) {
            super(message);
        }

        RedisReplicationTopologyUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    record ReplicationConfirmationTarget(int replicaCount, String topologyFingerprint) {
        ReplicationConfirmationTarget {
            if (replicaCount < 0 || topologyFingerprint == null || topologyFingerprint.isBlank()) {
                throw new IllegalArgumentException("Invalid Redis replication confirmation target");
            }
        }
    }

    enum ReplicatedMutation {
        CONFLICT,
        CHANGED,
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
