package com.citycopilot.agent.memory;

/**
 * 长期偏好持久化失败的领域异常。
 *
 * <p>控制器单独捕获它并明确告知用户“未保存”，避免数据库异常被误当成普通聊天降级。</p>
 */
public final class PreferenceMemoryWriteException extends RuntimeException {

    public PreferenceMemoryWriteException(String message, Throwable cause) {
        super(message, cause);
    }
}
