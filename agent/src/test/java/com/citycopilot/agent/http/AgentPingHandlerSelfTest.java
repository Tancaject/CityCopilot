package com.citycopilot.agent.http;

import java.util.Map;

public final class AgentPingHandlerSelfTest {

    public static void main(String[] args) {
        Map<String, Object> response = new AgentPingHandler().ping();

        assertEquals("city-copilot", response.get("application"), "application");
        assertEquals("ok", response.get("status"), "status");
    }

    private static void assertEquals(Object expected, Object actual, String label) {
        if (!expected.equals(actual)) {
            throw new AssertionError(label + " expected <" + expected + "> but was <" + actual + ">");
        }
    }
}
