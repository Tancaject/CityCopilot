package com.citycopilot.commerce.pricing;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class BookingQuoteCleanupConfigurationTest {
    @Test
    void cleanupIsOptInAndRequiresAnExplicitRetentionPeriod() {
        var context = new ApplicationContextRunner().withUserConfiguration(BookingQuoteCleanup.class)
                .withBean(JdbcTemplate.class, () -> mock(JdbcTemplate.class))
                .withBean(TransactionTemplate.class, () -> mock(TransactionTemplate.class));
        context.run(app -> assertThat(app).doesNotHaveBean(BookingQuoteCleanup.class));
        context.withPropertyValues("citycopilot.booking.quote-cleanup.enabled=true")
                .run(app -> assertThat(app.getStartupFailure()).hasRootCauseMessage(
                        "Quote cleanup requires citycopilot.booking.quote-cleanup.retention-days >= 0; set the retention period after expiry before enabling cleanup"));
        context.withPropertyValues("citycopilot.booking.quote-cleanup.enabled=true",
                        "citycopilot.booking.quote-cleanup.retention-days=0")
                .run(app -> assertThat(app).hasSingleBean(BookingQuoteCleanup.class));
    }
}
