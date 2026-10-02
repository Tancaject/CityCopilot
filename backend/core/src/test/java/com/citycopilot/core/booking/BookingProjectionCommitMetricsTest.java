package com.citycopilot.core.booking;

import com.citycopilot.core.event.BookingProjectionMetrics;
import com.citycopilot.core.event.InboxOutcome;
import com.citycopilot.core.event.InboxRepository;
import io.micrometer.core.instrument.MockClock;
import io.micrometer.core.instrument.simple.SimpleConfig;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import javax.sql.DataSource;
import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class BookingProjectionCommitMetricsTest {
    @Test
    void actualTransactionCommitControlsObservationAndRetriesDoNotAddProjectionSamples() throws Exception {
        var clock = new MockClock();
        clock.add(Duration.ofHours(1));
        var registry = new SimpleMeterRegistry(SimpleConfig.DEFAULT, clock);
        var json = JsonMapper.builderWithJackson2Defaults().findAndAddModules().build();
        var dataSource = mock(DataSource.class);
        var connection = mock(Connection.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.getAutoCommit()).thenReturn(true);
        var transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        var inbox = mock(InboxRepository.class);
        when(inbox.process(anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), anyLong()))
                .thenReturn(InboxOutcome.PROCESSED);
        var feedback = mock(BookingFeedbackService.class);
        var observations = new BookingProjectionMetrics(registry);
        var projector = new BookingBehaviorProjector(inbox, feedback,
                new BookingFeedbackPolicy(true, 30, 60, 3600000, "metrics-check"),
                transactions, mock(JdbcTemplate.class), json, observations);
        ObjectNode event;
        try (var stream = getClass().getResourceAsStream("/booking/commerce-held-outbox-v1.json")) {
            event = (ObjectNode) json.readTree(stream.readAllBytes());
        }
        event.put("occurredAt", Instant.ofEpochMilli(clock.wallTime()).minusSeconds(60).toString());
        byte[] body = json.writeValueAsBytes(event);
        var timer = registry.get("citycopilot.booking.projection.delay").timer();
        try {
            assertThatThrownBy(() -> observations.classifiedAfterCommit(InboxOutcome.PROCESSED, Instant.now()))
                    .hasMessage("Booking projection metrics require the business transaction");
            transactions.executeWithoutResult(status -> {
                assertThat(projector.process(body)).isEqualTo(InboxOutcome.PROCESSED);
                assertThat(timer.count()).isZero();
                status.setRollbackOnly();
            });
            assertThat(timer.count()).isZero();
            assertThat(registry.get("citycopilot.booking.business.consume").tag("outcome", "processed").counter().count()).isZero();
            verify(connection).rollback();

            transactions.executeWithoutResult(status -> {
                projector.process(body);
                clock.add(Duration.ofSeconds(30));
                assertThat(timer.count()).isZero();
            });
            verify(connection).commit();
            assertThat(timer.count()).isEqualTo(1);
            assertThat(timer.totalTime(TimeUnit.SECONDS)).isEqualTo(90);

            for (InboxOutcome ignored : new InboxOutcome[]{InboxOutcome.DUPLICATE, InboxOutcome.QUARANTINED}) {
                when(inbox.process(anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), anyLong()))
                        .thenReturn(ignored);
                assertThat(projector.process(body)).isEqualTo(ignored);
                assertThat(timer.count()).isEqualTo(1);
            }
            when(inbox.process(anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), anyLong()))
                    .thenReturn(InboxOutcome.STALE);
            assertThat(projector.process(body)).isEqualTo(InboxOutcome.STALE);
            assertThat(timer.count()).isEqualTo(2);

            doThrow(new IllegalStateException("projection write rejected")).when(feedback)
                    .recordBusinessEvent(anyString(), anyString(), anyLong(), any(), any());
            assertThatThrownBy(() -> projector.process(body)).hasMessage("projection write rejected");
            verify(connection, times(2)).rollback();
            assertThat(timer.count()).isEqualTo(2);
            doNothing().when(feedback).recordBusinessEvent(anyString(), anyString(), anyLong(), any(), any());
            event.put("occurredAt", Instant.ofEpochMilli(clock.wallTime()).plusSeconds(10).toString());
            projector.process(json.writeValueAsBytes(event));
            assertThat(registry.get("citycopilot.booking.projection.clock.skew").counter().count()).isEqualTo(1);
            assertThat(timer.count()).isEqualTo(2);
            assertThat(registry.get("citycopilot.booking.projection.last.delay").gauge().value()).isNaN();
        } finally {
            registry.close();
        }
    }
}
