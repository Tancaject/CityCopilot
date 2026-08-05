package com.citycopilot.agent.ops;

import com.citycopilot.agent.ops.v2.OpsCaseWorker;
import com.citycopilot.agent.ops.v2.OpsEventConsumer;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.AnnotatedElementUtils;

import static org.assertj.core.api.Assertions.assertThat;

class AgentWorkerRoleConditionTest {

    @Test
    void scheduledWorkersHaveIndependentRuntimeSwitches() {
        assertCondition(OpsAgentWorker.class, "citycopilot.ops", "worker-enabled");
        assertCondition(OpsCaseWorker.class, "citycopilot.ops-v2", "worker-enabled");
        assertCondition(OpsEventConsumer.class, "citycopilot.rocketmq", "consumer-enabled");
    }

    private void assertCondition(Class<?> type, String prefix, String name) {
        ConditionalOnProperty condition = AnnotatedElementUtils.findMergedAnnotation(
                type,
                ConditionalOnProperty.class
        );
        assertThat(condition).isNotNull();
        assertThat(condition.prefix()).isEqualTo(prefix);
        assertThat(condition.name()).containsExactly(name);
        assertThat(condition.havingValue()).isEqualTo("true");
        assertThat(condition.matchIfMissing()).isTrue();
    }
}
