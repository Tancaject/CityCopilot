package com.citycopilot.commerce;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class CommerceOutboxArchiveConfigurationTest {
    @Test
    void archivalRequiresAnExplicitOnlineReplayWindowAndOptIn() {
        var runner = new ApplicationContextRunner().withUserConfiguration(CommerceOutboxArchive.class)
                .withBean(JdbcTemplate.class, () -> mock(JdbcTemplate.class))
                .withBean(TransactionTemplate.class, () -> mock(TransactionTemplate.class));
        runner.run(context -> assertThat(context).doesNotHaveBean(CommerceOutboxArchive.class));
        runner.withPropertyValues("citycopilot.commerce.outbox-archive.enabled=true")
                .run(context -> assertThat(context.getStartupFailure()).hasRootCauseMessage(
                        "Outbox archival requires citycopilot.commerce.outbox-archive.retention-days >= 0; set the online replay window before enabling archival"));
        runner.withPropertyValues("citycopilot.commerce.outbox-archive.enabled=true",
                        "citycopilot.commerce.outbox-archive.retention-days=0")
                .run(context -> assertThat(context).hasSingleBean(CommerceOutboxArchive.class));
    }
}
