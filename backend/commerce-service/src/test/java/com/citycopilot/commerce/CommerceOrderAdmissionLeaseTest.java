package com.citycopilot.commerce;

import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CommerceOrderAdmissionLeaseTest {
    @Test
    void shardLeaseAndRoutingRecheckBothPrecedeDurablePrepare() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        CommerceOrderIdGenerator ids = mock(CommerceOrderIdGenerator.class);
        CommerceAdmissionDurabilityService durableAdmissions =
                mock(CommerceAdmissionDurabilityService.class);
        CommerceRedisReplicationExecutor replicatedRedis =
                mock(CommerceRedisReplicationExecutor.class);
        CommerceRuntimeMutationLeases runtimeLeases =
                mock(CommerceRuntimeMutationLeases.class);
        CommerceRuntimeMutationLeases.MutationLease lease =
                mock(CommerceRuntimeMutationLeases.MutationLease.class);
        CommerceRoutingGuard routing = mock(CommerceRoutingGuard.class);
        CommerceAdmissionDurabilityService.Admission durable =
                mock(CommerceAdmissionDurabilityService.Admission.class);
        when(runtimeLeases.acquireAdmission(100L)).thenReturn(lease);
        when(durableAdmissions.prepare(
                100L, 200L, "request-1", "seckill:v1:100", null
        )).thenReturn(durable);
        when(durable.wasAccepted()).thenReturn(true);
        when(durable.orderId()).thenReturn(300L);
        CommerceOrderAdmissionService service = new CommerceOrderAdmissionService(
                jdbc,
                ids,
                durableAdmissions,
                replicatedRedis,
                new CommerceRedisKeyspace(2, "v1"),
                runtimeLeases,
                routing
        );

        assertThat(service.seckill(100L, 200L, "request-1").orderId()).isEqualTo("300");

        InOrder order = inOrder(runtimeLeases, routing, durableAdmissions, lease);
        order.verify(runtimeLeases).acquireAdmission(100L);
        order.verify(routing).requireAdmissionActive(100L);
        order.verify(durableAdmissions).prepare(
                100L, 200L, "request-1", "seckill:v1:100", null
        );
        order.verify(lease).renewAndAssert();
        order.verify(lease).close();
    }
}
