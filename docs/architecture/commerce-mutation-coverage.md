# Commerce Mutation Coverage Matrix

| 写路径 | 权威入口 | 持久化与副作用 | 幂等/并发语义 | 当前验证边界 |
|---|---|---|---|---|
| 优惠券新增 | `POST /commerce/v1/vouchers` | voucher + shop projection；事务提交后初始化秒杀 Redis 状态并失效店铺券列表缓存 | Place 最终授权，失败关闭；ID 生成器 | 创建接口与跨服务授权的端到端结果见 [E6] |
| 优惠券修改/状态 | `POST /commerce/v1/vouchers/{id}` | voucher/seckill version 自增；提交后刷新活动元数据、失效券列表缓存 | Place 最终授权；活动修订号阻止旧事务回填；没有 expected-version 条件更新 | `SeckillLifecycleIT` 覆盖修改后缓存失效及旧快照不能回填；HTTP 验证见 [E6] |
| 券列表与售罄缓存 | 券列表查询、秒杀准入及库存返还 | 券列表 Redis 缓存 5 秒；本地售罄标记 1 秒，库存返还通过 Pub/Sub 清除 | 券列表版本校验；售罄快速拒绝不写账本，并保留已持久化请求的幂等重试；库存仍由 Lua 裁决 | `SeckillLifecycleIT` 覆盖缓存更新、售罄重试和跨缓存实例广播；访问量对照见 [E6] |
| 秒杀配置 | Commerce schema/admin | voucher + seckill config；安全初始化/修复 Redis 热镜像 | PK、字段校验；已有券禁止直接调整 stock | `SeckillLifecycleIT` 使用真实 Redis 初始化；管理端操作结果见 [E6] |
| 订单创建 | Lua + Commerce Stream Worker | Reservation + DB stock + order snapshot + Outbox；Redis stock/buyer/stream | 同一用户同一券最多一笔未取消订单；幂等键、持久准入与 worker claim | `SeckillLifecycleIT` 覆盖并发不超卖、重复请求、Stream 重投和准入恢复；跨 JVM 压测见 [E6] |
| 支付 | order action `pay` + HMAC 验签回调 | 先持久化支付单和回调时间；异步模拟回调原子更新支付单、订单及 Outbox | payment/order 唯一约束、渠道流水唯一约束；验签与金额校验；支付与关单竞争，迟到支付转退款 | `SeckillLifecycleIT` 覆盖回调幂等、迟到回调、待回调恢复及支付/关单竞态；HTTP 验证见 [E6] |
| 取消 | order action `cancel` | 订单关闭 + DB/Redis 库存返还 + Outbox；释放 Reservation 与 buyer | 条件状态更新、库存 fence 和恢复 marker；取消后可使用新幂等键重买 | `SeckillLifecycleIT` 覆盖库存只返还一次、释放 buyer 后重新准入；见 [E6] |
| 超时关单 | `CommerceOrderStreamWorker.expireDueOrders` | 到期未支付订单关闭，记录 `PAY_TIMEOUT`，按取消路径返还库存与购买资格 | 每行事务 `FOR UPDATE SKIP LOCKED`；与支付竞争同一订单，恢复记录与 Outbox 同事务提交 | `SeckillLifecycleIT` 覆盖超时重买、双 worker 不重复返还；跨 JVM 故障结果见 [E6] |
| 核销 | order action `use` | 条件状态更新 + Outbox | 已支付订单才可核销；商家只能核销本人店铺订单 | 本轮未新增专门 HTTP 验证 |
| 退款 | order action `refund` | 支付单与订单原子转已退款；DB/Redis 库存返还 + Outbox | 库存 fence/marker；保留 buyer 和未取消订单唯一约束，退款后不能重买 | `SeckillLifecycleIT` 覆盖用户退款原子性和库存恢复；见 [E6] |
| 支付对账与修复 | 每日支付对账、管理员支付修复接口 | 记录支付/订单差异；依据已持久化渠道支付证据修复支付状态 | 对账逐条 fencing；修复锁订单和支付单并核对 evidence hash，不恢复已取消订单 | `SeckillLifecycleIT` 覆盖支付状态与过期修复证据；日调度时间点和管理员页面未验，本次未新增支付管理页面 |
| Reservation/消费恢复 | worker/recovery | 持久恢复阶段 + DLQ；Redis admission 补偿 | source record、claim token/lease；终态重投只重试 ACK；库存恢复逐行 `SKIP LOCKED` | `SeckillLifecycleIT` 覆盖重投与准入恢复；实际 XCLAIM/进程中断结果见 [E6] |
| 库存修复 | 管理员 `/admin/seckill/consistency/{id}/repair` | 只重建 Commerce Redis 热镜像，按未取消订单和有效 Reservation 重建 buyer | 单券 in-flight、buyer 成员证据、evidence hash、fence | `SeckillLifecycleIT` 迁移场景覆盖旧取消订单的证据修复；Redis 故障演练见 [E6] |

订单保存店铺和优惠券快照，历史展示不需要同步调用 Place。创建和修改优惠券仍调用 Place 权威接口，Place 不可用时写操作失败关闭。上表的 `SeckillLifecycleIT` 使用真实 MySQL/Redis，但替换了 Place 客户端，也不代表两个 JVM 的调度与故障验收；浏览器、压测和故障结果统一以 [E6] 已记录的范围为准。

[E6]: ../evidence/2026-07-26-e6-performance-and-recovery.md
