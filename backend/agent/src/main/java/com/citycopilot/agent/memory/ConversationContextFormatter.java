package com.citycopilot.agent.memory;

/**
 * 内存与 Redis 会话实现共用的模型上下文格式器。
 *
 * <p>统一角色标签、消息数量和字符预算，避免两种存储产生不同的模型输入。</p>
 */
final class ConversationContextFormatter {
    /** 一个会话最多保留的单条消息数，即六轮问答。 */
    static final int MAX_MESSAGES = 12;
    /** 送入模型的格式化会话最大字符数。 */
    private static final int MAX_CONTEXT_CHARS = 6_000;

    private ConversationContextFormatter() {
    }

    /**
     * 按时间顺序拼接历史与当前请求，超出预算时保留靠近末尾的最新内容。
     */
    static String format(Iterable<ConversationMessage> history, String currentMessage) {
        StringBuilder context = new StringBuilder("Conversation history:\n");
        history.forEach(item -> context
                .append(item.role())
                .append(": ")
                .append(item.content())
                .append('\n'));
        context.append("Current user: ").append(safe(currentMessage));
        return context.length() <= MAX_CONTEXT_CHARS
                ? context.toString()
                : context.substring(context.length() - MAX_CONTEXT_CHARS);
    }

    /** 将可空消息归一为空串，避免持久化或拼接文字 {@code null}。 */
    static String safe(String value) {
        return value == null ? "" : value;
    }
}
