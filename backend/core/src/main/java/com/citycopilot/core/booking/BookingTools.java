package com.citycopilot.core.booking;

import com.citycopilot.contract.booking.BookingConfirmationContracts.*;
import com.citycopilot.contract.booking.BookingContracts.*;
import com.citycopilot.contract.booking.PricingContracts.*;
import com.citycopilot.contract.tool.AgentTool;
import com.citycopilot.contract.tool.ToolAudience;
import org.springframework.stereotype.Service;
import java.time.Instant;
import java.util.List;
import static com.citycopilot.contract.booking.BookingConfirmationContracts.TOOL_SCOPE;

@Service
public class BookingTools {
    private final BookingPortClient bookings;
    private final BookingConfirmationService confirmations;
    public BookingTools(BookingPortClient bookings, BookingConfirmationService confirmations) {
        this.bookings = bookings; this.confirmations = confirmations;
    }
    @AgentTool(name = "list_booking_slots", audience = ToolAudience.CONSUMER, scope = "booking",
            description = "List real available booking slots for a resource. dateFrom/dateTo are ISO-8601 instants with timezone offset, at most 14 days apart. Does not reserve capacity.")
    public List<SlotView> slots(String resourceId, Instant dateFrom, Instant dateTo) {
        return bookings.slots(resourceId, dateFrom, dateTo);
    }
    @AgentTool(name = "quote_booking", audience = ToolAudience.CONSUMER, scope = "booking",
            description = "Calculate an authoritative quote in integer CNY cents. request includes resourceId,slotId,partySize, optional offerId, couponMode AUTO/NONE/MANUAL and couponIds; purpose CREATE or RESCHEDULE. Rescheduling also requires bookingId and expectedBookingVersion. Does not reserve capacity or coupons.")
    public QuoteView quote(QuoteRequest request) { return bookings.quote(request); }
    @AgentTool(name = "get_my_bookings", audience = ToolAudience.CONSUMER, scope = "booking", optionalParameters = {"page"},
            description = "Read current user's bookings, current versions, frozen prices, deadlines and allowed actions. Page defaults to 1. Never reads another user's bookings.")
    public List<BookingView> mine(Integer page) { return bookings.bookings(page == null ? 1 : page); }

    // These tools only create immutable review cards. No tool, prompt flag or plan approval can approve them.
    @AgentTool(name = "hold_booking", audience = ToolAudience.CONSUMER, scope = TOOL_SCOPE,
            description = "Propose HOLD for a selected authoritative quoteId. Returns BOOKING_CONFIRMATION for the user to approve in the app. This call never reserves capacity; stop and present the card. HOLD and final CONFIRM require separate user actions.")
    public ConfirmationView hold(String quoteId) { return confirmations.propose(new ProposalRequest("HOLD", quoteId, null, null, null)); }
    @AgentTool(name = "confirm_booking", audience = ToolAudience.CONSUMER, scope = TOOL_SCOPE,
            description = "Propose final confirmation of an existing HELD bookingId and expectedVersion. Uses its frozen price and holdExpiresAt, not a new quote. Returns a user review card; never executes the booking. Stop and present the card.")
    public ConfirmationView confirm(String bookingId, Long expectedVersion) {
        return confirmations.propose(new ProposalRequest("CONFIRM", null, bookingId, expectedVersion, null));
    }
    @AgentTool(name = "reschedule_booking", audience = ToolAudience.CONSUMER, scope = TOOL_SCOPE,
            description = "Propose rescheduling bookingId/expectedVersion to the target quoteId from a RESCHEDULE quote. User must review old/new times, people and price in the returned card. Never changes the original booking before user approval. Stop after returning card.")
    public ConfirmationView reschedule(String bookingId, Long expectedVersion, String quoteId) {
        return confirmations.propose(new ProposalRequest("RESCHEDULE", quoteId, bookingId, expectedVersion, null));
    }
    @AgentTool(name = "cancel_booking", audience = ToolAudience.CONSUMER, scope = TOOL_SCOPE, optionalParameters = {"reason"},
            description = "Propose cancelling own bookingId with expectedVersion and optional reason. Returns a review card without cancelling. Only user approval in the app executes this action. Stop and present the card.")
    public ConfirmationView cancel(String bookingId, Long expectedVersion, String reason) {
        return confirmations.propose(new ProposalRequest("CANCEL", null, bookingId, expectedVersion, reason));
    }
}
