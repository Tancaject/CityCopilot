package com.citycopilot.contract.booking;

import java.time.Instant;
import java.util.List;

public final class PricingContracts {
    private PricingContracts() {}

    public record OfferRequest(String offerId, String kind, String title, String includedItems,
                               String excludedItems, long unitPriceCents, String status, Long expectedVersion) {}
    public record OfferView(String id, String shopId, String resourceId, String kind, String title,
                            String includedItems, String excludedItems, long unitPriceCents,
                            long version, String status) {}
    public record RuleRequest(String campaignKey, String offerId, String type, long thresholdCents,
                              long reductionCents, int payFactorBps, Long discountCapCents,
                              boolean stackable, Instant serviceFrom, Instant serviceTo,
                              Instant claimUntil, Instant couponExpiresAt) {}
    public record RuleView(String id, String campaignKey, long ruleVersion, String shopId, String offerId,
                           String type, long thresholdCents, long reductionCents, int payFactorBps,
                           Long discountCapCents, boolean stackable, Instant serviceFrom,
                           Instant serviceTo, Instant claimUntil, Instant couponExpiresAt, String issuanceStatus) {}
    public record CouponView(String id, String userId, String status, Instant expiresAt,
                             String bookingId, Instant revokedAt, RuleView rule) {}
    public record ClaimRequest(String ruleId) {}
    public record IssuanceRequest(String status) {}
    public record RevokeRequest(String reason) {}
    public record QuoteRequest(String resourceId, String slotId, int partySize, String offerId,
                               String couponMode, List<String> couponIds, String purpose,
                               String bookingId, Long expectedBookingVersion,
                               String source, String requestId) {}
    public record DiscountView(String couponId, String ruleId, long ruleVersion, String type,
                               long thresholdCents, int payFactorBps, Long discountCapCents,
                               long eligibleBaseCents, long beforeCents, long discountCents,
                               long afterCents, Instant couponExpiresAt) {}
    public record QuoteView(String quoteId, String userId, String purpose, String bookingId,
                            Long expectedBookingVersion, String resourceId, String slotId, int partySize,
                            int units, String pricingMode, String offerId, Long offerVersion,
                            String offerTitle, String includedItems, String excludedItems,
                            Long unitPriceCents, String currency, Long baseCents,
                            List<DiscountView> discountLines, Long totalCents, Instant expiresAt,
                            String source, String requestId) {
        public QuoteView {
            discountLines = List.copyOf(discountLines);
        }
    }
}
