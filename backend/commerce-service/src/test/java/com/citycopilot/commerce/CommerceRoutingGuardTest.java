package com.citycopilot.commerce;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CommerceRoutingGuardTest {
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> values = mock(ValueOperations.class);
    private final CommerceRedisKeyspace keyspace = new CommerceRedisKeyspace(4, "g1");
    private CommerceRoutingGuard guard;

    @BeforeEach
    void setUp() {
        when(redis.opsForValue()).thenReturn(values);
        guard = new CommerceRoutingGuard(redis, keyspace, false);
    }

    @Test
    void activeMatchingControlAndShardGatePermitAdmission() {
        when(values.get(CommerceRedisKeyspace.ROUTING_CONTROL_KEY))
                .thenReturn("v1|ACTIVE|g1|4|revision01");
        when(values.get(keyspace.admissionRoutingGate(42L)))
                .thenReturn(keyspace.activeAdmissionGateValue());

        assertThatCode(() -> guard.requireAdmissionActive(42L)).doesNotThrowAnyException();
    }

    @Test
    void drainingControlRejectsAdmissionBeforeTheShardLua() {
        when(values.get(CommerceRedisKeyspace.ROUTING_CONTROL_KEY))
                .thenReturn("v1|DRAINING|g1|4|revision02");

        assertThatThrownBy(() -> guard.requireAdmissionActive(42L))
                .isInstanceOfSatisfying(
                        CommerceApiException.class,
                        error -> assertThat(error.code()).isEqualTo("COMMERCE_ROUTING_DRAINING")
                );
    }

    @Test
    void oldReplicaRejectsAControlRecordForTheNewFingerprint() {
        when(values.get(CommerceRedisKeyspace.ROUTING_CONTROL_KEY))
                .thenReturn("v1|ACTIVE|g2|8|revision03");

        assertThatThrownBy(() -> guard.requireAdmissionActive(42L))
                .isInstanceOfSatisfying(
                        CommerceApiException.class,
                        error -> assertThat(error.code()).isEqualTo("COMMERCE_ROUTING_CONFIG_MISMATCH")
                );
    }

    @Test
    void missingControlFailsClosedWhenProductionAutoInitializationIsDisabled() {
        when(values.get(CommerceRedisKeyspace.ROUTING_CONTROL_KEY)).thenReturn(null);

        assertThatThrownBy(() -> guard.requireAdmissionActive(42L))
                .isInstanceOfSatisfying(
                        CommerceApiException.class,
                        error -> assertThat(error.code()).isEqualTo("COMMERCE_ROUTING_UNAVAILABLE")
                );
    }

    @Test
    void developmentInitializationUsesSetIfAbsentAndNeverOverwritesAnotherFingerprint() {
        CommerceRoutingGuard developmentGuard = new CommerceRoutingGuard(redis, keyspace, true);
        when(values.get(CommerceRedisKeyspace.ROUTING_CONTROL_KEY))
                .thenReturn(null, "v1|ACTIVE|g1|4|revision04");
        when(values.setIfAbsent(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString())).thenReturn(true);

        developmentGuard.initializeLocalRoutingForDevelopment();

        verify(values).setIfAbsent(
                org.mockito.ArgumentMatchers.eq(CommerceRedisKeyspace.ROUTING_CONTROL_KEY),
                org.mockito.ArgumentMatchers.startsWith("v1|ACTIVE|g1|4|")
        );
        assertThat(keyspace.admissionRoutingGates())
                .allSatisfy(gate -> verify(values).setIfAbsent(
                        gate,
                        keyspace.activeAdmissionGateValue()
                ));
    }
}
