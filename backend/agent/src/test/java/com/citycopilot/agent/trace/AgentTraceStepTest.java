package com.citycopilot.agent.trace;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentTraceStepTest {

    @Test
    void toolCallAllowsNullArgumentValues() {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("keywords", "hotpot");
        args.put("adcode", null);

        AgentTraceStep step = AgentTraceStep.toolCall(1, "search_geo_poi", args);

        assertTrue(step.args().containsKey("adcode"));
    }
}
