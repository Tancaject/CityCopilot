package com.citycopilot.commerce.pricing;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/** CNY 预约报价；调用方负责券的归属、适用商品、服务时间和当前状态校验。 */
public final class BookingPriceCalculator {
    public static final int MAX_CANDIDATES = 20;
    // 所有合法金额都必须能安全乘以任意合法万分比，而非随本次选券改变金额上限。
    public static final long MAX_CENTS = Long.MAX_VALUE / 10_000;

    private BookingPriceCalculator() {
    }

    public static long basePrice(long unitPriceCents, int quantity) {
        requireAmount(unitPriceCents);
        if (quantity <= 0) {
            throw new IllegalArgumentException("Quantity must be positive");
        }
        long base = Math.multiplyExact(unitPriceCents, quantity);
        requireAmount(base);
        return base;
    }

    /** 完整枚举不使用券、单券和合法双券；候选超限时拒绝，不截断。 */
    public static Price bestPrice(long baseCents, List<Coupon> candidates) {
        requireAmount(baseCents);
        List<Coupon> coupons = checkedCoupons(candidates, MAX_CANDIDATES).stream()
                .filter(coupon -> baseCents >= coupon.thresholdCents())
                .toList();
        Price best = calculate(baseCents, List.of());
        for (int first = 0; first < coupons.size(); first++) {
            Coupon coupon = coupons.get(first);
            best = better(best, calculate(baseCents, List.of(coupon)));
            for (int second = first + 1; second < coupons.size(); second++) {
                Coupon other = coupons.get(second);
                if (canStack(coupon, other)) {
                    best = better(best, calculate(baseCents, List.of(coupon, other)));
                }
            }
        }
        return best;
    }

    /** 计算用户明确选择的券；不满足门槛或叠加规则时拒绝，不替用户换券。 */
    public static Price price(long baseCents, List<Coupon> selectedCoupons) {
        requireAmount(baseCents);
        List<Coupon> coupons = checkedCoupons(selectedCoupons, 2);
        if (coupons.stream().anyMatch(coupon -> baseCents < coupon.thresholdCents())) {
            throw new IllegalArgumentException("Selected coupon threshold is not met");
        }
        if (coupons.size() == 2 && !canStack(coupons.get(0), coupons.get(1))) {
            throw new IllegalArgumentException("Selected coupons cannot be stacked");
        }
        return calculate(baseCents, coupons);
    }

    private static Price calculate(long baseCents, List<Coupon> coupons) {
        long remaining = baseCents;
        List<DiscountLine> lines = new ArrayList<>();
        for (Coupon coupon : coupons.stream().sorted(Comparator.comparing(Coupon::type)).toList()) {
            long discount;
            if (coupon.type() == Type.DISCOUNT) {
                long scaled = Math.multiplyExact(remaining, coupon.payFactorBps());
                // 非负金额的 ROUND_HALF_UP；先除再加，避免 scaled + 5000 溢出。
                long payable = scaled / 10_000 + (scaled % 10_000 >= 5_000 ? 1 : 0);
                discount = remaining - payable;
                if (coupon.discountCapCents() != null) {
                    discount = Math.min(discount, coupon.discountCapCents());
                }
            } else {
                discount = Math.min(remaining, coupon.reductionCents());
            }
            long after = remaining - discount;
            lines.add(new DiscountLine(coupon, baseCents, remaining, discount, after));
            remaining = after;
        }
        return new Price(baseCents, remaining, lines);
    }

    private static boolean canStack(Coupon first, Coupon second) {
        return first.type() != second.type() && first.stackable() && second.stackable();
    }

    private static Price better(Price first, Price second) {
        int compared = Long.compare(first.totalCents(), second.totalCents());
        if (compared == 0) {
            compared = Integer.compare(first.discountLines().size(), second.discountLines().size());
        }
        if (compared == 0 && !first.discountLines().isEmpty()) {
            compared = earliestExpiry(first).compareTo(earliestExpiry(second));
            List<String> firstIds = couponIds(first);
            List<String> secondIds = couponIds(second);
            for (int index = 0; compared == 0 && index < firstIds.size(); index++) {
                compared = firstIds.get(index).compareTo(secondIds.get(index));
            }
        }
        return compared <= 0 ? first : second;
    }

    private static Instant earliestExpiry(Price price) {
        return price.discountLines().stream()
                .map(line -> line.coupon().expiresAt())
                .min(Comparator.naturalOrder()).orElseThrow();
    }

    private static List<String> couponIds(Price price) {
        // 券 ID 按字典序比较，使 UUID 和数字字符串都不依赖输入顺序。
        return price.discountLines().stream().map(line -> line.coupon().id()).sorted().toList();
    }

    private static List<Coupon> checkedCoupons(List<Coupon> coupons, int limit) {
        List<Coupon> copy = List.copyOf(coupons);
        if (copy.size() > limit) {
            throw new IllegalArgumentException("Coupon count exceeds " + limit);
        }
        HashSet<String> ids = new HashSet<>();
        for (Coupon coupon : copy) {
            if (!ids.add(coupon.id())) {
                throw new IllegalArgumentException("Duplicate coupon id: " + coupon.id());
            }
        }
        return copy;
    }

    private static void requireAmount(long cents) {
        if (cents < 0 || cents > MAX_CENTS) {
            throw new IllegalArgumentException("Amount must be between 0 and " + MAX_CENTS + " cents");
        }
    }

    /** 声明顺序就是固定优惠执行顺序。 */
    public enum Type {
        DISCOUNT, FULL_REDUCTION, CASH
    }

    /** expiresAt 只参与选券排序；纯计算器不读取时钟或推断个人券是否仍可使用。 */
    public record Coupon(
            String id,
            Type type,
            long thresholdCents,
            long reductionCents,
            int payFactorBps,
            Long discountCapCents,
            boolean stackable,
            Instant expiresAt
    ) {
        public Coupon {
            if (id == null || id.isBlank()) {
                throw new IllegalArgumentException("Coupon id is required");
            }
            Objects.requireNonNull(type, "Coupon type is required");
            Objects.requireNonNull(expiresAt, "Coupon expiry is required");
            requireAmount(thresholdCents);
            requireAmount(reductionCents);
            if (type == Type.DISCOUNT && (payFactorBps < 0 || payFactorBps > 10_000)) {
                throw new IllegalArgumentException("Discount pay factor must be between 0 and 10000");
            }
            if (discountCapCents != null) {
                requireAmount(discountCapCents);
            }
        }
    }

    public record DiscountLine(
            Coupon coupon,
            long eligibleBaseCents,
            long beforeCents,
            long discountCents,
            long afterCents
    ) {
    }

    public record Price(long baseCents, long totalCents, List<DiscountLine> discountLines) {
        public Price {
            discountLines = List.copyOf(discountLines);
        }
    }
}
