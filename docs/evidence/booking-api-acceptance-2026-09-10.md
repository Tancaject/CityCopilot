# 预约 Core HTTP 与消息闭环验收

本次验收通过。机器可读结果、逐请求延迟及事件 ID 的权威记录为 [booking-api-acceptance-2026-09-10.json](booking-api-acceptance-2026-09-10.json)。执行器为 [run_booking_api_acceptance.py](../../tools/loadtest/run_booking_api_acceptance.py)。

## 环境与范围

2026-09-10 在隔离测试环境执行：Core `127.0.0.1:8081`、Commerce `127.0.0.1:8083`，MySQL `127.0.0.1:33319` 上的 `citycopilot_booking_core_test` / `citycopilot_booking_ui_test`，真实 RocketMQ 消费组 `booking-test-ui-v1`。该记录是本次验收快照，不代表服务当前运行状态。

使用普通测试用户 `100298`、餐厅 `90002` 的资源 `3` / 时段 `7`；业务写操作只涉及该用户在该时段的新预约。另一普通用户 `100000` 只用于越权读取探测。手机号验证码从隔离 Redis `61380` 读取，登录令牌仅保留在进程内，未写入证据。

## 验收结果

- 匿名请求伪造身份和管理员头被拒绝；已登录用户的身份不能被请求头替换。其他用户不能读取预约或幂等命令，普通用户不能执行商家取消接口。
- 一次性回环代理先完整读取 Core 的 `201 HELD` 响应，再断开下游连接，客户端实际得到 `RemoteDisconnected`。随后命令查询与同键重试返回同一预约，名额只扣减一次。该预约确认、取消成功，时段占用恢复验收前值。
- 计划查询使用 30 次请求、5 并发，覆盖总预算、人均预算和无法满足预算的情形。所有 HTTP 与响应状态检查通过，返回的全部方案满足人数、资源类型、可用名额、时段、报价归属和优惠后预算约束；计划查询未创建 HOLD。延迟及分位数见 JSON 的 `plans`，分位数沿用现有负载工具的 `percentile`。
- 客户端自报 `CONFIRMED` 被明确拒绝，未形成客户端履约事实。
- 测试预约的三个 Outbox 事件与 Core Inbox、业务事实的事件 ID 集合完全一致。真实零版本 `HELD` 晚于确认到达，被 Inbox 标记为 `STALE`，仍保留了 HELD 事实；当前状态保持版本 2 的 `CANCELLED`。
- 三个业务事实均关联到预先由 Core 签发、绑定本人及真实返回门店的同一曝光；派生特征保留一次确认和一次取消。该曝光表示服务器返回候选，不声称用户一定看过。

## 复现

为隔离测试服务准备普通测试账号、未来餐次和已发布按人套餐。`--fixture` 为 JSON 数组，每项包含公开查询接口返回的 `resource`、`slots`、`offer` 对象；执行器选择指定资源的首个未来时段，并重新读取实时容量和套餐价格。过期的本次时段不能直接用于下一次运行。

```powershell
python tools/loadtest/run_booking_api_acceptance.py `
  --test-only `
  --environment "Isolated loopback Core and Commerce test databases" `
  --fixture .tools/booking-runtime/fixtures.json `
  --resource-id 3 `
  --output target/booking-api-acceptance.json
```

脚本仅允许回环 HTTP 地址，默认从测试 Redis 读取验证码；已有测试令牌可通过 `CITYCOPILOT_BOOKING_API_TOKEN` 和 `CITYCOPILOT_BOOKING_API_OTHER_TOKEN` 环境变量传入。它在 `finally` 中按本次幂等键找回并取消残留预约，输出 `cleanup`。HTTP 失败或容量未恢复时退出码为 1。

消息核验在 HTTP 执行后独立通过只读 SQL 完成。使用结果中的 `lostResponse.bookingId`，分别读取 Commerce `commerce_outbox_event.aggregate_id`、Core `processed_event.aggregate_id`（限定消费组）及 `user_behavior_event.booking_id`；双向比较事件 ID 集合，并检查每个事件的版本、状态和归因。再核对 `booking_behavior_projection`、`search_impression`、`user_preference_feature`。结果保存在 JSON 的 `messageDelivery`，没有以 Mock 消费者替代真实 MQ。

## 证据边界

这是单机隔离环境、单餐厅的小规模 API 验收，未测得生产吞吐或长时间稳定性，也不代表真实用户点击率。报价及计划留下正常审计记录；所有本次创建的预约均已取消并释放容量。运动场馆并发库存验收、迁移验收和推荐离线评估分别使用各自证据，本报告不将其混为本次 HTTP 测试结果。
