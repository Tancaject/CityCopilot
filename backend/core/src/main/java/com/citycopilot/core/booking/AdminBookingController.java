package com.citycopilot.core.booking;

import com.citycopilot.contract.booking.BookingContracts.*;
import com.citycopilot.core.security.PermissionCodes;
import com.citycopilot.core.security.RequirePermission;
import com.citycopilot.core.service.IShopService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/admin")
@RequirePermission(value = {PermissionCodes.BOOKING_MANAGE, PermissionCodes.BOOKING_CHECK_IN}, anyOf = true)
public class AdminBookingController {
    private final BookingPortClient booking;
    private final IShopService shops;
    public AdminBookingController(BookingPortClient booking, IShopService shops) {
        this.booking = booking;
        this.shops = shops;
    }

    @GetMapping("/booking-shops")
    public List<ShopView> shops() {
        return shops.listBookingShopsForCurrentUser();
    }
    @GetMapping("/shops/{shopId}/booking-resources")
    public List<ResourceView> resources(@PathVariable String shopId) { return booking.adminResources(shopId); }
    @PostMapping("/shops/{shopId}/booking-resources")
    @RequirePermission(PermissionCodes.BOOKING_MANAGE)
    public ResourceView createResource(@PathVariable String shopId, @RequestBody ResourceRequest request) {
        return booking.createResource(shopId, request);
    }
    @PutMapping("/booking-resources/{id}")
    @RequirePermission(PermissionCodes.BOOKING_MANAGE)
    public ResourceView updateResource(@PathVariable String id, @RequestBody ResourceRequest request) {
        return booking.updateResource(id, request);
    }
    @PostMapping("/booking-resources/{id}/slots")
    @RequirePermission(PermissionCodes.BOOKING_MANAGE)
    public SlotView saveSlot(@PathVariable String id, @RequestBody SlotRequest request) {
        return booking.saveSlot(id, request);
    }
    @GetMapping("/shops/{shopId}/bookings")
    public List<BookingView> bookings(@PathVariable String shopId, @RequestParam Instant from, @RequestParam Instant to) {
        return booking.shopBookings(shopId, from, to);
    }
    @GetMapping("/bookings/{id}")
    public BookingView booking(@PathVariable String id) { return booking.adminBooking(id); }
    @PostMapping("/bookings/{id}/check-in")
    @RequirePermission(PermissionCodes.BOOKING_CHECK_IN)
    public ResponseEntity<MutationResult> checkIn(@PathVariable String id, @RequestBody ActionRequest request,
                                                 @RequestHeader("Idempotency-Key") String key) {
        return BookingController.result(booking.checkIn(id, request, key));
    }
    @PostMapping("/bookings/{id}/cancel")
    @RequirePermission(PermissionCodes.BOOKING_MANAGE)
    public ResponseEntity<MutationResult> cancel(@PathVariable String id, @RequestBody ActionRequest request,
                                                @RequestHeader("Idempotency-Key") String key) {
        return BookingController.result(booking.merchantCancel(id, request, key));
    }
}
