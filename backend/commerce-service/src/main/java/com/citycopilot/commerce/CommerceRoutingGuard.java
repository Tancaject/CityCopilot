package com.citycopilot.commerce;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/**
 * Fail-closed admission guard for the versioned Commerce Redis keyspace.
 *
 * <p>The global control record coordinates every API replica. A second gate in
 * each logical shard is checked again inside the admission Lua script, in the
 * same Redis Cluster slot as the voucher keys. The second check closes the
 * race between an API reading ACTIVE and an operator beginning a drain.</p>
 */
@Component
final class CommerceRoutingGuard {
    private static final Logger log = LoggerFactory.getLogger(CommerceRoutingGuard.class);

    private final StringRedisTemplate redis;
    private final CommerceRedisKeyspace keyspace;
    private final boolean autoInitialize;

    CommerceRoutingGuard(
            StringRedisTemplate redis,
            CommerceRedisKeyspace keyspace,
            @Value("${citycopilot.commerce.routing-control-auto-initialize:true}")
            boolean autoInitialize
    ) {
        this.redis = redis;
        this.keyspace = keyspace;
        this.autoInitialize = autoInitialize;
    }

    @PostConstruct
    void initializeLocalRoutingForDevelopment() {
        if (!autoInitialize) return;
        try {
            ValueOperations<String, String> values = redis.opsForValue();
            RoutingSnapshot current = parse(values.get(CommerceRedisKeyspace.ROUTING_CONTROL_KEY));
            boolean shardGatesInitialized = false;
            if (current == null) {
                initializeMissingShardGates(values);
                shardGatesInitialized = true;
                values.setIfAbsent(
                        CommerceRedisKeyspace.ROUTING_CONTROL_KEY,
                        keyspace.routingControlValue("ACTIVE", newRevision())
                );
                current = parse(values.get(CommerceRedisKeyspace.ROUTING_CONTROL_KEY));
            }
            if (current != null && current.isActiveFor(keyspace) && !shardGatesInitialized) {
                initializeMissingShardGates(values);
            } else if (current != null && !current.matches(keyspace)) {
                log.error(
                        "Commerce routing control belongs to generation {} with {} shards; "
                                + "local generation {} with {} shards remains fail-closed",
                        current.generation(),
                        current.physicalShardCount(),
                        keyspace.routingGeneration(),
                        keyspace.physicalShardCount()
                );
            }
        } catch (RuntimeException failure) {
            // Startup must remain observable so readiness can report the
            // control-plane failure. Every admission still fails closed.
            log.error("Unable to initialize Commerce routing control; admission remains closed", failure);
        }
    }

    void requireAdmissionActive(long voucherId) {
        if (voucherId <= 0) {
            throw new CommerceApiException(
                    "COMMERCE_INVALID_ARGUMENT",
                    "voucherId must be positive",
                    400
            );
        }
        RoutingSnapshot current;
        try {
            current = parse(redis.opsForValue().get(CommerceRedisKeyspace.ROUTING_CONTROL_KEY));
        } catch (RuntimeException failure) {
            throw unavailable();
        }
        if (current == null) {
            throw unavailable();
        }
        if (!current.matches(keyspace)) {
            throw new CommerceApiException(
                    "COMMERCE_ROUTING_CONFIG_MISMATCH",
                    "This Commerce replica does not own the active Redis routing generation",
                    503
            );
        }
        if (current.state() == RoutingState.DRAINING) {
            throw new CommerceApiException(
                    "COMMERCE_ROUTING_DRAINING",
                    "Commerce order admission is draining for a routing migration",
                    503
            );
        }
        try {
            String shardGate = redis.opsForValue().get(keyspace.admissionRoutingGate(voucherId));
            if (!keyspace.activeAdmissionGateValue().equals(shardGate)) {
                throw new CommerceApiException(
                        "COMMERCE_ROUTING_NOT_ACTIVE",
                        "Commerce order admission is not active for this Redis shard",
                        503
                );
            }
        } catch (CommerceApiException rejected) {
            throw rejected;
        } catch (RuntimeException failure) {
            throw unavailable();
        }
    }

    void requireRuntimeMutationActive() {
        RoutingSnapshot current;
        try {
            current = parse(redis.opsForValue().get(CommerceRedisKeyspace.ROUTING_CONTROL_KEY));
        } catch (RuntimeException failure) {
            throw unavailable();
        }
        if (current == null) throw unavailable();
        if (!current.matches(keyspace)) {
            throw new CommerceApiException(
                    "COMMERCE_ROUTING_CONFIG_MISMATCH",
                    "This Commerce replica does not own the active Redis routing generation",
                    503
            );
        }
        if (current.state() != RoutingState.ACTIVE) {
            throw new CommerceApiException(
                    "COMMERCE_ROUTING_DRAINING",
                    "Commerce mutations are draining for a routing migration",
                    503
            );
        }
        try {
            if (!localShardGatesMatch(RoutingState.ACTIVE)) {
                throw new CommerceApiException(
                        "COMMERCE_ROUTING_NOT_ACTIVE",
                        "Commerce mutation routing is not active on every Redis shard",
                        503
                );
            }
        } catch (CommerceApiException rejected) {
            throw rejected;
        } catch (RuntimeException failure) {
            throw unavailable();
        }
    }

    RoutingSnapshot current() {
        return parse(redis.opsForValue().get(CommerceRedisKeyspace.ROUTING_CONTROL_KEY));
    }

    boolean localShardGatesMatch(RoutingState state) {
        String expected = state == RoutingState.ACTIVE
                ? keyspace.activeAdmissionGateValue()
                : keyspace.drainingAdmissionGateValue();
        ValueOperations<String, String> values = redis.opsForValue();
        for (String gate : keyspace.admissionRoutingGates()) {
            if (!expected.equals(values.get(gate))) return false;
        }
        return true;
    }

    boolean isLocal(RoutingSnapshot snapshot) {
        return snapshot != null && snapshot.matches(keyspace);
    }

    private void initializeMissingShardGates(ValueOperations<String, String> values) {
        String active = keyspace.activeAdmissionGateValue();
        for (String gate : keyspace.admissionRoutingGates()) {
            values.setIfAbsent(gate, active);
        }
    }

    static RoutingSnapshot parse(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String[] fields = raw.split("\\|", -1);
        if (fields.length != 5 || !"v1".equals(fields[0])) {
            throw new IllegalStateException("Commerce routing control record is malformed");
        }
        RoutingState state;
        try {
            state = RoutingState.valueOf(fields[1].toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException invalid) {
            throw new IllegalStateException("Commerce routing control state is invalid", invalid);
        }
        int shardCount;
        try {
            shardCount = Integer.parseInt(fields[3]);
        } catch (NumberFormatException invalid) {
            throw new IllegalStateException("Commerce routing shard count is invalid", invalid);
        }
        if (fields[2].isBlank()
                || shardCount < 1
                || shardCount > 1024
                || fields[4].isBlank()) {
            throw new IllegalStateException("Commerce routing control record is invalid");
        }
        return new RoutingSnapshot(state, fields[2], shardCount, fields[4]);
    }

    private CommerceApiException unavailable() {
        return new CommerceApiException(
                "COMMERCE_ROUTING_UNAVAILABLE",
                "Commerce routing control is unavailable; order admission is closed",
                503
        );
    }

    private String newRevision() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    enum RoutingState {
        ACTIVE,
        DRAINING
    }

    record RoutingSnapshot(
            RoutingState state,
            String generation,
            int physicalShardCount,
            String revision
    ) {
        RoutingSnapshot {
            Objects.requireNonNull(state, "state");
            Objects.requireNonNull(generation, "generation");
            Objects.requireNonNull(revision, "revision");
        }

        boolean matches(CommerceRedisKeyspace keyspace) {
            return generation.equals(keyspace.routingGeneration())
                    && physicalShardCount == keyspace.physicalShardCount();
        }

        boolean isActiveFor(CommerceRedisKeyspace keyspace) {
            return state == RoutingState.ACTIVE && matches(keyspace);
        }
    }
}
