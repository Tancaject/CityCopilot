package com.citycopilot.core.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SeckillInventoryReconciliationServiceTest {

    @Test
    void detectsMissingInvalidAndDifferentRedisStock() {
        assertFalse(SeckillInventoryReconciliationService.isMismatch(10, "10"));
        assertTrue(SeckillInventoryReconciliationService.isMismatch(10, null));
        assertTrue(SeckillInventoryReconciliationService.isMismatch(10, "9"));
        assertTrue(SeckillInventoryReconciliationService.isMismatch(10, "invalid"));
    }
}
