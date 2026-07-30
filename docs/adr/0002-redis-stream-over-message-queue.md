# ADR-0002：秒杀异步链路继续使用 Redis Stream

状态：已由 ADR-0005 与 ADR-0006 取代
日期：2026-07-11

> 本文保留旧 Core 秒杀链路的历史决策。当前订单命令使用 Commerce 拥有的 `commerce:stream:orders`，失败记录进入 `commerce_dead_letter`；Core 不再启动旧 Stream worker。

## 背景

秒杀入口已依赖 Redis 做库存预扣、一人一单和 Lua 原子校验。引入 RocketMQ 能提供成熟的消息治理，但会增加一套部署、监控和故障处理体系。

## 决策

继续使用 Redis Stream，并补齐业务级可靠性能力：

- 消费失败按记录计数，达到上限后转入 `stream.coupon-orders.dlq`；
- DLQ 和库存偏差均暴露 Micrometer 指标；
- 定时对账 Redis 与数据库库存，支持显式开启自愈；
- 入口接受 `Idempotency-Key`，重复请求返回原订单 ID。

## 结果

部署依赖保持精简，Redis 故障仍是交易链路的重要故障域。若未来需要跨服务广播、长时间堆积、独立消费组治理或更强的消息可追溯性，再评估专用 MQ。

## 面试回答

“是否换 MQ 取决于业务约束，不取决于简历关键词。当前 Redis 已是秒杀原子校验的基础设施，我先补齐 DLQ、重试、幂等和对账；当堆积、广播或独立故障域成为瓶颈时再迁移。”
