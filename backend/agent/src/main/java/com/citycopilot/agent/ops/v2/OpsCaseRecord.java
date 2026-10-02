package com.citycopilot.agent.ops.v2;

import java.time.Instant;

/**
 * Ops Case 状态机的当前数据库投影。
 *
 * @param id case 标识
 * @param incidentKey 跨重复事件聚合的事故键
 * @param eventId 首次创建 case 的事件标识
 * @param caseType 事故类型
 * @param status 当前状态
 * @param version 乐观锁版本
 * @param currentStep 当前计划步骤位置
 * @param retryCount 自动/人工重试次数
 * @param cancellationRequested 是否收到协作式取消请求
 * @param lastError 最近失败摘要
 * @param createdAt 创建时间
 * @param updatedAt 最近状态更新时间
 */
public record OpsCaseRecord(
        String id,
        String incidentKey,
        String eventId,
        String caseType,
        OpsCaseStatus status,
        int version,
        int currentStep,
        int retryCount,
        boolean cancellationRequested,
        String lastError,
        Instant createdAt,
        Instant updatedAt
) {
}
