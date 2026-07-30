# ADR-0006：Commerce 与 Place 边界及数据所有权

状态：接受  
日期：2026-07-26

## 背景

为了展示真实微服务能力，需要抽出具有独立事务、一致性和扩缩容特征的领域，而不是按 Controller 数量拆服务。

## 决策

- `commerce-service` 拥有 coupon、seckill、reservation、order、outbox、inbox 数据以及业务 Redis key。
- `place-service` 仅在指标触发后抽出，拥有 shop、shop_type、GEO 和地理批任务。
- `core-service` 保留 Identity、Community 和外部兼容 API；订单、优惠券、一致性及死信能力仅通过 Commerce facade 调用，不保留运行时本地 writer 回退。
- 服务使用独立 schema 账号；禁止跨服务私表访问、跨库 Join 和数据库外键。
- 同步查询/命令使用版本化 HTTP；已发生事实使用 RocketMQ 事件。
- 当前 E4 采用维护窗口全量回填和单一 writer 切换；在 mutation change feed 覆盖完整之前不宣称通用在线 CDC，也不使用无事务应用双写。

## 结果与回滚

拆分增加网络失败成本，但形成可独立发布的数据所有权。Core 已硬路由到 Commerce，Commerce 不可用时失败关闭；旧 Core writer、Redis Stream worker、Redisson 锁、旧 Outbox、旧表初始化和迁移回退工具均已物理删除。Commerce 只会为“订单、Reservation、recovery 历史均为空且该券没有 in-flight admission”的全新秒杀券自动初始化 Redis 库存；存在历史数据但 Redis 库存缺失时保持准入关闭。库存或 buyer 成员不一致时，先用 Redis fence 暂停该券准入，确认该券 in-flight 集合为零，再通过带证据 hash 的一致性修复流程原子重建库存和 buyer set；共享 Stream 的 pending/lag 只作为全局健康诊断，不阻断无关券的修复。数据库的一人一券唯一约束始终作为 buyer 镜像异常时的最终防线。通用券更新接口禁止直接改变已有秒杀库存，避免 MySQL 与 Lua 准入库存分叉。故障处置只能修复或恢复当前 Commerce；若要恢复到历史实现，必须部署历史制品并单独迁移数据，不存在 profile 或环境变量回退入口。
