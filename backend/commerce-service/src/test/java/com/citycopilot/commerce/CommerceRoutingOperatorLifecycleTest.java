package com.citycopilot.commerce;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CommerceRoutingOperatorLifecycleTest {
    @Test
    @SuppressWarnings("unchecked")
    void statusRunnerCompletesWithoutTakingTheExclusiveOperatorLease() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        CommerceRedisReplicationExecutor replicatedRedis =
                mock(CommerceRedisReplicationExecutor.class);
        CommerceRuntimeMutationLeases runtimeLeases =
                mock(CommerceRuntimeMutationLeases.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.get(CommerceRedisKeyspace.ROUTING_CONTROL_KEY)).thenReturn(null);
        CommerceRoutingOperator operator = new CommerceRoutingOperator(
                jdbc,
                redis,
                replicatedRedis,
                runtimeLeases,
                new CommerceRedisKeyspace(2, "v1"),
                JsonMapper.builderWithJackson2Defaults().build(),
                "status",
                "",
                0
        );

        operator.run(null);

        assertThat(operator.completed()).isTrue();
        verify(runtimeLeases, never()).acquireOperator();
    }
}
