package com.citycopilot.commerce;

import io.lettuce.core.RedisCommandExecutionException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CommerceRedisReplicationExecutorTest {

    @Test
    void globalIdentityUsesOneNodeBoundLuaAndWait() {
        FakeCommands commands = new FakeCommands();
        commands.valueResult = bytes("300|100|seckill:v1:100");
        commands.replicationResult = 1L;
        FakeProvider provider = new FakeProvider(commands);
        CommerceRedisReplicationExecutor executor = new CommerceRedisReplicationExecutor(
                provider, new SimpleMeterRegistry(), 1, 250
        );

        String stored = executor.reserveGlobalIdentity(
                "commerce:{commerce:idempotency:200}:seckill:global-idempotency:key",
                "300|100|seckill:v1:100"
        );

        assertThat(stored).isEqualTo("300|100|seckill:v1:100");
        assertThat(commands.valueEvalCount).isEqualTo(1);
        assertThat(commands.waitCount).isEqualTo(1);
        assertThat(commands.waitReplicas).isEqualTo(1);
        assertThat(commands.waitTimeoutMillis).isEqualTo(250L);
    }

    @Test
    void globalIdentityConflictReturnsTheStoredIdentityAndStillConfirmsIt() {
        FakeCommands commands = new FakeCommands();
        commands.valueResult = bytes("299|99|seckill:v1:99");
        commands.replicationResult = 1L;
        CommerceRedisReplicationExecutor executor = new CommerceRedisReplicationExecutor(
                new FakeProvider(commands), new SimpleMeterRegistry(), 1, 250
        );

        String stored = executor.reserveGlobalIdentity(
                "commerce:{commerce:idempotency:200}:seckill:global-idempotency:key",
                "300|100|seckill:v1:100"
        );

        assertThat(stored).isEqualTo("299|99|seckill:v1:99");
        assertThat(commands.lastValueArguments.length).isEqualTo(1);
        assertThat(commands.lastValueArguments[0])
                .isEqualTo(bytes("300|100|seckill:v1:100"));
        assertThat(commands.waitCount).isEqualTo(1);
    }

    @Test
    void acceptedAndIdempotentScriptResultsRequireReplication() {
        FakeCommands commands = new FakeCommands();
        commands.integerResult = 3L;
        commands.replicationResult = 1L;
        CommerceRedisReplicationExecutor executor = new CommerceRedisReplicationExecutor(
                new FakeProvider(commands), new SimpleMeterRegistry(), 1, 100
        );
        DefaultRedisScript<Long> script = new DefaultRedisScript<>("return 3", Long.class);
        String key = "commerce:{commerce:v1:1:0}:stock:100";

        Long result = executor.executeReplicatedScript(
                "voucher_admission", key, script, List.of(key), "arg"
        );

        assertThat(result).isEqualTo(3L);
        assertThat(commands.waitCount).isEqualTo(1);
    }

    @Test
    void explicitRejectionDoesNotWait() {
        FakeCommands commands = new FakeCommands();
        commands.integerResult = 1L;
        CommerceRedisReplicationExecutor executor = new CommerceRedisReplicationExecutor(
                new FakeProvider(commands), new SimpleMeterRegistry(), 1, 100
        );
        DefaultRedisScript<Long> script = new DefaultRedisScript<>("return 1", Long.class);
        String key = "commerce:{commerce:v1:1:0}:stock:100";

        assertThat(executor.executeReplicatedScript(
                "voucher_admission", key, script, List.of(key), "arg"
        )).isEqualTo(1L);
        assertThat(commands.waitCount).isZero();
    }

    @Test
    void persistentMaintenanceFenceIsConfirmedOnTheOwningMaster() {
        FakeCommands commands = new FakeCommands();
        commands.integerResult = 1L;
        commands.replicationResult = 1L;
        CommerceRedisReplicationExecutor executor = new CommerceRedisReplicationExecutor(
                new FakeProvider(commands), new SimpleMeterRegistry(), 1, 100
        );

        CommerceRedisReplicationExecutor.ReplicatedMutation result = executor.setIfAbsent(
                "consistency_repair_fence",
                "commerce:{commerce:v1:1:0}:seckill:fence:100",
                "repair-token"
        );

        assertThat(result).isEqualTo(CommerceRedisReplicationExecutor.ReplicatedMutation.CHANGED);
        assertThat(commands.waitCount).isEqualTo(1);
    }

    @Test
    void conditionalFenceDeleteIsReplicaConfirmed() {
        FakeCommands commands = new FakeCommands();
        commands.integerResult = 1L;
        commands.replicationResult = 1L;
        CommerceRedisReplicationExecutor executor = new CommerceRedisReplicationExecutor(
                new FakeProvider(commands), new SimpleMeterRegistry(), 1, 100
        );

        CommerceRedisReplicationExecutor.ReplicatedMutation result = executor.deleteIfEquals(
                "consistency_repair_release",
                "commerce:{commerce:v1:1:0}:seckill:fence:100",
                "repair-token"
        );

        assertThat(result).isEqualTo(CommerceRedisReplicationExecutor.ReplicatedMutation.CHANGED);
        assertThat(commands.waitCount).isEqualTo(1);
    }

    @Test
    void insufficientReplicaConfirmationFailsClosed() {
        FakeCommands commands = new FakeCommands();
        commands.integerResult = 0L;
        commands.replicationResult = 0L;
        CommerceRedisReplicationExecutor executor = new CommerceRedisReplicationExecutor(
                new FakeProvider(commands), new SimpleMeterRegistry(), 1, 100
        );
        DefaultRedisScript<Long> script = new DefaultRedisScript<>("return 0", Long.class);
        String key = "commerce:{commerce:v1:1:0}:stock:100";

        assertThatThrownBy(() -> executor.executeReplicatedScript(
                "voucher_admission", key, script, List.of(key), "arg"
        )).isInstanceOf(CommerceRedisReplicationExecutor.RedisReplicationInsufficientException.class);
    }

    @Test
    void requireAllRejectsWhenOnlyOneOfTwoConfiguredReplicasConfirms() {
        FakeCommands commands = new FakeCommands();
        commands.integerResult = 0L;
        commands.replicationResult = 1L;
        commands.configuredReplicaCount = 2;
        CommerceRedisReplicationExecutor executor = new CommerceRedisReplicationExecutor(
                new FakeProvider(commands), new SimpleMeterRegistry(), 1, 100, true
        );
        DefaultRedisScript<Long> script = new DefaultRedisScript<>("return 0", Long.class);
        String key = "commerce:{commerce:v1:1:0}:stock:100";

        assertThatThrownBy(() -> executor.executeReplicatedScript(
                "voucher_admission", key, script, List.of(key), "arg"
        )).isInstanceOf(CommerceRedisReplicationExecutor.RedisReplicationInsufficientException.class);
        assertThat(commands.waitReplicas).isEqualTo(2);
    }

    @Test
    void requireAllFailsClosedWhenReplicaTopologyChangesDuringWait() {
        FakeCommands commands = new FakeCommands();
        commands.integerResult = 0L;
        commands.replicationResult = 2L;
        commands.configuredReplicaCount = 2;
        commands.configuredReplicaCountAfterWait = 1;
        CommerceRedisReplicationExecutor executor = new CommerceRedisReplicationExecutor(
                new FakeProvider(commands), new SimpleMeterRegistry(), 1, 100, true
        );
        DefaultRedisScript<Long> script = new DefaultRedisScript<>("return 0", Long.class);
        String key = "commerce:{commerce:v1:1:0}:stock:100";

        assertThatThrownBy(() -> executor.executeReplicatedScript(
                "voucher_admission", key, script, List.of(key), "arg"
        )).isInstanceOf(
                CommerceRedisReplicationExecutor.RedisReplicationTopologyUnavailableException.class
        );
    }

    @Test
    void requireAllFailsBeforeWritingWhenProviderHidesReplicaTopology() {
        FakeCommands commands = new FakeCommands();
        commands.integerResult = 0L;
        CommerceRedisReplicationExecutor executor = new CommerceRedisReplicationExecutor(
                new FakeProvider(commands), new SimpleMeterRegistry(), 1, 100, true
        );
        DefaultRedisScript<Long> script = new DefaultRedisScript<>("return 0", Long.class);
        String key = "commerce:{commerce:v1:1:0}:stock:100";

        assertThatThrownBy(() -> executor.executeReplicatedScript(
                "voucher_admission", key, script, List.of(key), "arg"
        )).isInstanceOf(
                CommerceRedisReplicationExecutor.RedisReplicationTopologyUnavailableException.class
        );
        assertThat(commands.evalCount).isZero();
        assertThat(commands.waitCount).isZero();
    }

    @Test
    void requireAllStartupValidationFailsWhenProviderHidesReplicaTopology() {
        CommerceRedisReplicationExecutor executor = new CommerceRedisReplicationExecutor(
                new FakeProvider(new FakeCommands()),
                new SimpleMeterRegistry(),
                1,
                100,
                true
        );

        assertThatThrownBy(executor::validateRequiredReplicaTopology)
                .isInstanceOf(
                        CommerceRedisReplicationExecutor.RedisReplicationTopologyUnavailableException.class
                );
    }

    @Test
    void movedResponseRefreshesTopologyAndRetriesTheWholeWriteAndWaitOnce() {
        FakeCommands commands = new FakeCommands();
        commands.integerResult = 0L;
        commands.replicationResult = 1L;
        commands.firstEvalFailure = new RedisCommandExecutionException(
                "MOVED 1234 127.0.0.1:7001"
        );
        FakeProvider provider = new FakeProvider(commands);
        provider.refreshable = true;
        CommerceRedisReplicationExecutor executor = new CommerceRedisReplicationExecutor(
                provider, new SimpleMeterRegistry(), 1, 100
        );
        DefaultRedisScript<Long> script = new DefaultRedisScript<>("return 0", Long.class);
        String key = "commerce:{commerce:v1:1:0}:stock:100";

        assertThat(executor.executeReplicatedScript(
                "voucher_admission", key, script, List.of(key), "arg"
        )).isEqualTo(0L);
        assertThat(provider.executeCount).isEqualTo(2);
        assertThat(provider.refreshCount).isEqualTo(1);
        assertThat(commands.evalCount).isEqualTo(2);
        assertThat(commands.waitCount).isEqualTo(1);
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static final class FakeProvider
            implements CommerceRedisReplicationExecutor.NodeBoundConnectionProvider {
        private final FakeCommands commands;
        private boolean refreshable;
        private int executeCount;
        private int refreshCount;

        private FakeProvider(FakeCommands commands) {
            this.commands = commands;
        }

        @Override
        public <T> T execute(
                String routingKey,
                Function<CommerceRedisReplicationExecutor.NodeBoundCommands, T> callback
        ) {
            executeCount++;
            return callback.apply(commands);
        }

        @Override
        public boolean refreshTopology() {
            refreshCount++;
            return refreshable;
        }
    }

    private static final class FakeCommands
            implements CommerceRedisReplicationExecutor.NodeBoundCommands {
        private Long integerResult;
        private byte[] valueResult;
        private Long replicationResult = 0L;
        private RuntimeException firstEvalFailure;
        private int evalCount;
        private int valueEvalCount;
        private int waitCount;
        private int waitReplicas;
        private long waitTimeoutMillis;
        private byte[][] lastValueArguments;
        private int configuredReplicaCount = -1;
        private int configuredReplicaCountAfterWait = -1;
        private int targetReadCount;

        @Override
        public Long eval(byte[] script, byte[][] keys, byte[][] arguments) {
            evalCount++;
            if (evalCount == 1 && firstEvalFailure != null) throw firstEvalFailure;
            return integerResult;
        }

        @Override
        public byte[] evalValue(byte[] script, byte[][] keys, byte[][] arguments) {
            valueEvalCount++;
            lastValueArguments = arguments;
            return valueResult;
        }

        @Override
        public Long waitForReplication(int replicas, long timeoutMillis) {
            waitCount++;
            waitReplicas = replicas;
            this.waitTimeoutMillis = timeoutMillis;
            return replicationResult;
        }

        @Override
        public CommerceRedisReplicationExecutor.ReplicationConfirmationTarget
        replicationConfirmationTarget(int minimumRequiredReplicas, boolean requireAllReplicas) {
            if (!requireAllReplicas) {
                return CommerceRedisReplicationExecutor.NodeBoundCommands.super
                        .replicationConfirmationTarget(minimumRequiredReplicas, false);
            }
            targetReadCount++;
            int replicas = targetReadCount > 1 && configuredReplicaCountAfterWait >= 0
                    ? configuredReplicaCountAfterWait
                    : configuredReplicaCount;
            if (replicas < 0) {
                return CommerceRedisReplicationExecutor.NodeBoundCommands.super
                        .replicationConfirmationTarget(minimumRequiredReplicas, true);
            }
            return new CommerceRedisReplicationExecutor.ReplicationConfirmationTarget(
                    replicas,
                    "fake-topology:" + replicas
            );
        }
    }
}
