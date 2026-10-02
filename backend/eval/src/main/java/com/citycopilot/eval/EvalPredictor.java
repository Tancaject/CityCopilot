package com.citycopilot.eval;

import java.util.Map;

/** 为评测运行器抽象离线启发式和在线模型两种预测来源。 */
interface EvalPredictor {
    /**
     * @param evalCase 单条评测输入及期望
     * @return 模式、工具、回答与可选 token 统计
     */
    EvalPrediction predict(EvalCase evalCase);

    /** @return 写入报告头部的预测来源元数据 */
    default Map<String, String> metadata() {
        return Map.of("provider", "offline");
    }
}
