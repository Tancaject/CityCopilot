package com.citycopilot.core.service;

import com.baomidou.mybatisplus.spring.service.IService;
import com.citycopilot.core.CityCopilotCoreApplication;
import com.citycopilot.core.commerce.CommercePortClient;
import com.citycopilot.core.service.impl.VoucherOrderServiceImpl;
import com.citycopilot.core.service.impl.VoucherServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class CommerceFacadeIsolationTest {

    @Test
    void orderAndVoucherContractsNoLongerExposeLocalPersistence() {
        assertThat(IService.class.isAssignableFrom(IVoucherOrderService.class)).isFalse();
        assertThat(IService.class.isAssignableFrom(IVoucherService.class)).isFalse();
    }

    @Test
    void currentCommerceFacadesDependOnlyOnTheRemotePort() {
        assertOnlyCommercePort(VoucherOrderServiceImpl.class);
        assertOnlyCommercePort(VoucherServiceImpl.class);
        // 运维门面额外写 Core 自己的审计日志，但仍不触碰任何交易存储。
        assertOnlyCommercePort(SeckillConsistencyService.class, OperationLogService.class);
        assertOnlyCommercePort(SeckillRecoveryService.class, OperationLogService.class);
    }

    @Test
    void coreDoesNotScheduleASecondCommerceWorker() {
        assertThat(CityCopilotCoreApplication.class.getAnnotation(EnableScheduling.class)).isNull();
    }

    @Test
    void defaultCoreSchemaDoesNotCreateLegacyCommerceTables() throws IOException {
        Path projectRoot = findProjectRoot();
        String defaultSchema = Files.readString(projectRoot.resolve("deploy/mysql/core/V1__core_schema.sql"), StandardCharsets.UTF_8);
        List<String> legacyTables = List.of(
                "coupon",
                "seckill_coupon",
                "coupon_order",
                "seckill_reservation",
                "outbox_event",
                "outbox_fence"
        );

        for (String table : legacyTables) {
            assertThat(defaultSchema)
                    .doesNotContain("CREATE TABLE `" + table + "`")
                    .doesNotContain("CREATE TABLE IF NOT EXISTS `" + table + "`");
        }
        assertThat(defaultSchema).contains("CREATE TABLE `processed_event`");
        assertThat(defaultSchema).contains("CREATE TABLE `event_projection_version`");
        assertThat(projectRoot.resolve("deploy/mysql/legacy")).doesNotExist();
        assertThat(projectRoot.resolve("tools/legacy")).doesNotExist();
    }

    private void assertOnlyCommercePort(Class<?> type, Class<?>... audit) {
        Class<?>[] allowed = new Class<?>[audit.length + 1];
        allowed[0] = CommercePortClient.class;
        System.arraycopy(audit, 0, allowed, 1, audit.length);
        Set<Class<?>> dependencies = Arrays.stream(type.getDeclaredFields())
                .filter(field -> !field.isSynthetic())
                .map(Field::getType)
                .collect(Collectors.toSet());

        assertThat(type.getSuperclass()).isEqualTo(Object.class);
        assertThat(dependencies).containsExactlyInAnyOrder(allowed);
        assertThat(type.getDeclaredConstructors()).singleElement().satisfies(constructor ->
                assertThat(constructor.getParameterTypes())
                        .containsExactly(allowed)
        );
    }

    private Path findProjectRoot() {
        Path candidate = Path.of("").toAbsolutePath();
        while (candidate != null && !Files.isRegularFile(candidate.resolve("compose.yaml"))) {
            candidate = candidate.getParent();
        }
        assertThat(candidate)
                .as("project root containing compose.yaml")
                .isNotNull();
        return candidate;
    }
}
