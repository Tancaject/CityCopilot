package com.citycopilot.agent.ops.v2;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 根据当前 case 与新鲜证据生成受限修复计划的端口。
 *
 * <p>证据不足或模型不可用时返回空，由 worker 转交人工处理，不允许猜测修复参数。</p>
 */
public interface OpsDiagnosisPlanner {
    /**
     * @param opsCase 正在收集证据的 case
     * @param evidence 尚未过期的证据记录
     * @return 可验证的诊断计划；无法安全规划时为空
     */
    Optional<DiagnosisPlan> plan(OpsCaseRecord opsCase, List<Map<String, Object>> evidence);

    /**
     * 规划器输出的诊断说明与执行步骤。
     *
     * @param diagnosis 根因、摘要和置信度等结构化信息
     * @param steps 仍须经过校验与审批的候选步骤
     */
    record DiagnosisPlan(Map<String, Object> diagnosis, List<OpsPlanStep> steps) {
    }
}
