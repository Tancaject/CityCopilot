package com.citycopilot.core.service.impl;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VoucherOrderRetryPolicyTest {

    @Test
    void poisonMessageMovesToDlqAtConfiguredAttempt() {
        VoucherOrderServiceImpl service = new VoucherOrderServiceImpl();
        ReflectionTestUtils.setField(service, "streamMaxRetries", 3);

        assertFalse(service.shouldDeadLetter(1));
        assertFalse(service.shouldDeadLetter(2));
        assertTrue(service.shouldDeadLetter(3));
        assertTrue(service.shouldDeadLetter(4));
    }
}
