package com.citycopilot.commerce;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class CommerceRedisKeyspaceTest {

    @Test
    void everyLuaKeyForOneVoucherSharesExactlyOneClusterHashTag() {
        CommerceRedisKeyspace keyspace = new CommerceRedisKeyspace(16, "g1");
        long voucherId = 42L;

        List<String> luaKeys = List.of(
                keyspace.campaignMeta(voucherId),
                keyspace.stock(voucherId),
                keyspace.buyers(voucherId),
                keyspace.stream(voucherId),
                keyspace.idempotency(voucherId, 7L, "request-1"),
                keyspace.fence(voucherId),
                keyspace.inflight(voucherId),
                keyspace.compensationMarker(voucherId, "g1:10:1-0")
        );

        assertThat(luaKeys)
                .allSatisfy(key -> assertThat(hashTag(key))
                        .isEqualTo(keyspace.clusterHashTag(voucherId)));
    }

    @Test
    void allReplicasRouteTheSameVoucherIdenticallyAndSpreadDifferentVouchers() {
        CommerceRedisKeyspace firstReplica = new CommerceRedisKeyspace(16, "g1");
        CommerceRedisKeyspace secondReplica = new CommerceRedisKeyspace(16, "g1");
        Set<Integer> observedShards = new HashSet<>();

        for (long voucherId = 1; voucherId <= 256; voucherId++) {
            assertThat(firstReplica.stream(voucherId))
                    .isEqualTo(secondReplica.stream(voucherId));
            observedShards.add(firstReplica.physicalShard(voucherId));
        }

        assertThat(observedShards).hasSize(16);
    }

    @Test
    void shardPrefixMakesEqualRedisRecordIdsGloballyUnique() {
        CommerceRedisKeyspace keyspace = new CommerceRedisKeyspace(4, "g1");
        String recordId = "1720000000000-0";

        assertThat(keyspace.recoverySourceId(keyspace.streams().get(0), recordId))
                .isNotEqualTo(keyspace.recoverySourceId(keyspace.streams().get(1), recordId));
    }

    @Test
    void globalIdempotencyIdentityDoesNotDependOnVoucherShard() {
        CommerceRedisKeyspace keyspace = new CommerceRedisKeyspace(16, "g1");

        String registryKey = keyspace.globalIdempotency(7L, "request-1");

        assertThat(registryKey)
                .isEqualTo(new CommerceRedisKeyspace(16, "g1")
                        .globalIdempotency(7L, "request-1"));
        assertThat(registryKey)
                .contains("{commerce-idempotency:g1:16:");
    }

    @Test
    void idempotencyKeysRemainOpaqueAndCaseSensitiveInRedisRouting() {
        CommerceRedisKeyspace keyspace = new CommerceRedisKeyspace(16, "g1");

        assertThat(keyspace.globalIdempotency(7L, "Request-Key"))
                .isNotEqualTo(keyspace.globalIdempotency(7L, "request-key"));
        assertThat(keyspace.idempotency(42L, 7L, "Request-Key"))
                .isNotEqualTo(keyspace.idempotency(42L, 7L, "request-key"));
    }

    private String hashTag(String key) {
        return key.substring(key.indexOf('{'), key.indexOf('}') + 1);
    }
}
