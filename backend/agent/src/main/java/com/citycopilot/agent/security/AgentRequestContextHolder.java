package com.citycopilot.agent.security;

public final class AgentRequestContextHolder {
    private static final ThreadLocal<AgentRequestContext> CONTEXT = new ThreadLocal<>();

    private AgentRequestContextHolder() {
    }

    public static AgentRequestContext get() {
        AgentRequestContext context = CONTEXT.get();
        return context == null ? AgentRequestContext.anonymous(null) : context;
    }

    public static void set(AgentRequestContext context) {
        CONTEXT.set(context);
    }

    public static void clear() {
        CONTEXT.remove();
    }
}
