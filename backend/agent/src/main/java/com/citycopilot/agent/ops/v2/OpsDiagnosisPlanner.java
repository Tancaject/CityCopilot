package com.citycopilot.agent.ops.v2;

import java.util.List;
import java.util.Map;
import java.util.Optional;

public interface OpsDiagnosisPlanner {
    Optional<DiagnosisPlan> plan(OpsCaseRecord opsCase, List<Map<String, Object>> evidence);

    record DiagnosisPlan(Map<String, Object> diagnosis, List<OpsPlanStep> steps) {
    }
}
