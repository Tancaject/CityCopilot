# Commerce 所有权与运行门禁

## 当前所有权

Commerce 是优惠券、秒杀准入、订单、Reservation、恢复记录和业务 Outbox 的唯一数据 owner。Core 保留外部兼容 API、权限、审计与 Tool 权威入口，并通过版本化 `CommercePortClient` 调用 Commerce；Commerce 不可用时交易失败关闭。

旧 Core 交易 writer、Redisson 锁、旧 Redis Stream/Lua、旧 Outbox Relay、旧交易表初始化和迁移回退工具已经从仓库物理删除。不存在可通过 Spring profile 或环境变量重新启用的本地 writer。

## 完整性门禁

运行：

```powershell
.\tools\migrations\verify-commerce-integrity.ps1
```

脚本只读取当前 `citycopilot_commerce` 数据库，验证：

- 九张必需的 Commerce 表均存在；
- `commerce_seckill_voucher` 不存在负库存。

该门禁不读取 Core 数据库，也不承担历史数据迁移对账。

## 排空门禁

运行：

```powershell
.\tools\migrations\commerce-runtime-drain-gate.ps1
```

脚本先执行完整性门禁，再要求：

- `commerce_outbox_event` 没有待发布、已认领或失败事件；
- 没有仍处于处理中或待释放的 Reservation；
- 没有未重放 DLQ；
- `commerce_order_recovery` 没有未解决恢复阶段；
- `commerce:stream:orders` 的 pending、consumer lag 和单券 in-flight 总数均为零。

门禁只做读取与验证，不删除数据、不改变路由。

## 故障与恢复边界

- RocketMQ 故障时保留 Commerce Outbox，恢复 Proxy 后由 `CommerceOutboxRelay` 继续发布。
- Redis Stream 或订单事务异常时，由 `commerce_order_recovery`、补偿/恢复 Lua 和 DLQ 保持可恢复状态。
- Core 的 `processed_event` 与 `event_projection_version` 是当前 RocketMQ Inbox/版本审计表，不属于旧交易链路。
- 不提供切回旧 Core writer 的运行时回滚。若必须恢复历史版本，应在隔离环境部署历史制品，并执行单独的数据恢复方案。

## 已有数据卷

删除仓库代码和 SQL 不会删除已有 Docker volume 中的历史表或 Redis key。清理历史数据必须作为独立运维变更执行：先停止历史进程、备份、核对 Commerce 数据和未完成消息，再对明确目标执行定向删除。不得用 `reset -Force` 或 `FLUSHDB` 代替定向清理，也不得删除当前 Core Inbox 表或任何 `commerce_*` 表。
