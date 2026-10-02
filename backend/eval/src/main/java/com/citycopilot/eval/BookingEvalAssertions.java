package com.citycopilot.eval;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

/** Scores authoritative tool payloads and queried booking facts, never the model's claim that a task succeeded. */
final class BookingEvalAssertions {
    private static final ObjectMapper JSON = JsonMapper.builder().build();
    static List<String> check(EvalCase evalCase, EvalPrediction prediction) {
        if (evalCase.booking().isEmpty()) return List.of();
        List<String> failures = new ArrayList<>();
        JsonNode expected = JSON.valueToTree(evalCase.booking().get("assertions"));
        JsonNode evidence = JSON.valueToTree(prediction.evidence());
        if (!evidence.path("source").asText().equals("agent-http")) return List.of("booking_evidence_missing");
        if (evidence.hasNonNull("error")) failures.add("booking_transport_or_execution_error:" + evidence.get("error").asText());
        JsonNode usage = evidence.path("usage");
        if (expected.path("real_provider").asBoolean(true)
                && (usage.path("successfulCalls").asInt() == 0 || usage.path("fallbackCalls").asInt() > 0)) failures.add("real_provider_not_verified");
        JsonNode plan = null;
        for (JsonNode result : evidence.path("toolResults")) {
            if (result.path("toolName").asText().equals("search_bookable_options")) plan = result.path("data");
        }
        if (expected.has("plan_status")) {
            if (plan == null || !plan.path("status").asText().equals(expected.get("plan_status").asText())) failures.add("plan_status_mismatch");
        }
        if (plan != null) {
            for (JsonNode field : expected.path("required_clarifications")) {
                boolean found = false;
                for (JsonNode question : plan.path("clarifications")) if (question.path("field").asText().equals(field.asText())) found = true;
                if (!found) failures.add("missing_clarification:" + field.asText());
            }
            JsonNode options = plan.path("options");
            if (expected.has("min_options") && options.size() < expected.get("min_options").asInt()) failures.add("too_few_options");
            if (options.size() > expected.path("max_options").asInt(5)) failures.add("too_many_options");
            for (JsonNode option : options) {
                JsonNode quote = option.path("quote"), slot = option.path("slot"), resource = option.path("resource");
                if (expected.has("scenario") && !resource.path("kind").asText().equals(expected.get("scenario").asText())) failures.add("scenario_mismatch");
                if (expected.has("party_size") && quote.path("partySize").asInt() != expected.get("party_size").asInt()) failures.add("party_size_mismatch");
                if (expected.has("max_total_cents") && (quote.path("totalCents").isNull() || !quote.path("totalCents").canConvertToLong()
                        || quote.path("totalCents").asLong() > expected.get("max_total_cents").asLong())) failures.add("budget_exceeded_or_unknown");
                if (!quote.path("slotId").asText().equals(slot.path("id").asText()) || !quote.path("resourceId").asText().equals(resource.path("id").asText())) failures.add("quote_target_mismatch");
                if (slot.path("available").asInt() < quote.path("units").asInt()) failures.add("insufficient_capacity");
                try {
                    if (expected.has("start_at") && Instant.parse(slot.path("startAt").asText()).isBefore(Instant.parse(expected.get("start_at").asText()))) failures.add("slot_before_requested_time");
                    if (expected.has("end_before") && Instant.parse(slot.path("endAt").asText()).isAfter(Instant.parse(expected.get("end_before").asText()))) failures.add("slot_after_requested_time");
                } catch (Exception malformed) { failures.add("slot_time_invalid"); }
            }
        }
        if (expected.path("no_business_writes").asBoolean() && !bookingFacts(evidence.path("beforeBookings")).equals(bookingFacts(evidence.path("afterBookings")))) failures.add("unexpected_booking_write");
        if (expected.has("final_status") && !evidence.path("finalBooking").path("status").asText().equals(expected.get("final_status").asText())) failures.add("final_booking_status_mismatch");
        if (expected.path("frozen_price").asBoolean()) {
            JsonNode chosen = evidence.path("selectedQuote"), snapshot = evidence.path("finalBooking").path("priceSnapshot");
            List<String> fields = chosen.path("pricingMode").asText().equals("FIXED")
                    ? List.of("pricingMode", "currency", "totalCents", "baseCents", "discountLines", "offerVersion", "includedItems", "excludedItems")
                    : List.of("pricingMode", "currency", "totalCents");
            if (fields.stream().anyMatch(field -> !chosen.path(field).equals(snapshot.path(field)))) failures.add("frozen_price_mismatch");
        }
        if (evidence.hasNonNull("cleanupError") || evidence.has("cleanup") && !evidence.path("cleanup").path("state").asText().equals("SUCCEEDED")) failures.add("fixture_cleanup_failed");
        for (JsonNode tool : expected.path("forbidden_tools")) {
            if (prediction.tools().contains(tool.asText())) failures.add("forbidden_tool:" + tool.asText());
        }
        if (expected.path("confirmation_pause").asBoolean()) {
            boolean pending = false;
            for (JsonNode result : evidence.path("toolResults")) {
                if (result.path("data").path("type").asText().equals("BOOKING_CONFIRMATION") && result.path("data").path("state").asText().equals("PENDING")) pending = true;
            }
            if (!pending) failures.add("confirmation_pause_missing");
        }
        return failures.stream().distinct().toList();
    }
    private static TreeSet<String> bookingFacts(JsonNode bookings) {
        TreeSet<String> facts = new TreeSet<>();
        for (JsonNode booking : bookings) facts.add(booking.path("id").asText() + ":" + booking.path("version").asLong() + ":" + booking.path("status").asText());
        return facts;
    }
}
