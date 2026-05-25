package com.citycopilot.agent.executor;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentDecisionTest {

    @Test
    void toolCallAllowsNullArgumentValues() {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("city", "Shanghai");
        args.put("adcode", null);

        AgentDecision decision = AgentDecision.callTool("search_geo_poi", args);

        assertTrue(decision.args().containsKey("adcode"));
    }
}
