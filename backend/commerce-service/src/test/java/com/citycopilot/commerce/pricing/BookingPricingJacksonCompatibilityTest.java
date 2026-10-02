package com.citycopilot.commerce.pricing;

import com.citycopilot.commerce.PlaceAuthorityClient;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.cfg.DateTimeFeature;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BookingPricingJacksonCompatibilityTest {
    @Test
    void readsStoredQuoteAndPreservesCentsNullsAndIsoTimesInBookingSnapshot() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(anyString(), eq(String.class), eq("quote-1"))).thenReturn(List.of("""
                {"quoteId":"quote-1","userId":"7","purpose":"CREATE","bookingId":null,
                 "expectedBookingVersion":null,"resourceId":"9","slotId":"12","partySize":2,
                 "units":2,"pricingMode":"FIXED","offerId":"15","offerVersion":3,
                 "offerTitle":"双人午餐","includedItems":"主食","excludedItems":"饮料",
                 "unitPriceCents":2502,"currency":"CNY","baseCents":5004,
                 "discountLines":[{"couponId":"21","ruleId":"22","ruleVersion":2,
                   "type":"REDUCTION","thresholdCents":4000,"payFactorBps":0,
                   "discountCapCents":null,"eligibleBaseCents":5004,"beforeCents":5004,
                   "discountCents":1,"afterCents":5003,"couponExpiresAt":"2026-09-26T12:00:00.123Z"}],
                 "totalCents":5003,"expiresAt":"2026-09-25T10:15:30.123Z",
                 "source":"AGENT","impressionId":null,"requestId":"request-1"}
                """));
        // Pricing owns its ISO storage format even when the application's mapper enables timestamps.
        JsonMapper json = JsonMapper.builderWithJackson2Defaults()
                .enable(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS).build();
        BookingPricingService pricing = new BookingPricingService(jdbc, mock(TransactionTemplate.class),
                json, mock(PlaceAuthorityClient.class));

        var quote = pricing.getQuote("quote-1", 7);
        var snapshot = pricing.snapshot(quote);

        assertThat(quote.totalCents()).isEqualTo(5003L);
        assertThat(quote.expiresAt()).isEqualTo(Instant.parse("2026-09-25T10:15:30.123Z"));
        assertThat(snapshot).containsEntry("offerTitle", "双人午餐")
                .containsEntry("bookingId", null).containsEntry("impressionId", null)
                .containsEntry("expiresAt", "2026-09-25T10:15:30.123Z");
        var wire = json.valueToTree(snapshot);
        assertThat(wire.get("totalCents").longValue()).isEqualTo(5003);
        assertThat(wire.get("discountLines").get(0).get("couponExpiresAt").stringValue())
                .isEqualTo("2026-09-26T12:00:00.123Z");
        assertThat(json.isEnabled(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS)).isTrue();
    }
}
