package com.citycopilot.eval;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class BookingEvalAssertionsTest {
    @Test void modelSuccessClaimWithoutBusinessEvidenceCannotPass() {
        var evalCase = testCase(Map.of("final_status", "CONFIRMED"));
        var prediction = new EvalPrediction("REACT", List.of(), "预约已确认", 1, 1);
        assertEquals(List.of("booking_evidence_missing"), BookingEvalAssertions.check(evalCase, prediction));
    }
    @Test void structuredConstraintsFailEvenWhenAnswerClaimsCorrectPeopleTimeAndBudget() {
        var evalCase = testCase(Map.of("plan_status", "OPTIONS", "party_size", 4, "max_total_cents", 20000,
                "end_before", "2026-09-11T12:00:00Z"));
        var option = Map.of("resource", Map.of("id", "11", "kind", "DINING_POOL"),
                "slot", Map.of("id", "22", "available", 3, "startAt", "2026-09-11T10:00:00Z", "endAt", "2026-09-11T13:00:00Z"),
                "quote", Map.of("resourceId", "11", "slotId", "22", "partySize", 5, "units", 5, "totalCents", 25000));
        var prediction = new EvalPrediction("REACT", List.of("search_bookable_options"), "4人，预算内且时间符合", 1, 1,
                Map.of("source", "agent-http", "usage", Map.of("successfulCalls", 1, "fallbackCalls", 0),
                        "toolResults", List.of(Map.of("toolName", "search_bookable_options", "data", Map.of("status", "OPTIONS", "options", List.of(option))))));
        var failures = BookingEvalAssertions.check(evalCase, prediction);
        assertTrue(failures.containsAll(List.of("party_size_mismatch", "budget_exceeded_or_unknown", "insufficient_capacity", "slot_after_requested_time")));
    }
    @Test void heldStateAndProviderFallbackCannotBeReportedAsConfirmed() {
        var evalCase = testCase(Map.of("final_status", "CONFIRMED"));
        var prediction = new EvalPrediction("PLAN_EXECUTE", List.of("confirm_booking"), "预约已确认", null, null,
                Map.of("source", "agent-http", "usage", Map.of("successfulCalls", 1, "fallbackCalls", 1), "finalBooking", Map.of("status", "HELD")));
        assertTrue(BookingEvalAssertions.check(evalCase, prediction).containsAll(List.of("real_provider_not_verified", "final_booking_status_mismatch")));
    }
    private EvalCase testCase(Map<String, Object> assertions) {
        return new EvalCase("booking-case", "预约", "ANY", List.of(), List.of(), List.of(), Map.of("assertions", assertions));
    }
}
