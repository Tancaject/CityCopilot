package com.citycopilot.core.service;

import com.citycopilot.contract.commerce.CommerceContracts.ConsistencyRepairResult;
import com.citycopilot.core.commerce.CommercePortClient;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * 秒杀一致性运维门面，把证据采集、修复和复核转交 Commerce 服务。
 *
 * <p>Core 只负责参数边界和审计，不直接修改 Commerce 的 Redis 或 MySQL 数据。</p>
 */
@Service
public class SeckillConsistencyService {

    /** Commerce 运维接口客户端。 */
    private final CommercePortClient commercePortClient;
    /** 修复操作的审计记录。 */
    private final OperationLogService operationLogService;

    /**
     * @param commercePortClient Commerce 服务客户端
     * @param operationLogService 审计日志服务
     */
    public SeckillConsistencyService(CommercePortClient commercePortClient, OperationLogService operationLogService) {
        this.commercePortClient = commercePortClient;
        this.operationLogService = operationLogService;
    }

    /**
     * 采集单张秒杀券在 MySQL、Redis、Reservation、订单和 Outbox 中的有界证据。
     *
     * @param voucherId 秒杀券主键
     * @return 一致性快照及证据哈希
     */
    public Map<String, Object> snapshot(Long voucherId) {
        requireVoucherId(voucherId);
        return commercePortClient.consistency(voucherId);
    }

    /**
     * 在证据哈希仍匹配且没有在途冲突时修复 Redis 库存。
     *
     * @param voucherId 秒杀券主键
     * @param expectedEvidenceHash 操作者读取快照时获得的证据哈希，防止基于陈旧证据写入
     * @return Commerce 的修复结果
     */
    public ConsistencyRepairResult repairStock(Long voucherId, String expectedEvidenceHash) {
        requireVoucherId(voucherId);
        try {
            ConsistencyRepairResult result = commercePortClient.repairConsistency(voucherId, expectedEvidenceHash);
            operationLogService.record("seckill", "stock_repair", "voucher", voucherId, String.valueOf(voucherId),
                    expectedEvidenceHash, result, true, null);
            return result;
        } catch (RuntimeException error) {
            operationLogService.record("seckill", "stock_repair", "voucher", voucherId, String.valueOf(voucherId),
                    expectedEvidenceHash, null, false, error.getMessage());
            throw error;
        }
    }

    /**
     * 重新独立采集证据并验证修复后置条件。
     *
     * @param voucherId 秒杀券主键
     * @return 最新一致性结论
     */
    public Map<String, Object> verify(Long voucherId) {
        requireVoucherId(voucherId);
        return commercePortClient.verifyConsistency(voucherId);
    }

    /**
     * 拒绝空值和非正券主键，避免向远端发出无意义运维请求。
     *
     * @param voucherId 待校验券主键
     */
    private void requireVoucherId(Long voucherId) {
        if (voucherId == null || voucherId <= 0) {
            throw new IllegalArgumentException("voucherId must be positive");
        }
    }
}
