package com.citycopilot.core.booking;

import com.citycopilot.contract.event.BookingChangedEvent;
import com.citycopilot.core.event.BookingProjectionMetrics;
import com.citycopilot.core.event.InboxOutcome;
import com.citycopilot.core.event.InboxRepository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;

/** Inbox 登记与业务事实、特征、当前状态一同提交；旧版本事实仍然入账。 */
@Service
public class BookingBehaviorProjector {
    private static final Map<String, String> STATES = Map.of("held", "HELD", "confirmed", "CONFIRMED",
            "rescheduled", "CONFIRMED", "used", "USED", "cancelled", "CANCELLED", "hold_expired", "HOLD_EXPIRED", "no_show", "NO_SHOW");
    private final InboxRepository inbox;
    private final BookingFeedbackService feedback;
    private final BookingFeedbackPolicy policy;
    private final TransactionTemplate transactions;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final BookingProjectionMetrics metrics;

    public BookingBehaviorProjector(InboxRepository inbox, BookingFeedbackService feedback, BookingFeedbackPolicy policy,
                                    TransactionTemplate transactions, JdbcTemplate jdbc, ObjectMapper json, BookingProjectionMetrics metrics) {
        this.inbox = inbox; this.feedback = feedback; this.policy = policy;
        this.transactions = transactions; this.jdbc = jdbc;
        this.json = json; this.metrics = metrics;
    }

    public InboxOutcome process(byte[] body) {
        final JsonNode envelope;
        try { envelope = json.readTree(body); }
        catch (Exception error) { throw new IllegalArgumentException("Invalid booking event JSON", error); }
        String eventId = required(envelope, "eventId"), eventType = required(envelope, "eventType");
        if (!UUID.fromString(eventId).toString().equals(eventId)) throw new IllegalArgumentException("Invalid booking event ID");
        String aggregateId = required(envelope, "aggregateId");
        long version = nonNegativeInteger(envelope, "aggregateVersion");
        String hash = sha256(body);
        return transactions.execute(tx -> {
            InboxOutcome outcome = inbox.process(policy.consumerGroup(), eventId, hash, eventType, "commerce_booking", aggregateId, version);
            if (outcome == InboxOutcome.QUARANTINED) {
                jdbc.update("""
                        UPDATE processed_event SET quarantine_reason=CONCAT('payload hash conflict: original=',payload_hash,', incoming=',?)
                        WHERE consumer_group=? AND event_id=?
                        """, hash, policy.consumerGroup(), eventId);
                metrics.classifiedAfterCommit(outcome, null);
                return outcome;
            }
            if (outcome == InboxOutcome.DUPLICATE) {
                metrics.classifiedAfterCommit(outcome, null);
                return outcome;
            }
            if (!"commerce_booking".equals(required(envelope, "aggregateType"))
                    || !"commerce-service".equals(required(envelope, "sourceService"))) throw new IllegalArgumentException("Unexpected booking event source");
            if (nonNegativeInteger(envelope, "schemaVersion") != 1) throw new IllegalArgumentException("Unsupported booking event version");
            String action = eventType.startsWith("commerce.booking.") ? eventType.substring("commerce.booking.".length()) : "";
            if (!STATES.containsKey(action)) throw new IllegalArgumentException("Unsupported booking event type: " + eventType);
            BookingChangedEvent event;
            nonNegativeInteger(envelope.path("payload"), "scheduleVersion");
            try { event = json.treeToValue(envelope.path("payload"), BookingChangedEvent.class); }
            catch (Exception error) { throw new IllegalArgumentException("Invalid booking event payload", error); }
            if (event == null || !aggregateId.equals(event.bookingId()) || !STATES.get(action).equals(event.status())) {
                throw new IllegalArgumentException("Booking envelope and payload disagree");
            }
            Instant occurredAt;
            try { occurredAt = json.treeToValue(envelope.get("occurredAt"), Instant.class); }
            catch (Exception error) { throw new IllegalArgumentException("Invalid booking event occurredAt", error); }
            if (occurredAt == null) throw new IllegalArgumentException("Missing booking event occurredAt");
            feedback.recordBusinessEvent(eventId, action.toUpperCase(java.util.Locale.ROOT), version, occurredAt, event);
            metrics.classifiedAfterCommit(outcome, occurredAt);
            return outcome;
        });
    }

    private static String required(JsonNode value, String field) {
        JsonNode node = value.get(field);
        if (node == null || node.isNull() || !node.isTextual() || node.asText().isBlank()) throw new IllegalArgumentException("Booking event field required: " + field);
        return node.asText();
    }
    private static long nonNegativeInteger(JsonNode value, String field) {
        JsonNode node = value.get(field);
        if (node == null || !node.isIntegralNumber() || !node.canConvertToLong() || node.longValue() < 0) {
            throw new IllegalArgumentException("Booking event requires a non-negative integer: " + field);
        }
        return node.longValue();
    }
    private static String sha256(byte[] body) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body)); }
        catch (NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
    }
}
