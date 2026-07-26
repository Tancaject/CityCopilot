# Commerce Mutation Coverage Matrix

| 写路径 | 权威入口 | 原子持久化 | Redis/事件副作用 | 幂等与并发保护 | 已验证 |
|---|---|---|---|---|---|
| 优惠券新增 | `POST /commerce/v1/vouchers` | voucher + shop projection | 无 | Place 最终授权，失败关闭 | 是 |
| 优惠券修改/状态 | `POST /commerce/v1/vouchers/{id}` | optimistic version | 无 | Place 最终授权，版本冲突拒绝 | 是 |
| 秒杀配置 | Commerce schema migration/admin | voucher + seckill config | `commerce:seckill:stock:*` 重建 | PK、非负库存 | 是 |
| 订单创建 | Lua + Commerce Stream Worker | Reservation + stock decrement + order snapshot + Outbox | stock/buyer/stream | 一人一单、idempotency key、唯一 worker identity | 是 |
| 支付 | order action `pay` | optimistic state + Outbox | Commerce event | 状态机/version | 是 |
| 取消 | order action `cancel` | state + inventory + Outbox | 恢复 stock、移除 buyer | 状态机/version、恢复幂等 | 是 |
| 核销 | order action `use` | optimistic state + Outbox | Commerce event | 状态机/version | 是 |
| 退款 | order action `refund` | state + inventory + Outbox | 恢复 stock、移除 buyer | 状态机/version、恢复幂等 | 是 |
| Reservation 恢复 | worker/recovery | 状态推进或 DLQ | claim/compensation | unique user+voucher、stream record | 是 |
| 库存修复 | Ops Agent tool | 无跨域 SQL | 仅修复 `commerce:*` 热镜像 | evidence hash + 人工审批 + 单次执行 + verifier | 是 |

订单保存店铺和优惠券快照，历史展示不需要同步调用 Place。创建和修改优惠券仍调用 Place 权威接口，Place 不可用时写操作 fail closed。
