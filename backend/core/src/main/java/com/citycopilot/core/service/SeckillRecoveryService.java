package com.citycopilot.core.service;

import com.citycopilot.contract.commerce.CommerceContracts.DeadLetterView;
import com.citycopilot.contract.commerce.CommerceContracts.DeadLetterReplayResult;
import com.citycopilot.contract.commerce.CommerceContracts.PaymentReview;
import com.citycopilot.core.commerce.CommercePortClient;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Commerce 秒杀死信的运维门面。
 *
 * <p>Reservation 恢复在 Commerce 内完成；Core 不轮询或重放已经退役的本地订单 Stream。</p>
 */
@Service
public class SeckillRecoveryService {

    /** Commerce 死信查询与重放客户端。 */
    private final CommercePortClient commercePortClient;
    /** 重放操作的审计记录。 */
    private final OperationLogService operationLogService;

    /**
     * @param commercePortClient Commerce 服务客户端
     * @param operationLogService 审计日志服务
     */
    public SeckillRecoveryService(CommercePortClient commercePortClient, OperationLogService operationLogService) {
        this.commercePortClient = commercePortClient;
        this.operationLogService = operationLogService;
    }

    /**
     * 查询近期秒杀死信，供运维人员诊断失败原因。
     *
     * @param limit 最大返回条数；远端会进一步限制安全上限
     * @return 死信记录列表
     */
    public List<DeadLetterView> listDeadLetters(Integer limit) {
        return commercePortClient.deadLetters(limit);
    }

    public List<PaymentReview> paymentIssues() { return commercePortClient.paymentIssues(); }

    public PaymentReview paymentReview(String paymentId) { return commercePortClient.paymentReview(paymentId); }

    public PaymentReview repairPayment(String paymentId, String evidenceHash) {
        try {
            PaymentReview result = commercePortClient.repairPayment(paymentId, evidenceHash);
            operationLogService.record("payment", "reconcile_repair", "payment", null, paymentId,
                    evidenceHash, result, true, null);
            return result;
        } catch (RuntimeException error) {
            operationLogService.record("payment", "reconcile_repair", "payment", null, paymentId,
                    evidenceHash, null, false, error.getMessage());
            throw error;
        }
    }

    /**
     * 按记录主键重放一条死信。
     *
     * @param deadLetterRecordId Commerce 死信记录主键
     * @return 幂等重放结果
     */
    public DeadLetterReplayResult replayDeadLetter(String deadLetterRecordId) {
        try {
            DeadLetterReplayResult result = commercePortClient.replayDeadLetter(deadLetterRecordId);
            operationLogService.record("seckill", "dead_letter_replay", "dead_letter", null, deadLetterRecordId,
                    null, result, true, null);
            return result;
        } catch (RuntimeException error) {
            operationLogService.record("seckill", "dead_letter_replay", "dead_letter", null, deadLetterRecordId,
                    null, null, false, error.getMessage());
            throw error;
        }
    }
}
