# CityCopilot 演进功能开关

下表只列仍允许在本地或灰度环境切换的演进能力。

| 环境变量 | 默认值 | 控制范围 | 回滚动作 |
|---|---:|---|---|
| `CITYCOPILOT_KAFKA_ENABLED` | `true` | Outbox Relay 与 Kafka Consumer | 故障隔离时可停止发布/消费，但不得删除 Outbox |

开关不得绕过权限、幂等或 Inbox；关闭开关只改变路由和调度，不删除恢复所需数据。Core→Commerce 路由不是功能开关：Commerce 不可用时交易失败关闭，仓库中不存在可由环境变量恢复的 Core 交易 writer。
