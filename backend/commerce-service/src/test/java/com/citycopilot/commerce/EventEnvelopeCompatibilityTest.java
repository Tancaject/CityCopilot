package com.citycopilot.commerce;

import com.citycopilot.contract.event.BookingChangedEvent;
import com.citycopilot.contract.event.EventEnvelope;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EventEnvelopeCompatibilityTest {
    private final ObjectMapper json = JsonMapper.builderWithJackson2Defaults().findAndAddModules().build();
    private static final String ID = "512bf362-2ef2-4f31-96df-dcfdb826042f";
    private static final String AT = "2026-09-24T10:15:30Z";

    @Test
    void existingWireFieldsAndReplayIdentityRemainStable() throws Exception {
        EventEnvelope<Map<String, Object>> event = new EventEnvelope<>(ID, "commerce.order.used", 1,
                "commerce_order", "9007199254740993", 3, "commerce-service", AT,
                "9007199254740993", Map.of("status", 3, "action", "used"));
        JsonNode wire = json.readTree(json.writeValueAsBytes(event));
        assertThat(wire).isEqualTo(json.readTree("""
                {"eventId":"512bf362-2ef2-4f31-96df-dcfdb826042f","eventType":"commerce.order.used",
                 "schemaVersion":1,"aggregateType":"commerce_order","aggregateId":"9007199254740993",
                 "aggregateVersion":3,"sourceService":"commerce-service","occurredAt":"2026-09-24T10:15:30Z",
                 "correlationId":"9007199254740993","payload":{"status":3,"action":"used"}}
                """));
        EventEnvelope<Map<String, Object>> restored = json.readValue(json.writeValueAsBytes(event), new TypeReference<>() { });
        assertThat(restored).isEqualTo(event);
    }

    @Test
    void typedBookingPayloadKeepsOptionalAttributionAndTimestampShape() throws Exception {
        BookingChangedEvent payload = new BookingChangedEvent("42", "7", "9", "1", "2", 2, 2,
                "HELD", 0, Instant.parse(AT), Instant.parse(AT).plusSeconds(3600),
                Map.of("currency", "CNY", "total", 200), null, null, 9);
        EventEnvelope<BookingChangedEvent> event = new EventEnvelope<>(ID, "commerce.booking.held", 1,
                "commerce_booking", "42", 0, "commerce-service", AT, "42", payload);
        byte[] body = json.writeValueAsBytes(event);
        assertThat(json.readTree(body).get("occurredAt").isTextual()).isTrue();
        EventEnvelope<BookingChangedEvent> restored = json.readValue(body, new TypeReference<>() { });
        assertThat(restored).isEqualTo(event);
    }

    @Test
    void invalidCommonMetadataCannotBePersistedAsAnEvent() {
        assertThatThrownBy(() -> new EventEnvelope<>(ID, " ", 1, "order", "1", 0,
                "commerce-service", AT, "1", Map.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EventEnvelope<>(ID, "order.created", 0, "order", "1", 0,
                "commerce-service", AT, "1", Map.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EventEnvelope<>(ID, "order.created", 1, "order", "1", -1,
                "commerce-service", AT, "1", Map.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EventEnvelope<>(ID, "order.created", 1, "order", "1", 0,
                "commerce-service", "not-a-timestamp", "1", Map.of())).isInstanceOf(java.time.format.DateTimeParseException.class);
    }
}
