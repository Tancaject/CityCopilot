package com.citycopilot.agent.router;

public final class AgentRouterSelfTest {

    public static void main(String[] args) {
        AgentRouter router = new AgentRouter();

        assertEquals(Mode.REACT, router.classify("附近有什么火锅"), "simple nearby search");
        assertEquals(Mode.PLAN_EXECUTE, router.classify("附近 50 块以内有券的火锅，帮我下单"), "write intent");
        assertEquals(Mode.PLAN_EXECUTE, router.classify("先找附近火锅，然后列出优惠券，最后帮我下单"), "sequenced intent");
    }

    private static void assertEquals(Object expected, Object actual, String label) {
        if (!expected.equals(actual)) {
            throw new AssertionError(label + " expected <" + expected + "> but was <" + actual + ">");
        }
    }
}
