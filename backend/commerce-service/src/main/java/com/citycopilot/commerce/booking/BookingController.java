package com.citycopilot.commerce.booking;

import com.citycopilot.contract.booking.BookingContracts.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/commerce/v1")
@ConditionalOnProperty(prefix = "citycopilot.commerce", name = "api-enabled", havingValue = "true", matchIfMissing = true)
public class BookingController {
    private final BookingService bookings;
    private final BookingResourceService resources;

    public BookingController(BookingService bookings, BookingResourceService resources) {
        this.bookings = bookings;
        this.resources = resources;
    }

    @GetMapping("/booking-rules")
    Rules rules() { return resources.rules(); }

    @GetMapping("/shops/{shopId}/booking-resources")
    List<ResourceView> resources(@PathVariable long shopId) { return resources.resources(shopId); }

    @GetMapping("/booking-resources/{id}")
    ResourceView resource(@PathVariable long id) { return resources.resource(id); }

    @GetMapping("/booking-slots/{id}")
    SlotView slot(@PathVariable long id) { return resources.slot(id); }

    @GetMapping("/booking-resources/{id}/slots")
    List<SlotView> slots(@PathVariable long id,
                        @RequestParam(required = false) Instant dateFrom,
                        @RequestParam(required = false) Instant dateTo) {
        return resources.slots(id, dateFrom, dateTo);
    }

    @PostMapping("/bookings/holds")
    ResponseEntity<MutationResult> hold(@RequestHeader("X-CityCopilot-User-Id") long userId,
                                       @RequestHeader("Idempotency-Key") String key,
                                       @RequestBody HoldRequest request) {
        return response(bookings.hold(userId, key, request));
    }

    @PostMapping("/bookings/{id}/confirm")
    ResponseEntity<MutationResult> confirm(@PathVariable String id,
                                          @RequestHeader("X-CityCopilot-User-Id") long userId,
                                          @RequestHeader("Idempotency-Key") String key,
                                          @RequestBody ActionRequest request) {
        return response(bookings.confirm(userId, id, key, request));
    }

    @PostMapping("/bookings/{id}/cancel")
    ResponseEntity<MutationResult> cancel(@PathVariable String id,
                                         @RequestHeader("X-CityCopilot-User-Id") long userId,
                                         @RequestHeader("Idempotency-Key") String key,
                                         @RequestBody ActionRequest request) {
        return response(bookings.cancel(userId, false, false, id, key, request));
    }

    @PostMapping("/bookings/{id}/reschedule")
    ResponseEntity<MutationResult> reschedule(@PathVariable String id,
                                             @RequestHeader("X-CityCopilot-User-Id") long userId,
                                             @RequestHeader("Idempotency-Key") String key,
                                             @RequestBody RescheduleRequest request) {
        return response(bookings.reschedule(userId, id, key, request));
    }

    @GetMapping("/bookings")
    List<BookingView> mine(@RequestHeader("X-CityCopilot-User-Id") long userId,
                           @RequestParam(defaultValue = "1") int page) { return bookings.bookings(userId, page); }

    @GetMapping("/bookings/{id}")
    BookingView booking(@PathVariable String id, @RequestHeader("X-CityCopilot-User-Id") long userId) {
        return bookings.booking(id, userId);
    }

    @GetMapping("/booking-commands/{key}")
    CommandView command(@PathVariable String key, @RequestHeader("X-CityCopilot-User-Id") long userId) {
        return bookings.commandResult(userId, key);
    }

    @GetMapping("/booking-notices")
    List<NoticeView> notices(@RequestHeader("X-CityCopilot-User-Id") long userId) { return bookings.notices(userId); }

    @PostMapping("/booking-notices/{id}/read")
    NoticeView readNotice(@PathVariable long id, @RequestHeader("X-CityCopilot-User-Id") long userId) {
        return bookings.readNotice(userId, id);
    }

    @GetMapping("/admin/shops/{shopId}/booking-resources")
    List<ResourceView> merchantResources(@PathVariable long shopId,
                                         @RequestHeader("X-CityCopilot-User-Id") long actorId,
                                         @RequestHeader(value = "X-CityCopilot-Platform-Admin", defaultValue = "false") boolean admin) {
        return resources.merchantResources(shopId, actorId, admin);
    }

    @PostMapping("/admin/shops/{shopId}/booking-resources")
    ResourceView createResource(@PathVariable long shopId,
                                 @RequestHeader("X-CityCopilot-User-Id") long actorId,
                                 @RequestHeader(value = "X-CityCopilot-Platform-Admin", defaultValue = "false") boolean admin,
                                 @RequestBody ResourceRequest request) {
        return resources.createResource(shopId, actorId, admin, request);
    }

    @PutMapping("/admin/booking-resources/{id}")
    ResourceView updateResource(@PathVariable long id,
                                 @RequestHeader("X-CityCopilot-User-Id") long actorId,
                                 @RequestHeader(value = "X-CityCopilot-Platform-Admin", defaultValue = "false") boolean admin,
                                 @RequestBody ResourceRequest request) {
        return resources.updateResource(id, actorId, admin, request);
    }

    @PostMapping("/admin/booking-resources/{id}/slots")
    SlotView saveSlot(@PathVariable long id,
                       @RequestHeader("X-CityCopilot-User-Id") long actorId,
                       @RequestHeader(value = "X-CityCopilot-Platform-Admin", defaultValue = "false") boolean admin,
                       @RequestBody SlotRequest request) {
        return resources.saveSlot(id, actorId, admin, request);
    }

    @GetMapping("/admin/shops/{shopId}/bookings")
    List<BookingView> merchantBookings(@PathVariable long shopId,
                                       @RequestHeader("X-CityCopilot-User-Id") long actorId,
                                       @RequestHeader(value = "X-CityCopilot-Platform-Admin", defaultValue = "false") boolean admin,
                                       @RequestParam(required = false) Instant from,
                                       @RequestParam(required = false) Instant to) {
        return bookings.merchantBookings(shopId, actorId, admin, from, to);
    }

    @GetMapping("/admin/bookings/{id}")
    BookingView merchantBooking(@PathVariable String id,
                                 @RequestHeader("X-CityCopilot-User-Id") long actorId,
                                 @RequestHeader(value = "X-CityCopilot-Platform-Admin", defaultValue = "false") boolean admin) {
        return bookings.merchantBooking(id, actorId, admin);
    }

    @PostMapping("/admin/bookings/{id}/cancel")
    ResponseEntity<MutationResult> merchantCancel(@PathVariable String id,
                                                 @RequestHeader("X-CityCopilot-User-Id") long actorId,
                                                 @RequestHeader(value = "X-CityCopilot-Platform-Admin", defaultValue = "false") boolean admin,
                                                 @RequestHeader("Idempotency-Key") String key,
                                                 @RequestBody ActionRequest request) {
        return response(bookings.cancel(actorId, true, admin, id, key, request));
    }

    @PostMapping("/admin/bookings/{id}/check-in")
    ResponseEntity<MutationResult> checkIn(@PathVariable String id,
                                          @RequestHeader("X-CityCopilot-User-Id") long actorId,
                                          @RequestHeader(value = "X-CityCopilot-Platform-Admin", defaultValue = "false") boolean admin,
                                          @RequestHeader("Idempotency-Key") String key,
                                          @RequestBody ActionRequest request) {
        return response(bookings.checkIn(actorId, admin, id, key, request));
    }

    @ExceptionHandler({ConcurrencyFailureException.class, QueryTimeoutException.class})
    ResponseEntity<Map<String, Object>> contention(Exception ignored) {
        return ResponseEntity.status(503).header("Retry-After", "1").body(Map.of(
                "success", false, "code", "BOOKING_RETRY_REQUIRED",
                "message", "预约事务等待超时或发生竞争，请保留原 Idempotency-Key 重试"));
    }

    private ResponseEntity<MutationResult> response(MutationResult result) {
        return ResponseEntity.status(result.httpStatus()).body(result);
    }
}
