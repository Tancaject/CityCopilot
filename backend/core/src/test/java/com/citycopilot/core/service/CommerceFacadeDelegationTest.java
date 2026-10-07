package com.citycopilot.core.service;

import com.citycopilot.contract.commerce.CommerceContracts.ConsistencyRepairResult;
import com.citycopilot.contract.commerce.CommerceContracts.DeadLetterView;
import com.citycopilot.contract.commerce.CommerceContracts.DeadLetterReplayResult;
import com.citycopilot.core.commerce.CommercePortClient;
import com.citycopilot.core.dto.Result;
import com.citycopilot.core.dto.VoucherRequest;
import com.citycopilot.core.service.impl.VoucherOrderServiceImpl;
import com.citycopilot.core.service.impl.VoucherServiceImpl;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CommerceFacadeDelegationTest {

    @Test
    void orderFacadeDelegatesEveryOperationToCommerce() {
        CommercePortClient port = mock(CommercePortClient.class);
        VoucherOrderServiceImpl facade = new VoucherOrderServiceImpl(port);
        Result expected = Result.ok("commerce");
        when(port.seckill(10L, "request-1")).thenReturn(expected);
        when(port.myOrders(2)).thenReturn(expected);
        when(port.order(99L, false)).thenReturn(expected);
        when(port.order(99L, true)).thenReturn(expected);
        when(port.transition(99L, "cancel", null, false)).thenReturn(expected);
        when(port.transition(99L, "pay", 2, false)).thenReturn(expected);
        when(port.transition(99L, "use", null, true)).thenReturn(expected);
        when(port.transition(99L, "refund", null, false)).thenReturn(expected);

        assertThat(facade.seckillVoucher(10L, "request-1")).isSameAs(expected);
        assertThat(facade.queryMyOrders(2)).isSameAs(expected);
        assertThat(facade.queryOrder(99L)).isSameAs(expected);
        assertThat(facade.queryOrderForAdmin(99L)).isSameAs(expected);
        assertThat(facade.cancelOrder(99L)).isSameAs(expected);
        assertThat(facade.payOrder(99L, 2)).isSameAs(expected);
        assertThat(facade.useOrder(99L)).isSameAs(expected);
        assertThat(facade.refundOrder(99L)).isSameAs(expected);

        verify(port).seckill(10L, "request-1");
        verify(port).myOrders(2);
        verify(port).order(99L, false);
        verify(port).order(99L, true);
        verify(port).transition(99L, "cancel", null, false);
        verify(port).transition(99L, "pay", 2, false);
        verify(port).transition(99L, "use", null, true);
        verify(port).transition(99L, "refund", null, false);
    }

    @Test
    void voucherFacadeDelegatesReadsAndWritesToCommerce() {
        CommercePortClient port = mock(CommercePortClient.class);
        VoucherServiceImpl facade = new VoucherServiceImpl(port);
        VoucherRequest voucher = new VoucherRequest().setId(12L).setTitle("coffee");
        Map<String, Object> voucherPayload = Map.of("title", "coffee");
        Result expected = Result.ok("commerce");
        when(port.vouchers(3L, false, null, null)).thenReturn(expected);
        when(port.vouchers(3L, true, "coffee", 1)).thenReturn(expected);
        when(port.createVoucher(voucherPayload)).thenReturn(expected);
        when(port.updateVoucher(12L, voucherPayload)).thenReturn(expected);
        when(port.updateVoucher(12L, Map.of("status", 2))).thenReturn(expected);

        assertThat(facade.queryVoucherOfShop(3L)).isSameAs(expected);
        assertThat(facade.queryAdminVouchers(1, 3L, "coffee", 1)).isSameAs(expected);
        assertThat(facade.createVoucher(voucher)).isSameAs(expected);
        assertThat(facade.updateVoucher(voucher)).isSameAs(expected);
        assertThat(facade.updateVoucherStatus(12L, 2)).isSameAs(expected);

        verify(port).vouchers(3L, false, null, null);
        verify(port).vouchers(3L, true, "coffee", 1);
        verify(port).createVoucher(voucherPayload);
        verify(port).updateVoucher(12L, voucherPayload);
        verify(port).updateVoucher(12L, Map.of("status", 2));
    }

    @Test
    void consistencyAndRecoveryDelegateToCommerceAndAuditWrites() {
        CommercePortClient port = mock(CommercePortClient.class);
        OperationLogService audit = mock(OperationLogService.class);
        SeckillConsistencyService consistency = new SeckillConsistencyService(port, audit);
        SeckillRecoveryService recovery = new SeckillRecoveryService(port, audit);
        Map<String, Object> snapshot = Map.of(
                "redisStock", 8,
                "expectedRedisStock", 8,
                "consistent", true,
                "conflicts", List.of(),
                "evidenceHash", "hash-1"
        );
        ConsistencyRepairResult repair = new ConsistencyRepairResult(7L, 8, 2, 0, "hash-1", "hash-2", true);
        Map<String, Object> verification = Map.of("verified", true);
        List<DeadLetterView> deadLetters = List.of(new DeadLetterView("1-0", "error", 3, null, null, null));
        DeadLetterReplayResult replay = new DeadLetterReplayResult("already_replayed", "1-0", null, null, null, null, null);
        when(port.consistency(7L)).thenReturn(snapshot);
        when(port.repairConsistency(7L, "hash-1")).thenReturn(repair);
        when(port.verifyConsistency(7L)).thenReturn(verification);
        when(port.deadLetters(5)).thenReturn(deadLetters);
        when(port.replayDeadLetter("1-0")).thenReturn(replay);

        assertThat(consistency.snapshot(7L)).isSameAs(snapshot);
        assertThat(consistency.repairStock(7L, "hash-1")).isSameAs(repair);
        assertThat(consistency.verify(7L)).isSameAs(verification);
        assertThat(recovery.listDeadLetters(5)).isSameAs(deadLetters);
        assertThat(recovery.replayDeadLetter("1-0")).isSameAs(replay);

        verify(port).repairConsistency(7L, "hash-1");
        verify(port).verifyConsistency(7L);
        verify(port).deadLetters(5);
        verify(port).replayDeadLetter("1-0");
        verify(audit).record("seckill", "stock_repair", "voucher", 7L, "7", "hash-1", repair, true, null);
        verify(audit).record("seckill", "dead_letter_replay", "dead_letter", null, "1-0", null, replay, true, null);
        assertThatThrownBy(() -> consistency.snapshot(0L))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
