package com.citycopilot.core.domain.order;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

public enum OrderStatus {
    UNPAID(1, "unpaid", "待支付"),
    PAID(2, "paid", "已支付"),
    USED(3, "used", "已核销"),
    CANCELLED(4, "cancelled", "已取消"),
    REFUNDING(5, "refunding", "退款中"),
    REFUNDED(6, "refunded", "已退款");

    private static final Map<OrderStatus, Set<OrderStatus>> TRANSITIONS = Map.of(
            UNPAID, EnumSet.of(PAID, CANCELLED),
            PAID, EnumSet.of(USED, REFUNDING),
            REFUNDING, EnumSet.of(REFUNDED)
    );

    private final int code;
    private final String state;
    private final String text;

    OrderStatus(int code, String state, String text) {
        this.code = code;
        this.state = state;
        this.text = text;
    }

    public int code() {
        return code;
    }

    public String state() {
        return state;
    }

    public String text() {
        return text;
    }

    public boolean canTransitionTo(OrderStatus target) {
        return target != null && TRANSITIONS.getOrDefault(this, Set.of()).contains(target);
    }

    public void requireTransitionTo(OrderStatus target) {
        if (!canTransitionTo(target)) {
            throw new IllegalStateException("Illegal order transition: " + this + " -> " + target);
        }
    }

    public static OrderStatus fromCode(Integer code) {
        int normalized = code == null ? UNPAID.code : code;
        for (OrderStatus status : values()) {
            if (status.code == normalized) {
                return status;
            }
        }
        throw new IllegalArgumentException("Unknown order status code: " + code);
    }
}
