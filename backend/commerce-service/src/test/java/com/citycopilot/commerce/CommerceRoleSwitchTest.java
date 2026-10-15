package com.citycopilot.commerce;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.mockingDetails;

class CommerceRoleSwitchTest {
    @Test
    void splitOrderServicesWireWithoutCyclesAndDisabledWorkerDoesNotTouchRedis() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        new ApplicationContextRunner()
                .withUserConfiguration(CommerceOrderService.class, CommerceOrderAdmissionService.class,
                        CommerceOrderStreamWorker.class, CommerceOrderRecoveryStore.class)
                .withPropertyValues("citycopilot.commerce.stream-worker-enabled=false")
                .withBean(JdbcTemplate.class, () -> mock(JdbcTemplate.class))
                .withBean(StringRedisTemplate.class, () -> redis)
                .withBean(TransactionTemplate.class, () -> mock(TransactionTemplate.class))
                .withBean(ObjectMapper.class, () -> JsonMapper.builderWithJackson2Defaults().build())
                .withBean(CommerceAdmissionDurabilityService.class, () -> mock(CommerceAdmissionDurabilityService.class))
                .withBean(CommerceOrderIdGenerator.class, () -> mock(CommerceOrderIdGenerator.class))
                .withBean(CommerceRedisReplicationExecutor.class, () -> mock(CommerceRedisReplicationExecutor.class))
                .withInitializer(context -> context.getBeanFactory()
                        .registerSingleton("commerceReadCache", mock(CommerceReadCache.class)))
                .withBean(CommerceRedisKeyspace.class, () -> new CommerceRedisKeyspace(1, "v1"))
                .withBean(PlaceAuthorityClient.class, () -> mock(PlaceAuthorityClient.class))
                .run(context -> {
                    assertThat(context).hasSingleBean(CommerceOrderService.class)
                            .hasSingleBean(CommerceOrderAdmissionService.class)
                            .hasSingleBean(CommerceOrderStreamWorker.class);
                    context.getBean(CommerceOrderStreamWorker.class).recoverDurableAdmissions();
                    assertThat(mockingDetails(redis).getInvocations())
                            .extracting(invocation -> invocation.getMethod().getName())
                            .containsOnly("setBeanClassLoader", "afterPropertiesSet");
                    verifyNoInteractions(context.getBean(CommerceAdmissionDurabilityService.class));
                });
    }

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
        assertThat(properties.getProperty("spring.data.redis.cluster.nodes")).isNull();
        assertThat(properties.getProperty("spring.task.scheduling.shutdown.await-termination")).isEqualTo(true);
        assertThat(properties.getProperty("spring.task.scheduling.shutdown.await-termination-period")).isEqualTo("${CITYCOPILOT_COMMERCE_SCHEDULER_SHUTDOWN_TIMEOUT:25s}");
        assertThat(properties.getProperty("spring.task.scheduling.pool.size")).isEqualTo("${CITYCOPILOT_COMMERCE_SCHEDULER_POOL_SIZE:4}");
        assertThat(properties.getProperty("citycopilot.commerce.redis-physical-shard-count")).isEqualTo("${CITYCOPILOT_COMMERCE_REDIS_PHYSICAL_SHARD_COUNT:16}");
        assertThat(properties.getProperty("citycopilot.commerce.redis-routing-generation")).isEqualTo("${CITYCOPILOT_COMMERCE_REDIS_ROUTING_GENERATION:v1}");
        assertThat(properties.getProperty("citycopilot.commerce.redis-required-replicas")).isEqualTo("${CITYCOPILOT_COMMERCE_REDIS_REQUIRED_REPLICAS:1}");
        assertThat(properties.getProperty("citycopilot.commerce.redis-replication-timeout-ms")).isEqualTo("${CITYCOPILOT_COMMERCE_REDIS_REPLICATION_TIMEOUT_MS:1000}");
        assertThat(properties.getProperty("management.endpoint.health.group.readiness.include")).isEqualTo("readinessState,db,redis");
    }

    private void assertSwitch(Class<?> type, String property) {
        ConditionalOnProperty condition = type.getAnnotation(ConditionalOnProperty.class);
        assertThat(condition).isNotNull();
        assertThat(condition.prefix()).isEqualTo("citycopilot.commerce");
        assertThat(condition.name()).containsExactly(property);
        assertThat(condition.havingValue()).isEqualTo("true");
    }
}
