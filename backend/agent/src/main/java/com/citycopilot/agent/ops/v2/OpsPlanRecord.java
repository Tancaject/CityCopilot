package com.citycopilot.agent.ops.v2;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 已持久化且带完整性哈希的 Ops 计划。
 *
 * @param id 计划标识
 * @param caseId 所属运维 case
 * @param version case 内递增版本
 * @param source 计划来源，如 LLM 或 MANUAL
 * @param diagnosis 诊断摘要
 * @param steps 受限执行步骤
 * @param planHash 绑定 case、证据与步骤的 SHA-256
 * @param status 计划审批/执行状态
 * @param createdAt 创建时间
 */
public record OpsPlanRecord(
        String id,
        String caseId,
        int version,
        String source,
        Map<String, Object> diagnosis,
        List<OpsPlanStep> steps,
        String planHash,
        String status,
        Instant createdAt
) {
}
