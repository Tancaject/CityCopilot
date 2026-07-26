package com.citycopilot.core.domain.order;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class OrderStatusTest {

    @Test
    void validatesCompleteTransitionMatrix() {
        Map<OrderStatus, Set<OrderStatus>> allowed = Map.of(
                OrderStatus.UNPAID, Set.of(OrderStatus.PAID, OrderStatus.CANCELLED),
                OrderStatus.PAID, Set.of(OrderStatus.USED, OrderStatus.REFUNDING),
                OrderStatus.REFUNDING, Set.of(OrderStatus.REFUNDED)
        );

        for (OrderStatus source : OrderStatus.values()) {
            for (OrderStatus target : OrderStatus.values()) {
                boolean expected = allowed.getOrDefault(source, Set.of()).contains(target);
                assertEquals(expected, source.canTransitionTo(target), source + " -> " + target);
                if (expected) {
                    source.requireTransitionTo(target);
                } else {
                    assertThrows(IllegalStateException.class, () -> source.requireTransitionTo(target));
                }
            }
        }
    }

    @Test
    void mapsLegacyDatabaseCodes() {
        assertEquals(OrderStatus.UNPAID, OrderStatus.fromCode(null));
        assertEquals(OrderStatus.REFUNDED, OrderStatus.fromCode(6));
        assertThrows(IllegalArgumentException.class, () -> OrderStatus.fromCode(99));
    }
}
