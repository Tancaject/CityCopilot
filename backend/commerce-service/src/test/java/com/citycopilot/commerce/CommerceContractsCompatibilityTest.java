package com.citycopilot.commerce;

import com.citycopilot.contract.commerce.CommerceContracts.AdmissionAccepted;
import com.citycopilot.contract.commerce.CommerceContracts.CommerceError;
import com.citycopilot.contract.commerce.CommerceContracts.ConsistencyRepairRequest;
import com.citycopilot.contract.commerce.CommerceContracts.ConsistencyRepairResult;
import com.citycopilot.contract.commerce.CommerceContracts.OrderActionRequest;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

class CommerceContractsCompatibilityTest {
    private final JsonMapper json = JsonMapper.builderWithJackson2Defaults().build();

    @Test
    void typedContractsKeepExistingWireFieldsAndIdentifierTypes() {
        assertWire(new AdmissionAccepted("9007199254740993", "processing", "COMMERCE_ORDER_ACCEPTED", "request-1"),
                """
                {"orderId":"9007199254740993","state":"processing",
                 "code":"COMMERCE_ORDER_ACCEPTED","idempotencyKey":"request-1"}
                """);
        assertWire(new OrderActionRequest("pay", 2), """
                {"action":"pay","payType":2}
                """);
        assertThat(json.readValue("{\"action\":\"cancel\"}", OrderActionRequest.class))
                .isEqualTo(new OrderActionRequest("cancel", null));
        assertWire(new ConsistencyRepairRequest("old-hash"), """
                {"expectedEvidenceHash":"old-hash"}
                """);
        assertWire(new ConsistencyRepairResult(100, 7, 2, 1, "old-hash", "new-hash", true), """
                {"voucherId":100,"targetRedisStock":7,"rebuiltBuyerCount":2,"restoredRecoveryRows":1,
                 "beforeEvidenceHash":"old-hash","afterEvidenceHash":"new-hash","verified":true}
                """);
        assertWire(new CommerceError(false, "COMMERCE_ADMISSION_OUTCOME_UNKNOWN", "Retry with the same Idempotency-Key"),
                """
                {"success":false,"code":"COMMERCE_ADMISSION_OUTCOME_UNKNOWN",
                 "message":"Retry with the same Idempotency-Key"}
                """);
    }

    private void assertWire(Object value, String expected) {
        assertThat(json.readTree(json.writeValueAsBytes(value))).isEqualTo(json.readTree(expected));
        assertThat(json.readValue(expected, value.getClass())).isEqualTo(value);
    }
}
