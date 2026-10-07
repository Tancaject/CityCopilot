# ADR-0009：Commerce 高并发准入与恢复语义

状态：接受（核心路径已实现，Redis Cluster/故障注入尚未完成生产验证）
日期：2026-08-04
补充：ADR-0005、ADR-0006

## 背景

秒杀流量具有短时突发、单券热点和大量失败请求。若同步热路径持有 MySQL 行锁或依赖应用分布式锁，同一库存行会串行化，并把流量直接传导到数据库。Redis Lua、Stream、MySQL 和 RocketMQ 之间又不存在跨系统事务，因此必须明确每个故障窗口的恢复和幂等语义。

## 决策

- Commerce 是优惠券、库存、Reservation、订单、Recovery 和 Outbox 的唯一 writer；Core 不保留本地回退 writer。
- 秒杀同步路径不查询订单/券/库存事实表，也不持有热点库存行锁；它先在 MySQL admission ledger 持久化 `PREPARED` 身份，再用 Redis 建立跨券全局幂等身份并执行券分片同槽 Lua。常态 ID 从 JVM 本地 hi/lo 号段取得；号段耗尽时会摊销执行一次短 MySQL `commerce_id_segment` 分配事务（应用默认 1024，生产环境可在压测后通过 `CITYCOPILOT_COMMERCE_ID_SEGMENT_SIZE` 调大）。
- 全局幂等注册与券 Lua 是可补完的两阶段流程，不是跨槽原子事务：注册成功后崩溃，重试沿用同一 orderId 完成准入；相同 user/key 的不同券或 fingerprint 永久冲突。Redis failover 的已确认写语义仍依赖运行环境。
- API 只有在券 Lua 写入获得复制确认且 admission ledger 从 `PREPARED` 条件更新为 `ACCEPTED` 后才返回新的 `202 PROCESSING`；幂等重放返回 `200` 和同一 orderId。若 Lua 已写主节点但复制确认/响应失败，返回 `COMMERCE_ADMISSION_OUTCOME_UNKNOWN`、`Retry-After: 1`，客户端必须使用同一 `Idempotency-Key` 重试完成决议。
- 券 Lua 使用 Redis `TIME`，原子完成活动/fence/时间窗、本券 fingerprint、一人一券、库存预扣、buyer、inflight 和 Stream XADD，成功返回 202 PROCESSING。
- 所有实例按 voucherId、固定 routing generation 和逻辑 shard-count 确定性定位键；一次券 Lua 使用的 key 带相同 `{commerce:<generation>:<count>:<logicalShard>}` hash tag，以便将来迁到 Redis Cluster 时满足同槽约束。generation 与 shard-count 是部署不变量，写入键名和账本，不支持运行中切换。
- 当前每券只有一个库存 bucket。超热点活动的多 bucket、escrow/transfer token 尚未实现。全局幂等和券 Lua 在同一连接上按 `CITYCOPILOT_COMMERCE_REDIS_REQUIRED_REPLICAS` 执行 `WAIT`（默认 0，即单机不等待）；它只覆盖单 master 故障边界。
- Redis Stream 只承载 Commerce 内部命令。每个逻辑 shard 一条 Stream，Group 从 0 创建；正常读取逐 Stream 使用 XREADGROUP，Pending 恢复使用完整 XPENDING cursor + XCLAIM。终态使用同槽 Lua `XACK` 后 `XDEL`，防止 Stream 永久增长。
- Worker 创建订单的 MySQL 本地事务原子写入 Reservation、条件库存扣减、订单、Recovery 终态和 Outbox；事务内禁止 Redis、MQ 和 HTTP。失败补偿按“持久 DB 阶段 -> 事务外幂等 Redis marker/Lua -> 新 DB 事务终态 -> Redis finalization”重放。
- DB 提交后执行幂等 Redis finalization、ACK 和 XDEL。响应丢失允许重放，数据库唯一约束和 Recovery 终态消除重复业务效果。超过 delivery 上限的 poison command 先核对 durable ORDER_CREATED；已落单只 finalization，能证明未落单才补偿，无法证明身份则持久化 DLQ 后保留 inflight 作为人工修复信号。
- Outbox Relay 读取候选 ID 后以带状态条件的原子 `UPDATE` 竞争可过期 lease，并在所有终态更新中校验 claim/fencing token；READY、FAILED、历史 DEAD 和已过期 CLAIMED 都可重新认领。
- 跨服务事件采用 at-least-once。消费者将 Inbox、聚合版本和本地业务变化写入同一事务。
- Redis 命令、活动 metadata 或库存 authority 不可用时 fail closed，返回稳定 503；绝不回退到 MySQL 抢锁或旧 Core worker。逐请求复制确认只覆盖单 master 故障边界，双故障、持久化和相关性故障仍是生产配置与故障注入边界。
- `Idempotency-Key` 对外部秒杀写请求必填；相同 user/key 与同 fingerprint 返回相同 orderId，相同 user/key 与不同 fingerprint 永久冲突。其他管理写接口尚未统一纳入该协议。
- 券更新先以同槽 revision Lua 原子递增 fence、写入带 TTL 的 update marker 并删除 metadata，提交后只允许相同 revision CAS 发布；singleton reconciliation 在 marker 存活期间不得重开 metadata，marker 超时后再从 MySQL 恢复崩溃或回滚遗留的关闭状态。marker TTL 必须大于管理事务超时；旧事务不能重开新事务已关闭的活动。

## 必须保持的不变量

- Redis bucket 库存不小于 0；
- 有效订单数不超过活动总库存；
- 同一用户对同一券最多一个有效订单；
- 一个 Stream command 最多一次 MySQL 业务效果；
- 一个聚合版本最多一条同类型 Outbox；
- MQ 重复投递不会产生重复本地业务效果；
- 补偿、库存转移、finalization、repair 重放结果相同。

## 被否决的方案

- MySQL `FOR UPDATE` 作为秒杀入口：热点库存行会串行化。
- Redisson 用户锁作为最终正确性：无法覆盖 Redis 准入、DB commit、Stream ACK 和 MQ publish 的跨系统故障窗口。
- 只使用 RocketMQ 取代 Redis Stream：无法让库存校验、用户去重和命令入队处于同一 Redis 原子边界。
- 分布式事务/XA：跨 Redis、MySQL 和 MQ 的可用性、延迟和运维成本不可接受。
- Redis 失败时同步落 MySQL：会在故障时把全部峰值转移到事实库。

## 结果

系统以异步完成换取稳定峰值吞吐，并通过持久化 Recovery、唯一约束、Outbox/Inbox 和补偿得到可恢复的业务效果。代价是客户端必须接受 PROCESSING 状态，平台必须长期运行对账、积压告警和故障注入验证。

当前部署是单机 Redis。准入、补偿、重复投递、丢失 ACCEPTED 后重试和取消返库存由 [`SeckillLifecycleIT`](../../backend/commerce-service/src/test/java/com/citycopilot/commerce/SeckillLifecycleIT.java) 在真实 MySQL/Redis 上验证；Redis Cluster 下的 MOVED、主从切换和复制确认尚未验证。修改 routing generation 或 shard-count 等同于数据迁移，只能在停止准入并排空后整体重启，不能作为滚动配置变更。

## 未实现：在线分片扩容

单 Redis 成为瓶颈时，计划的在线扩容方式如下。它曾有实现，但因从未在 Cluster 上验证且每次准入增加 3～4 次 Redis 往返而移除（2026-10），需要时以本节为设计起点重新实现并在真实 Cluster 上验收：

- 全局 control key 记录 `ACTIVE|DRAINING`、generation 与 shard-count；每个逻辑 shard 一个与券键同槽的 gate，准入 Lua 在同槽内复核，封住"API 读到 ACTIVE 后运维开始排空"的竞态。
- 运维 CAS 执行 `ACTIVE(old) -> DRAINING(old) -> ACTIVE(new)`：DRAINING 拒绝新准入，但旧 Stream worker 与 ACCEPTED 账本恢复继续运行；只有 Stream/PEL、ACCEPTED 账本、Recovery、Outbox 与在途写请求全部归零后才激活目标代际。
- 在途写请求用 Redis 租约计数（准入按 shard、其他写按全局），运维以独占租约阻止新写进入；尚未返回 202 的 `PREPARED` 账本在激活前后幂等改写到目标代际。
- 库存、buyer、活动元数据和全局幂等键的迁移是独立数据迁移，排空为零不能替代它。
