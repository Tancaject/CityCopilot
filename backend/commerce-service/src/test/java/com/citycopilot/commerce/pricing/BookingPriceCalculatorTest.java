package com.citycopilot.commerce.pricing;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Instant;
import java.util.List;
import java.util.stream.IntStream;

import static com.citycopilot.commerce.pricing.BookingPriceCalculator.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BookingPriceCalculatorTest {
    private static final Instant EXPIRY = Instant.parse("2026-10-01T00:00:00Z");

    @Test
    void comparesEveryCombinationAndUsesOriginalAmountForThreshold() {
        Coupon discount = new Coupon("discount", Type.DISCOUNT, 0, 0, 9_000, null, true, EXPIRY);
        Coupon reduction = new Coupon("reduction", Type.FULL_REDUCTION, 20_000, 3_000, 0, null, true, EXPIRY);
        Coupon cash = cash("cash", 4_000, true, EXPIRY);

        Price selected = price(20_000, List.of(reduction, discount));
        assertThat(selected.totalCents()).isEqualTo(15_000);
        assertThat(selected.discountLines()).extracting(DiscountLine::discountCents)
                .containsExactly(2_000L, 3_000L);
        assertThat(selected.discountLines()).extracting(DiscountLine::eligibleBaseCents)
                .containsExactly(20_000L, 20_000L);

        Price best = bestPrice(20_000, List.of(discount, cash, reduction));
        assertThat(best.totalCents()).isEqualTo(13_000);
        assertThat(best.discountLines()).extracting(line -> line.coupon().id())
                .containsExactly("reduction", "cash");
        assertThat(bestPrice(20_000, List.of(reduction, cash, discount))).isEqualTo(best);
        assertThatThrownBy(() -> price(19_999, List.of(reduction)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("threshold");
        assertThat(bestPrice(19_999, List.of(reduction)).totalCents()).isEqualTo(19_999);
    }

    @ParameterizedTest
    @CsvSource({"1,5000,1", "3,5000,2", "101,9000,91", "100,0,0", "100,10000,100"})
    void roundsPayableHalfUpInCents(long base, int payFactor, long expected) {
        Coupon discount = new Coupon("discount", Type.DISCOUNT, 0, 0, payFactor, null, true, EXPIRY);
        assertThat(price(base, List.of(discount)).totalCents()).isEqualTo(expected);
    }

    @Test
    void capsPercentageDiscountAndNeverMakesAmountNegative() {
        Coupon capped = new Coupon("discount", Type.DISCOUNT, 0, 0, 5_000, 1_200L, true, EXPIRY);
        Price price = price(10_000, List.of(capped, cash("cash", 20_000, true, EXPIRY)));
        assertThat(price.totalCents()).isZero();
        assertThat(price.discountLines()).extracting(DiscountLine::discountCents)
                .containsExactly(1_200L, 8_800L);
        assertThat(bestPrice(0, List.of(capped)).discountLines()).isEmpty();
    }

    @Test
    void requiresBothStackableFlagsDifferentTypesAndDistinctIds() {
        Coupon discount = new Coupon("discount", Type.DISCOUNT, 0, 0, 9_000, null, true, EXPIRY);
        Coupon exclusive = cash("cash", 4_000, false, EXPIRY);
        assertThat(bestPrice(20_000, List.of(discount, exclusive)).totalCents()).isEqualTo(16_000);
        assertThatThrownBy(() -> price(20_000, List.of(discount, exclusive)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("stacked");
        assertThatThrownBy(() -> price(20_000, List.of(exclusive, cash("other", 1_000, true, EXPIRY))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("stacked");
        assertThatThrownBy(() -> bestPrice(20_000, List.of(exclusive, exclusive)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Duplicate coupon");
    }

    @Test
    void breaksTiesByCouponCountThenEarliestExpiryThenSortedIds() {
        Coupon reduction = new Coupon("reduction", Type.FULL_REDUCTION, 0, 2_000, 0, null, true, EXPIRY);
        Coupon cash = cash("cash", 2_000, true, EXPIRY);
        Coupon single = cash("single", 4_000, false, EXPIRY.plusSeconds(60));
        assertThat(bestPrice(20_000, List.of(reduction, cash, single)).discountLines())
                .extracting(line -> line.coupon().id()).containsExactly("single");

        Coupon older = cash("z", 4_000, true, EXPIRY.minusSeconds(1));
        Coupon sameExpiry = cash("a", 4_000, true, older.expiresAt());
        assertThat(bestPrice(20_000, List.of(single, older)).discountLines())
                .extracting(line -> line.coupon().id()).containsExactly("z");
        assertThat(bestPrice(20_000, List.of(older, sameExpiry)).discountLines())
                .extracting(line -> line.coupon().id()).containsExactly("a");
    }

    @Test
    void usesAllTwentyCandidatesAndRejectsOverflowRatherThanTruncating() {
        List<Coupon> candidates = IntStream.rangeClosed(1, 20)
                .mapToObj(id -> cash(String.valueOf(id), id * 100L, true, EXPIRY)).toList();
        assertThat(bestPrice(20_000, candidates).totalCents()).isEqualTo(18_000);
        List<Coupon> tooMany = IntStream.rangeClosed(1, 21)
                .mapToObj(id -> cash(String.valueOf(id), id * 100L, true, EXPIRY)).toList();
        assertThatThrownBy(() -> bestPrice(20_000, tooMany))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("20");
        assertThatThrownBy(() -> price(20_000, candidates))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("2");
    }

    @Test
    void checksMoneyBoundsAndQuantityMultiplication() {
        assertThat(basePrice(8_800, 4)).isEqualTo(35_200);
        Coupon fullPrice = new Coupon("discount", Type.DISCOUNT, 0, 0, 10_000, null, true, EXPIRY);
        assertThat(price(MAX_CENTS, List.of(fullPrice)).totalCents()).isEqualTo(MAX_CENTS);
        assertThatThrownBy(() -> basePrice(MAX_CENTS, Integer.MAX_VALUE))
                .isInstanceOf(ArithmeticException.class);
        assertThatThrownBy(() -> basePrice(MAX_CENTS, 2)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> basePrice(100, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> price(-1, List.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Coupon("bad", Type.DISCOUNT, 0, 0, 10_001, null, true, EXPIRY))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static Coupon cash(String id, long reduction, boolean stackable, Instant expiresAt) {
        return new Coupon(id, Type.CASH, 0, reduction, 0, null, stackable, expiresAt);
    }
}
