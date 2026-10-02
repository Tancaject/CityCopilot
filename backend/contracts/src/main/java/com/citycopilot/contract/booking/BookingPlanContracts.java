package com.citycopilot.contract.booking;

import com.citycopilot.contract.booking.BookingContracts.ResourceView;
import com.citycopilot.contract.booking.BookingContracts.SlotView;
import com.citycopilot.contract.booking.PricingContracts.QuoteView;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/** A plan is a read-only offer to the user; no capacity is reserved until a separate explicit hold. */
public final class BookingPlanContracts {
    private BookingPlanContracts() {}

    public record PlanRequest(String scenario, LocalDate date, LocalTime timeFrom, LocalTime timeTo,
                              String timezone, Integer partySize, String keyword,
                              Double longitude, Double latitude, Double maxDistanceMeters,
                              Long budgetCents, String budgetBasis, List<String> preferences) {}
    public record Clarification(String field, String question) {}
    public record PlanOption(String shopId, String shopName, String address, Double distanceMeters,
                             ResourceView resource, SlotView slot, QuoteView quote, List<String> reasons) {}
    public record PlanResponse(String status, String requestId, String rankingVersion, Instant serverTime,
                               List<Clarification> clarifications, List<PlanOption> options,
                               int candidateShops, boolean truncated, String message) {}

    public record BatchRequest(List<String> shopIds, String scenario, Instant startFrom, Instant endBefore,
                               int partySize, Long maxTotalCents, String requestId, String impressionId) {}
    public record BatchOption(ResourceView resource, SlotView slot, QuoteView quote) {}
    public record BatchResponse(List<BatchOption> options, Instant serverTime, boolean truncated) {}
}
