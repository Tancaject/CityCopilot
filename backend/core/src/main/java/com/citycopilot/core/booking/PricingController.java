package com.citycopilot.core.booking;

import com.citycopilot.contract.booking.PricingContracts.*;
import com.citycopilot.contract.security.PermissionCodes;
import com.citycopilot.core.security.RequirePermission;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
public class PricingController {
    private final BookingPortClient booking;
    public PricingController(BookingPortClient booking) { this.booking = booking; }

    @GetMapping("/booking-resources/{id}/offers")
    public List<OfferView> offers(@PathVariable String id) { return booking.offers(id); }
    @PostMapping("/booking-quotes")
    public QuoteView quote(@RequestBody QuoteRequest request) { return booking.quote(request); }
    @GetMapping("/booking-quotes/{id}")
    public QuoteView quote(@PathVariable String id) { return booking.quote(id); }
    @PostMapping("/admin/booking-resources/{id}/offers")
    @RequirePermission(PermissionCodes.BOOKING_MANAGE)
    public OfferView saveOffer(@PathVariable String id, @RequestBody OfferRequest request) { return booking.saveOffer(id, request); }
    @GetMapping("/admin/booking-resources/{id}/offers")
    @RequirePermission(PermissionCodes.BOOKING_MANAGE)
    public List<OfferView> adminOffers(@PathVariable String id) { return booking.adminOffers(id); }
    @GetMapping("/shops/{id}/booking-coupon-rules")
    public List<RuleView> rules(@PathVariable String id) { return booking.couponRules(id, false); }
    @GetMapping("/admin/shops/{id}/booking-coupon-rules")
    @RequirePermission(PermissionCodes.BOOKING_MANAGE)
    public List<RuleView> adminRules(@PathVariable String id) { return booking.couponRules(id, true); }
    @PostMapping("/admin/shops/{id}/booking-coupon-rules")
    @RequirePermission(PermissionCodes.BOOKING_MANAGE)
    public RuleView publishRule(@PathVariable String id, @RequestBody RuleRequest request) { return booking.publishRule(id, request); }
    @PostMapping("/admin/booking-coupon-rules/{id}/issuance")
    @RequirePermission(PermissionCodes.BOOKING_MANAGE)
    public RuleView issuance(@PathVariable String id, @RequestBody IssuanceRequest request) { return booking.issuance(id, request); }
    @PostMapping("/admin/booking-coupons/{id}/revoke")
    @RequirePermission(PermissionCodes.BOOKING_MANAGE)
    public CouponView revoke(@PathVariable String id, @RequestBody RevokeRequest request) { return booking.revoke(id, request); }
    @GetMapping("/booking-coupons")
    public List<CouponView> coupons(@RequestParam(required = false) String shopId) { return booking.coupons(shopId); }
    @PostMapping("/booking-coupons/claims")
    public CouponView claim(@RequestBody ClaimRequest request, @RequestHeader("Idempotency-Key") String key) { return booking.claim(request, key); }
}
