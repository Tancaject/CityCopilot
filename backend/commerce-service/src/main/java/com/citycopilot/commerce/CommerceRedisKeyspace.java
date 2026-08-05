package com.citycopilot.commerce;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Deterministically routes every voucher to one Redis Cluster logical bucket.
 *
 * <p>The routing generation and shard count are deployment invariants. Every
 * API replica computes the same bucket locally, while workers poll every bucket
 * stream. A generation change therefore requires an explicit data migration,
 * never an in-place configuration rollout.</p>
 */
@Component
final class CommerceRedisKeyspace {
    private static final Pattern SAFE_GENERATION = Pattern.compile("[A-Za-z0-9_-]{1,24}");
    static final String ROUTING_CONTROL_KEY = "commerce:{commerce-runtime}:routing";
    static final String RUNTIME_MUTATION_LEASES_KEY =
            "commerce:{commerce-runtime}:mutation-leases";
    static final String ROUTING_OPERATOR_LEASE_KEY =
            "commerce:{commerce-runtime}:routing-operator-lease";
    private static final String ROUTING_RECORD_VERSION = "v1";

    private final int physicalShardCount;
    private final String routingGeneration;
    private final List<String> streams;
    private final Map<String, Integer> streamShards;

    CommerceRedisKeyspace(
            @Value("${citycopilot.commerce.redis-physical-shard-count:1}") int physicalShardCount,
            @Value("${citycopilot.commerce.redis-routing-generation:v1}") String routingGeneration
    ) {
        if (physicalShardCount < 1 || physicalShardCount > 1024) {
            throw new IllegalArgumentException("Commerce Redis physical shard count must be 1..1024");
        }
        String normalizedGeneration = Objects.requireNonNull(
                routingGeneration,
                "routingGeneration"
        ).trim();
        if (!SAFE_GENERATION.matcher(normalizedGeneration).matches()) {
            throw new IllegalArgumentException(
                    "Invalid Commerce Redis routing generation: " + routingGeneration
            );
        }
        this.physicalShardCount = physicalShardCount;
        this.routingGeneration = normalizedGeneration;
        List<String> configuredStreams = new ArrayList<>(physicalShardCount);
        Map<String, Integer> configuredStreamShards = new LinkedHashMap<>(physicalShardCount);
        for (int shard = 0; shard < physicalShardCount; shard++) {
            String stream = prefix(shard) + "stream:orders";
            configuredStreams.add(stream);
            configuredStreamShards.put(stream, shard);
        }
        this.streams = List.copyOf(configuredStreams);
        this.streamShards = Map.copyOf(configuredStreamShards);
    }

    int physicalShardCount() {
        return physicalShardCount;
    }

    String routingGeneration() {
        return routingGeneration;
    }

    int physicalShard(long voucherId) {
        if (voucherId <= 0) {
            throw new IllegalArgumentException("voucherId must be positive");
        }
        return Math.floorMod(Long.hashCode(voucherId), physicalShardCount);
    }

    String clusterHashTag(long voucherId) {
        return hashTag(physicalShard(voucherId));
    }

    String campaignMeta(long voucherId) {
        return prefix(voucherId) + "seckill:meta:" + voucherId;
    }

    String campaignRevision(long voucherId) {
        return prefix(voucherId) + "seckill:meta-revision:" + voucherId;
    }

    String campaignUpdateFence(long voucherId) {
        return prefix(voucherId) + "seckill:meta-update-fence:" + voucherId;
    }

    String stock(long voucherId) {
        return prefix(voucherId) + "seckill:stock:" + voucherId;
    }

    String buyers(long voucherId) {
        return prefix(voucherId) + "seckill:buyers:" + voucherId;
    }

    String idempotency(long voucherId, long userId, String idempotencyKey) {
        return prefix(voucherId) + "seckill:idempotency:"
                + voucherId + ":" + userId + ":" + idempotencyKey;
    }

    /**
     * Permanent cross-voucher identity registry. This key is intentionally
     * outside the voucher Lua slot: SET NX reserves the global identity first,
     * and a retry can safely finish the voucher-local admission after a crash.
     */
    String globalIdempotency(long userId, String idempotencyKey) {
        int shard = Math.floorMod(
                31 * Long.hashCode(userId) + idempotencyKey.hashCode(),
                physicalShardCount
        );
        return "commerce:{commerce-idempotency:" + routingGeneration + ":"
                + physicalShardCount + ":" + shard + "}:"
                + userId + ":" + idempotencyKey;
    }

    String fence(long voucherId) {
        return prefix(voucherId) + "seckill:fence:" + voucherId;
    }

    String inflight(long voucherId) {
        return prefix(voucherId) + "seckill:inflight:" + voucherId;
    }

    String admissionRoutingGate(long voucherId) {
        return prefix(voucherId) + "runtime:routing-gate";
    }

    String admissionRuntimeLeases(long voucherId) {
        return prefix(voucherId) + "runtime:admission-leases";
    }

    List<String> admissionRoutingGates() {
        List<String> gates = new ArrayList<>(physicalShardCount);
        for (int shard = 0; shard < physicalShardCount; shard++) {
            gates.add(prefix(shard) + "runtime:routing-gate");
        }
        return List.copyOf(gates);
    }

    List<String> admissionRuntimeLeaseKeys() {
        List<String> leases = new ArrayList<>(physicalShardCount);
        for (int shard = 0; shard < physicalShardCount; shard++) {
            leases.add(prefix(shard) + "runtime:admission-leases");
        }
        return List.copyOf(leases);
    }

    String activeAdmissionGateValue() {
        return routingGateValue("ACTIVE");
    }

    String drainingAdmissionGateValue() {
        return routingGateValue("DRAINING");
    }

    String routingControlValue(String state, String revision) {
        String normalizedState = normalizeRoutingState(state);
        String normalizedRevision = Objects.requireNonNull(revision, "revision").trim();
        if (!normalizedRevision.matches("[A-Za-z0-9_-]{8,64}")) {
            throw new IllegalArgumentException("Invalid Commerce routing revision");
        }
        return String.join(
                "|",
                ROUTING_RECORD_VERSION,
                normalizedState,
                routingGeneration,
                String.valueOf(physicalShardCount),
                normalizedRevision
        );
    }

    String restoreMarker(long voucherId, String sourceId) {
        return prefix(voucherId) + "seckill:restore:" + sourceId;
    }

    String compensationMarker(long voucherId, String sourceId) {
        return prefix(voucherId) + "seckill:compensation:" + sourceId;
    }

    String stream(long voucherId) {
        return streams.get(physicalShard(voucherId));
    }

    List<String> streams() {
        return streams;
    }

    String recoverySourceId(String stream, String recordId) {
        Integer shard = streamShards.get(stream);
        if (shard == null) {
            throw new IllegalArgumentException("Unknown Commerce shard stream: " + stream);
        }
        return routingGeneration + ":" + physicalShardCount + ":" + shard + ":" + recordId;
    }

    private String prefix(long voucherId) {
        return prefix(physicalShard(voucherId));
    }

    private String prefix(int shard) {
        return "commerce:" + hashTag(shard) + ":";
    }

    private String hashTag(int shard) {
        return "{commerce:" + routingGeneration + ":" + physicalShardCount + ":" + shard + "}";
    }

    private String routingGateValue(String state) {
        return String.join(
                "|",
                ROUTING_RECORD_VERSION,
                normalizeRoutingState(state),
                routingGeneration,
                String.valueOf(physicalShardCount)
        );
    }

    private String normalizeRoutingState(String state) {
        String normalized = Objects.requireNonNull(state, "state").trim().toUpperCase(Locale.ROOT);
        if (!"ACTIVE".equals(normalized) && !"DRAINING".equals(normalized)) {
            throw new IllegalArgumentException("Invalid Commerce routing state: " + state);
        }
        return normalized;
    }
}
