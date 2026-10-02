package com.citycopilot.commerce.pricing;

import com.citycopilot.contract.booking.PricingContracts.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/commerce/v1")
@ConditionalOnProperty(prefix = "citycopilot.commerce", name = "api-enabled", havingValue = "true", matchIfMissing = true)
public class BookingPricingController {
    private final BookingPricingService pricing;

    public BookingPricingController(BookingPricingService pricing) {
        this.pricing = pricing;
    }

    @GetMapping("/booking-resources/{resourceId}/offers")
    public List<OfferView> offers(@PathVariable String resourceId) {
        return pricing.offers(resourceId, false);
    }

    @GetMapping("/admin/booking-resources/{resourceId}/offers")
    public List<OfferView> managedOffers(@PathVariable String resourceId,
                                         @RequestHeader("X-CityCopilot-User-Id") long actorId,
                                         @RequestHeader(value = "X-CityCopilot-Platform-Admin", defaultValue = "false") boolean admin) {
        return pricing.managedOffers(resourceId, actorId, admin);
    }

    @PostMapping("/admin/booking-resources/{resourceId}/offers")
    public OfferView saveOffer(@PathVariable String resourceId, @RequestBody OfferRequest request,
                               @RequestHeader("X-CityCopilot-User-Id") long actorId,
                               @RequestHeader(value = "X-CityCopilot-Platform-Admin", defaultValue = "false") boolean admin) {
        return pricing.saveOffer(resourceId, request, actorId, admin);
    }

    @GetMapping("/shops/{shopId}/booking-coupon-rules")
    public List<RuleView> rules(@PathVariable String shopId) {
        return pricing.rules(shopId, false);
    }

    @GetMapping("/admin/shops/{shopId}/booking-coupon-rules")
    public List<RuleView> managedRules(@PathVariable String shopId,
                                      @RequestHeader("X-CityCopilot-User-Id") long actorId,
                                      @RequestHeader(value = "X-CityCopilot-Platform-Admin", defaultValue = "false") boolean admin) {
        return pricing.managedRules(shopId, actorId, admin);
    }

    @PostMapping("/admin/shops/{shopId}/booking-coupon-rules")
    public RuleView publishRule(@PathVariable String shopId, @RequestBody RuleRequest request,
                                @RequestHeader("X-CityCopilot-User-Id") long actorId,
                                @RequestHeader(value = "X-CityCopilot-Platform-Admin", defaultValue = "false") boolean admin) {
        return pricing.publishRule(shopId, request, actorId, admin);
    }

    @PostMapping("/admin/booking-coupon-rules/{ruleId}/issuance")
    public RuleView issuance(@PathVariable String ruleId, @RequestBody IssuanceRequest request,
                             @RequestHeader("X-CityCopilot-User-Id") long actorId,
                             @RequestHeader(value = "X-CityCopilot-Platform-Admin", defaultValue = "false") boolean admin) {
        return pricing.setIssuance(ruleId, request.status(), actorId, admin);
    }

    @PostMapping("/admin/booking-coupons/{couponId}/revoke")
    public CouponView revoke(@PathVariable String couponId, @RequestBody RevokeRequest request,
                             @RequestHeader("X-CityCopilot-User-Id") long actorId,
                             @RequestHeader(value = "X-CityCopilot-Platform-Admin", defaultValue = "false") boolean admin) {
        return pricing.revoke(couponId, request.reason(), actorId, admin);
    }

    @GetMapping("/booking-coupons")
    public List<CouponView> coupons(@RequestHeader("X-CityCopilot-User-Id") long userId,
                                    @RequestParam(required = false) String shopId) {
        return pricing.coupons(userId, shopId);
    }

    @PostMapping("/booking-coupons/claims")
    public CouponView claim(@RequestHeader("X-CityCopilot-User-Id") long userId,
                            @RequestHeader("Idempotency-Key") String idempotencyKey,
                            @RequestBody ClaimRequest request) {
        return pricing.claim(userId, request.ruleId(), idempotencyKey);
    }

    @PostMapping("/booking-quotes")
    public QuoteView quote(@RequestHeader("X-CityCopilot-User-Id") long userId, @RequestBody QuoteRequest request) {
        return pricing.quote(userId, request);
    }

    @GetMapping("/booking-quotes/{quoteId}")
    public QuoteView quote(@PathVariable String quoteId, @RequestHeader("X-CityCopilot-User-Id") long userId) {
        return pricing.getQuote(quoteId, userId);
    }
}
