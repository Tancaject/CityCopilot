# CityCopilot 演进功能开关

所有新链路默认关闭，只有对应阶段验证通过后才在本地或灰度环境启用。

| 环境变量 | 默认值 | 控制范围 | 回滚动作 |
|---|---:|---|---|
| `CITYCOPILOT_RESERVATION_ENABLED` | `false` | 秒杀 Reservation 状态机 | 回到原 Stream Worker，保留 Reservation 表 |
| `CITYCOPILOT_ROCKETMQ_ENABLED` | `false` | Outbox Relay 与 MQ Consumer | 停止发布/消费，不删除 Outbox |
| `CITYCOPILOT_OPS_AGENT_ENABLED` | `false` | 创建和调度 Ops Case | 停止新 Case，已有 Case 转人工 |
| `CITYCOPILOT_OPS_AUTO_EXECUTION_ENABLED` | `false` | 审批后的自动执行 | 只保留诊断、计划和审批 |
| `CITYCOPILOT_COMMERCE_REMOTE_ENABLED` | `false` | Core facade 调用 Commerce | 切回 Core 本地实现 |
| `CITYCOPILOT_COMMERCE_SHADOW_READ_ENABLED` | `false` | Commerce 影子读比对 | 关闭影子流量 |
| `CITYCOPILOT_PLACE_REMOTE_ENABLED` | `false` | Core facade 调用 Place | 切回 Core 本地实现 |
| `CITYCOPILOT_OPS_WORKER_ENABLED` | `false` | 独立 Ops Worker 部署 | 回到 agent-service 内执行 |

开关不得绕过权限、幂等、Inbox 或审批；关闭开关只改变路由和调度，不删除恢复所需数据。

