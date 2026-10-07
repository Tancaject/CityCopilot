package com.citycopilot.commerce;

import com.citycopilot.contract.commerce.CommerceContracts.AdmissionRepairRequeued;
import org.junit.jupiter.api.Test;


import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class CommerceControllerOperationsTest {

    @Test
    void admissionRepairRequeueRequiresTheExistingOpsAuthorizationGate() {
        CommerceOperationsService operations = mock(CommerceOperationsService.class);
        CommerceController controller = controller(operations);

        assertThatThrownBy(() -> controller.requeueAdmissionRepair(
                300L,
                false,
                "operator:7"
        )).isInstanceOfSatisfying(
                CommerceApiException.class,
                error -> assertThat(error.code()).isEqualTo("COMMERCE_OPS_FORBIDDEN")
        );
        verifyNoInteractions(operations);
    }

    @Test
    void authorizedOperatorCanRequeueExactlyOneAdmissionOrderId() {
        CommerceOperationsService operations = mock(CommerceOperationsService.class);
        CommerceController controller = controller(operations);
        when(operations.requeueAdmissionRepair(300L, "operator:7"))
                .thenReturn(new AdmissionRepairRequeued("requeued", "300"));

        assertThat(controller.requeueAdmissionRepair(300L, true, "operator:7"))
                .isEqualTo(new AdmissionRepairRequeued("requeued", "300"));
        verify(operations).requeueAdmissionRepair(300L, "operator:7");
    }

    private CommerceController controller(CommerceOperationsService operations) {
        return new CommerceController(
                mock(CommerceOrderService.class),
                mock(CommerceOrderAdmissionService.class),
                mock(CommerceVoucherService.class),
                mock(CommerceConsistencyService.class),
                operations,
                mock(CommercePaymentService.class)
        );
    }
}
