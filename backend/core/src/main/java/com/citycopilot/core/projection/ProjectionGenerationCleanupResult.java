package com.citycopilot.core.projection;

/** 单次维护操作的实际处置结果；未进入候选批的代际不包含在响应中。 */
public record ProjectionGenerationCleanupResult(String projection, String generation, String outcome, long unlinkedKeys) {
}
