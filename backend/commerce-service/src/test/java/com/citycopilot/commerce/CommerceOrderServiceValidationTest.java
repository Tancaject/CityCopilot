package com.citycopilot.commerce;

import org.junit.jupiter.api.Test;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CommerceOrderServiceValidationTest {
    private static final Instant NOW = Instant.parse("2026-07-30T00:00:00Z");

    @Test
    void acceptsAnActiveSeckillVoucherInsideItsWindow() {
        assertThatCode(() -> CommerceOrderService.validateAdmissionConfig(voucher(), NOW))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsInactiveAndOutOfWindowVouchers() {
        Map<String, Object> inactive = voucher();
        inactive.put("status", 2);
        assertThatThrownBy(() -> CommerceOrderService.validateAdmissionConfig(inactive, NOW))
                .isInstanceOf(CommerceApiException.class)
                .hasMessage("Voucher is not active");

        Map<String, Object> future = voucher();
        future.put("begin_time", Timestamp.from(NOW.plusSeconds(60)));
        assertThatThrownBy(() -> CommerceOrderService.validateAdmissionConfig(future, NOW))
                .isInstanceOf(CommerceApiException.class)
                .hasMessage("Seckill has not started");
    }

    private Map<String, Object> voucher() {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("type", 1);
        value.put("status", 1);
        value.put("stock", 10);
        value.put("begin_time", Timestamp.from(NOW.minusSeconds(60)));
        value.put("end_time", Timestamp.from(NOW.plusSeconds(60)));
        return value;
    }
}
