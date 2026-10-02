package com.citycopilot.agent.confirmation;

import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.SerializationFeature;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * 计划确认快照的完整性工具。
 *
 * <p>以稳定字段顺序计算 SHA-256；确认恢复前可据此发现持久化内容被修改或序列化不一致。</p>
 */
public final class PlanCheckpointIntegrity {
    /** V1 审批哈希保留 Jackson 2 的编码约定，不能随运行时默认值变化。 */
    private static final ObjectMapper MAPPER = JsonMapper.builderWithJackson2Defaults()
            .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
            .disable(MapperFeature.SORT_CREATOR_PROPERTIES_FIRST)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .build();

    private PlanCheckpointIntegrity() {
    }

    /**
     * 计算检查点的规范化摘要。
     *
     * @param checkpoint 待保护的计划快照
     * @return 小写十六进制 SHA-256
     */
    public static String hash(PlanCheckpoint checkpoint) {
        try {
            byte[] canonical = MAPPER.writeValueAsBytes(checkpoint);
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical);
            return HexFormat.of().formatHex(digest);
        } catch (Exception error) {
            throw new IllegalStateException("Unable to hash plan checkpoint", error);
        }
    }

    /**
     * 使用常量时间比较校验待确认动作携带的计划哈希。
     *
     * @param action 待恢复动作
     * @return 快照完整且哈希匹配时为 true
     */
    public static boolean valid(PendingAction action) {
        if (action == null || action.planHash() == null || action.planHash().isBlank()) {
            return false;
        }
        return MessageDigest.isEqual(
                action.planHash().getBytes(StandardCharsets.UTF_8),
                hash(toCheckpoint(action)).getBytes(StandardCharsets.UTF_8)
        );
    }

    /** 将持久化动作中的计划字段还原为执行器可恢复的检查点。 */
    public static PlanCheckpoint toCheckpoint(PendingAction action) {
        return new PlanCheckpoint(
                action.planVersion(),
                action.originalMessage(),
                action.hints(),
                action.steps(),
                action.currentStep(),
                action.completedOutputs(),
                action.evidenceIds(),
                action.verificationSteps(),
                action.toolContracts()
        );
    }
}
