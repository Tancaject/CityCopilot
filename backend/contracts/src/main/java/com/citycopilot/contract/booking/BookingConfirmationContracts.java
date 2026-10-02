package com.citycopilot.contract.booking;

import com.citycopilot.contract.booking.BookingContracts.BookingView;
import com.citycopilot.contract.booking.BookingContracts.MutationResult;
import com.citycopilot.contract.booking.PricingContracts.QuoteView;
import java.time.Instant;

/** Model tools may propose these actions; only the authenticated user's HTTP approval executes them. */
public final class BookingConfirmationContracts {
    public static final String TYPE = "BOOKING_CONFIRMATION";
    public static final String TOOL_SCOPE = "booking-confirmation";
    private BookingConfirmationContracts() {}

    public record ProposalRequest(String action, String quoteId, String bookingId, Long expectedVersion, String reason) {}
    public record ApprovalRequest(String requestHash) {}
    public record ConfirmationView(String type, String operationId, String action, String state,
                                   String requestHash, Instant expiresAt, Instant serverTime,
                                   String bookingId, String resourceName, String timezone,
                                   Instant startAt, Instant endAt, int partySize,
                                   BookingView previous, QuoteView quote, MutationResult result,
                                   String message, String confirmationPath) {}
}
