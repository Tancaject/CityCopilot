package com.citycopilot.agent.security;

/**
 * 当前请求安全上下文的线程本地容器。
 *
 * <p>MVC 拦截器负责普通请求的设置与清理；异步 SSE 任务必须显式复制上下文，并在 finally 中调用 {@link #clear()} 防止线程复用串号。</p>
 */
public final class AgentRequestContextHolder {
    /** 与当前执行线程绑定的请求上下文。 */
    private static final ThreadLocal<AgentRequestContext> CONTEXT = new ThreadLocal<>();

    private AgentRequestContextHolder() {
    }

    /** @return 当前上下文；未设置时返回匿名上下文而非 null */
    public static AgentRequestContext get() {
        AgentRequestContext context = CONTEXT.get();
        return context == null ? AgentRequestContext.anonymous(null) : context;
    }

    /** 将已验证上下文绑定到当前线程。 */
    public static void set(AgentRequestContext context) {
        CONTEXT.set(context);
    }

    /** 移除当前线程上下文，避免线程池复用泄露身份。 */
    public static void clear() {
        CONTEXT.remove();
    }
}
