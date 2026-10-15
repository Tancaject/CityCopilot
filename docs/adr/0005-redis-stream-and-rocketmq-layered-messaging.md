# ADR-0005：Redis Stream 与 RocketMQ 分层消息

状态：已被 [ADR-0012](0012-kafka-replaces-rocketmq.md) 替代（2026-10-15）。Redis Stream 与 MQ 分层的决策不变，跨服务事件改由 Kafka 承载。  
日期：2026-07-26  
补充：ADR-0002

## 背景

秒杀资格判断、库存扣减、用户去重和命令入队必须在同一段 Redis Lua 中完成；跨服务领域事件则需要独立消费组、长期积压、重试、DLQ 和运维治理。单独使用任一消息系统都会牺牲其中一类边界。

## 决策

- Redis Lua 原子写入 `commerce:stream:orders`，Redis Stream 只承载 Commerce 内部订单命令。
- Commerce Stream Worker 使用 consumer group 和显式 ACK；终态失败先补偿 Redis 资格/库存并写入 `commerce_dead_letter`，再 ACK。
- 已提交的 MySQL 业务事务同时写 `commerce_outbox_event`；Commerce Relay 将标准事件信封发布到 RocketMQ。
- RocketMQ 只承载跨服务领域事件，投递语义为 at-least-once。
- 每个消费者使用 `processed_event` Inbox、唯一约束和聚合版本实现 effectively-once business effect。
- 首版使用 Normal Topic；没有固定 message group 和串行 claim/发送/消费时不得宣称 FIFO。

公共消息字段由无框架依赖的 [EventEnvelope](../../backend/contracts/src/main/java/com/citycopilot/contract/event/EventEnvelope.java) 定义，订单、预约和运维事件生产者共用这一契约。`schemaVersion` 描述载荷结构，`aggregateVersion` 描述业务版本，两者分别演进。`occurredAt` 使用 ISO-8601 文本，避免序列化配置改变时间的 JSON 类型。Relay 重发 Outbox 中保存的原始正文，保留 `eventId`、版本和内容；不能重新构造信封来“重放”同一事件。消费者仍按收到的原始字节计算内容哈希，继续识别同一身份对应不同载荷的冲突。

## 结果与回滚

系统同时维护两套消息基础设施，但职责不重叠。`CITYCOPILOT_ROCKETMQ_ENABLED=false` 可关闭 MQ Relay/Consumer，业务同步链路与 Redis Stream 仍可运行；不得在关闭期间删除未发布 Outbox。
