package com.citycopilot.commerce;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CommerceRoleSwitchTest {
    @Test
    void independentlyConditionsApiRelayAndReconciliationRoles() {
        assertSwitch(CommerceController.class, "api-enabled");
        assertSwitch(CommerceOutboxRelay.class, "outbox-relay-enabled");
        assertSwitch(CommerceReconciliationJob.class, "reconciliation-enabled");
    }

    @Test
    void productionProfileRequiresDeploymentToSelectExactlyItsRole() throws Exception {
        List<PropertySource<?>> loaded = new YamlPropertySourceLoader().load(
                "commerce-production",
                new ClassPathResource("application-production.yaml")
        );
        PropertySource<?> properties = loaded.get(0);

        assertThat(properties.getProperty("server.shutdown")).isEqualTo("graceful");
        assertThat(properties.getProperty("spring.sql.init.mode")).isEqualTo("never");
        assertThat(properties.getProperty("citycopilot.commerce.api-enabled")).isEqualTo("${CITYCOPILOT_COMMERCE_API_ENABLED:false}");
        assertThat(properties.getProperty("citycopilot.commerce.stream-worker-enabled")).isEqualTo("${CITYCOPILOT_COMMERCE_STREAM_WORKER_ENABLED:false}");
        assertThat(properties.getProperty("citycopilot.commerce.outbox-relay-enabled")).isEqualTo("${CITYCOPILOT_COMMERCE_OUTBOX_RELAY_ENABLED:false}");
        assertThat(properties.getProperty("citycopilot.commerce.reconciliation-enabled")).isEqualTo("${CITYCOPILOT_COMMERCE_RECONCILIATION_ENABLED:false}");
        assertThat(properties.getProperty("citycopilot.commerce.inventory-bootstrap-enabled")).isEqualTo("${CITYCOPILOT_COMMERCE_INVENTORY_BOOTSTRAP_ENABLED:false}");
        assertThat(properties.getProperty("spring.data.redis.cluster.nodes")).isEqualTo("${CITYCOPILOT_REDIS_CLUSTER_NODES}");
        assertThat(properties.getProperty("spring.task.scheduling.shutdown.await-termination")).isEqualTo(true);
        assertThat(properties.getProperty("spring.task.scheduling.shutdown.await-termination-period")).isEqualTo("${CITYCOPILOT_COMMERCE_SCHEDULER_SHUTDOWN_TIMEOUT:25s}");
        assertThat(properties.getProperty("spring.task.scheduling.pool.size")).isEqualTo("${CITYCOPILOT_COMMERCE_SCHEDULER_POOL_SIZE:4}");
        assertThat(properties.getProperty("citycopilot.commerce.redis-physical-shard-count")).isEqualTo("${CITYCOPILOT_COMMERCE_REDIS_PHYSICAL_SHARD_COUNT:16}");
        assertThat(properties.getProperty("citycopilot.commerce.redis-routing-generation")).isEqualTo("${CITYCOPILOT_COMMERCE_REDIS_ROUTING_GENERATION:v1}");
        assertThat(properties.getProperty("citycopilot.commerce.routing-control-auto-initialize")).isEqualTo("${CITYCOPILOT_COMMERCE_ROUTING_CONTROL_AUTO_INITIALIZE:false}");
        assertThat(properties.getProperty("citycopilot.commerce.redis-required-replicas")).isEqualTo("${CITYCOPILOT_COMMERCE_REDIS_REQUIRED_REPLICAS:1}");
        assertThat(properties.getProperty("citycopilot.commerce.redis-replication-timeout-ms")).isEqualTo("${CITYCOPILOT_COMMERCE_REDIS_REPLICATION_TIMEOUT_MS:1000}");
        assertThat(properties.getProperty("management.endpoint.health.group.readiness.include")).isEqualTo("readinessState,db,redis,commerceRouting");
    }

    private void assertSwitch(Class<?> type, String property) {
        ConditionalOnProperty condition = type.getAnnotation(ConditionalOnProperty.class);
        assertThat(condition).isNotNull();
        assertThat(condition.prefix()).isEqualTo("citycopilot.commerce");
        assertThat(condition.name()).containsExactly(property);
        assertThat(condition.havingValue()).isEqualTo("true");
    }
}
