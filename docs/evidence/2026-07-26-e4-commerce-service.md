# E4 Commerce Service 验收证据

日期：2026-07-26  
环境：Windows + Docker Desktop，单机 Compose；该结果是集成验证，不是多机 HA 证明。

## 服务与数据所有权

- 新增独立 `commerce-service`（8083）和 `citycopilot_commerce` MySQL（3309）。
- Commerce 独占 voucher、seckill voucher、order、reservation、outbox、inbox、DLQ 和 shop projection。
- Core 的兼容 API 通过 `CommercePortClient` 转发，Commerce 关闭时返回稳定的 `COMMERCE_UNAVAILABLE`。
- Commerce 使用独立 MySQL 用户和 Redis ACL 用户；ACL 只授予 `commerce:*` key pattern。
- Compose 中 Core、Commerce、Agent、MCP、MySQL、Redis、RocketMQ、Frontend 均恢复为 healthy。

## 真实业务链路

- 通过旧 Core 兼容入口创建的新订单只写入 Commerce。
- 迁移后 Commerce 共 13 个订单，其中 10 个为旧订单回填，3 个为新服务验收订单。
- 已实测支付、核销、取消、退款；取消和退款恢复 DB 与 Redis 库存。
- 优惠券通过 Core 兼容入口完成新增和修改，响应字段保持 camelCase。
- Outbox 最终均为 `PUBLISHED`。

## 迁移核对

`verify-commerce-cutover.ps1` 的最终结果：

- 旧/新迁移优惠券：2 / 2；
- 旧/新迁移订单：10 / 10；
- 优惠券 PK 范围：`1..100`，订单 PK 范围：`76638984112964585..76638984112964594`；
- 面值汇总：4751，实际价值汇总：5100；
- voucher hash：5007662655，order hash：19083615391，两侧一致；
- 行数、范围、金额和 hash 总门禁 `rowCountGate=true`。

`commerce-cutover-gate.ps1 -Action Preflight`：

- unpublished Outbox = 0；
- in-flight Reservation = 0；
- unreplayed DLQ = 0；
- legacy Redis pending = 0；
- `safe=true`。

## Commerce → MQ → Agent 自愈

向 `commerce:seckill:stock:100` 注入库存偏差后：

1. Commerce 连续两轮对账产生 `commerce.stock.discrepancy-detected`；
2. Commerce Outbox 经 RocketMQ 发布，状态 `PUBLISHED`；
3. Agent 创建 Case `2a21c405-deb2-44bf-836a-ce72530740e9`；
4. Agent 采集证据，`ownerService=commerce-service`，无 LLM key 时进入 `MANUAL_REVIEW`；
5. 人工计划绑定 evidence hash，审批后仅执行一次 repair；
6. 独立 verifier 重新取证，最终 Case 为 `SUCCEEDED`，execution = 1，verification = 1。

这证明“称为 Agent”对应的是持久化 Case、证据、计划、审批、执行和复核闭环，而不是简单定时任务。

## 自动化测试

- Commerce unit：4 passed；
- Core unit：30 run，27 passed，3 个需完整 Spring 环境的测试按既有条件 skipped；
- 迁移与切换门禁通过；
- 仍需在 E6 汇总完整 Maven、前端、eval、Compose、性能与故障恢复结果。
