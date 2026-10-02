package com.citycopilot.core.booking;

import com.citycopilot.contract.booking.BookingContracts.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.time.Instant;
import java.util.List;

@RestController
public class BookingController {
    private final BookingPortClient booking;
    public BookingController(BookingPortClient booking) { this.booking = booking; }

    @GetMapping("/booking-rules")
    public Rules rules() { return booking.rules(); }
    @GetMapping("/shops/{shopId}/booking-resources")
    public List<ResourceView> resources(@PathVariable String shopId) { return booking.resources(shopId); }
    @GetMapping("/booking-resources/{id}/slots")
    public List<SlotView> slots(@PathVariable String id, @RequestParam Instant dateFrom, @RequestParam Instant dateTo) {
        return booking.slots(id, dateFrom, dateTo);
    }
    @GetMapping("/bookings")
    public List<BookingView> bookings(@RequestParam(defaultValue = "1") int page) { return booking.bookings(page); }
    @GetMapping("/bookings/{id}")
    public BookingView booking(@PathVariable String id) { return booking.booking(id); }
    @PostMapping("/bookings/holds")
    public ResponseEntity<MutationResult> hold(@RequestBody HoldRequest request, @RequestHeader("Idempotency-Key") String key) {
        return result(booking.hold(request, key));
    }
    @PostMapping("/bookings/{id}/confirm")
    public ResponseEntity<MutationResult> confirm(@PathVariable String id, @RequestBody ActionRequest request, @RequestHeader("Idempotency-Key") String key) {
        return result(booking.confirm(id, request, key));
    }
    @PostMapping("/bookings/{id}/cancel")
    public ResponseEntity<MutationResult> cancel(@PathVariable String id, @RequestBody ActionRequest request, @RequestHeader("Idempotency-Key") String key) {
        return result(booking.cancel(id, request, key));
    }
    @PostMapping("/bookings/{id}/reschedule")
    public ResponseEntity<MutationResult> reschedule(@PathVariable String id, @RequestBody RescheduleRequest request, @RequestHeader("Idempotency-Key") String key) {
        return result(booking.reschedule(id, request, key));
    }
    @GetMapping("/booking-commands/{key}")
    public CommandView command(@PathVariable String key) { return booking.command(key); }
    @GetMapping("/booking-notices")
    public List<NoticeView> notices() { return booking.notices(); }
    @PostMapping("/booking-notices/{id}/read")
    public NoticeView readNotice(@PathVariable String id) { return booking.readNotice(id); }
    static ResponseEntity<MutationResult> result(MutationResult result) {
        return ResponseEntity.status(result.httpStatus()).body(result);
    }
}
