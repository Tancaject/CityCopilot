# ADR-0005：Redis Stream 与 RocketMQ 分层消息

状态：接受  
日期：2026-07-26  
补充：ADR-0002

## 背景

秒杀资格判断、库存扣减、用户去重和命令入队必须在同一段 Redis Lua 中完成；跨服务领域事件则需要独立消费组、长期积压、重试、DLQ 和运维治理。单独使用任一消息系统都会牺牲其中一类边界。

## 决策

- Redis Lua 原子写入 `stream.coupon-orders`，Redis Stream 只承载 Commerce 内部订单命令。
- Stream Worker 使用 consumer group、显式 ACK、pending reclaim、结构化失败结果和 Reservation 补偿。
- 已提交的 MySQL 业务事务同时写 `outbox_event`；Relay 将标准事件信封发布到 RocketMQ。
- RocketMQ 只承载跨服务领域事件，投递语义为 at-least-once。
- 每个消费者使用 `processed_event` Inbox、唯一约束和聚合版本实现 effectively-once business effect。
- 首版使用 Normal Topic；没有固定 message group 和串行 claim/发送/消费时不得宣称 FIFO。

## 结果与回滚

系统同时维护两套消息基础设施，但职责不重叠。`CITYCOPILOT_ROCKETMQ_ENABLED=false` 可关闭 MQ Relay/Consumer，业务同步链路与 Redis Stream 仍可运行；不得在关闭期间删除未发布 Outbox。

