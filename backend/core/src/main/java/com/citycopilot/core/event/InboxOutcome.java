package com.citycopilot.core.event;

/** RocketMQ 事件进入 Core Inbox 后的幂等与顺序判定结果。 */
public enum InboxOutcome {
    /** 首次收到且版本不落后，可以推进投影水位。 */
    PROCESSED,
    /** 同一 consumerGroup/eventId 和相同载荷已处理。 */
    DUPLICATE,
    /** 聚合版本低于当前投影水位，无需再次应用。 */
    STALE,
    /** 相同 eventId 携带不同载荷，已隔离等待人工处理。 */
    QUARANTINED
}
