# Commerce Mutation Coverage Matrix

| 写路径 | 权威入口 | 持久化与副作用 | 幂等/并发语义 | 当前验证边界 |
|---|---|---|---|---|
| 优惠券新增 | `POST /commerce/v1/vouchers` | voucher + shop projection；事务提交后初始化 `commerce:seckill:*` | Place 最终授权，失败关闭；ID 生成器 | Mockito 单元测试；未做真实 MySQL/Redis/HTTP 集成 |
| 优惠券修改/状态 | `POST /commerce/v1/vouchers/{id}` | voucher/seckill version 自增 | Place 最终授权；当前没有 expected-version 条件，不能宣称乐观锁冲突拒绝 | Mockito 单元测试；未做并发写集成 |
| 秒杀配置 | Commerce schema/admin | voucher + seckill config；安全初始化/修复 Redis 热镜像 | PK、字段校验；已有券禁止直接调整 stock | schema 与单元测试；未做真实 Redis 初始化集成 |
| 订单创建 | Lua + Commerce Stream Worker | Reservation + DB stock + order snapshot + Outbox；Redis stock/buyer/stream | 一人一单、idempotency key、持久消费状态与 worker claim | 单元/故障路径测试；真实多实例 Redis/MySQL 竞态尚未运行 |
| 支付 | order action `pay` | 条件状态更新 + Outbox | 状态机条件更新 | 代码与聚合测试；无专门 HTTP 集成 |
| 取消 | order action `cancel` | 状态 + DB/Redis inventory + Outbox | fence/token；buyer 保留，因为 schema 规定同一用户对同一券终身唯一 | 代码与故障路径测试；无真实 Redis/MySQL 集成 |
| 核销 | order action `use` | 条件状态更新 + Outbox | 状态机条件更新 | 代码与聚合测试；无专门 HTTP 集成 |
| 退款 | order action `refund` | 状态 + DB/Redis inventory + Outbox | fence/token；buyer 保留，因为 schema 规定同一用户对同一券终身唯一 | 代码与故障路径测试；无真实 Redis/MySQL 集成 |
| Reservation/消费恢复 | worker/recovery | 持久恢复阶段 + DLQ；Redis admission 补偿 | source record、claim token/lease、终态重投只重试 ACK | 单元/故障路径测试；真实 XCLAIM 多实例竞态尚未运行 |
| 库存修复 | Ops Agent tool | 不写 Core 旧表；只重建 Commerce Redis 热镜像 | 单券 in-flight、buyer 成员证据、evidence hash、fence | 单元测试；真实 Redis Lua/并发修复尚未运行 |

订单保存店铺和优惠券快照，历史展示不需要同步调用 Place。创建和修改优惠券仍调用 Place 权威接口，Place 不可用时写操作 fail closed。这里的“单元测试”不等价于生产验证；容器、ACL、真实 Redis/MySQL/Lua 和 HTTP 链路需在 Docker 可用时另行验收。
