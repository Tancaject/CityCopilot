# 预约负载与消息故障验收

[预约实施计划](../architecture/booking-pricing-implementation-plan.md)定义业务边界和验收范围；本文件说明如何重放 Commerce HTTP 压测。脚本使用真实 MySQL、现有 Core 商家归属接口及真实 Kafka，覆盖免费预约、线下结算。报价与优惠券事务由 `BookingLifecycleIT` 单独验证。

## 准备

- 使用专用本地 MySQL 数据库，名称必须以 `_load_test` 结尾。按 [数据库迁移运行手册](../database-migrations.md#专用测试数据库)使用 Commerce Flyway 任务初始化，负载脚本的连接主机只能是 loopback。每次实验使用没有预约数据的库；脚本不会删除已有数据。
- 将已构建的 Commerce 可执行 JAR 复制到独立运行目录后启动，避免后续 Maven 重新打包替换正在使用的 JAR。数据库指向该库，Place 地址指向实际 Core。使用 Core 中存在的商家及其所属 shop，CSV 第一列提供不同的真实用户 ID，可带 `userId` 表头。
- 保持预约新入口开启、Outbox Relay 开启。使用 [`booking-mq-compose.yaml`](../../tools/loadtest/booking-mq-compose.yaml) 启动专用消息实例，并用 `--spring.kafka.bootstrap-servers=127.0.0.1:18089` 让该 Commerce 连接其中的单节点 Kafka。其他业务实例继续使用自己的消息配置。
- Python 只使用标准库；MySQL CLI 必须可用。服务令牌由环境变量传入，数据库口令也通过环境变量传给子进程，不写入报告。

```powershell
docker compose -f tools/loadtest/booking-mq-compose.yaml up -d --wait

python tools/loadtest/run_booking_baseline.py `
  --base-url http://127.0.0.1:18093/commerce/v1 `
  --users-file tools/loadtest/results/booking-users.csv `
  --shop-id <真实商家店铺ID> --merchant-id <真实商家用户ID> `
  --users 100 --concurrency 20 --require-outbox-drain `
  --mysql-exe 'C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe' `
  --mysql-port 33319 --database citycopilot_booking_load_test `
  --output tools/loadtest/results/booking/summary.json
```

`CITYCOPILOT_INTERNAL_COMMERCE_TOKEN` 为默认服务令牌变量名，`CITYCOPILOT_BOOKING_LOAD_DB_PASSWORD` 为默认数据库口令变量名。参数与默认值以脚本 `--help` 为准。初次创建资源后，脚本核对资源确实落在指定验证库，匹配失败会在负载开始前停止。

## 负载与结果

三个场景依次运行，每名用户最多产生三个未来预约：

| 场景 | 请求 | 业务断言 |
| --- | --- | --- |
| hot-court | 所有用户争抢同一具体场地、同一小时 | 恰好一个 CONFIRMED，其余只能因为剩余容量不足被拒绝 |
| dining | 同一餐次，每单 1～8 人，餐次容量为用户数的两倍 | 按人数分配；不能因统计订单数而超售，也不能错误拒绝仍容纳得下的人数 |
| nonhot | 每个用户各用一个具体场地 | 所有 HOLD 和确认成功，用于观察没有容量竞争时的路径 |

业务请求为 HOLD 后立即确认，使用不同幂等键。脚本不会自动重试数据库或网络错误，也不使用平台管理员头跳过商家归属。容量不足计作预期业务冲突，与非预期失败分开统计。

报告包含每种场景的工作流吞吐、HTTP 请求吞吐，以及 HOLD、确认、完整工作流的平均值、p50、p95、p99 和最大延迟。`.samples.jsonl` 保留逐请求结果，不包含核销码或服务令牌。`.before.prometheus` 与 `.after.prometheus` 保留 JVM、进程、系统 CPU 和 Outbox 指标。负载发生在单机 Commerce 端口，结果不能解释为 Core 浏览器端到端性能、多机扩展能力或线上承载量。

直接查询 MySQL 检查占用未超容量、占用等于有效预约 units 总和、场馆 units=1、餐厅 units=partySize、每个预约当前版本存在 Outbox 事件、没有遗留 HELD。任一不变量或业务断言失败时进程退出码为 1。

## MQ 中断与恢复

在同一命令中添加 `--mq-outage`。此模式只操作 `citycopilot-booking-load` 项目：

1. 停止该项目的 broker 和 proxy，然后执行相同业务负载。
2. 额外用商家身份创建一个不确认的 HOLD，等待接口返回的真实 `holdExpiresAt`，检查 MQ 停机时它仍被后台任务转为 HOLD_EXPIRED 并释放容量。商家 ID 不能同时出现在负载用户 CSV 中。等待真实 Relay 把至少一条事件记为 FAILED，记录失败信息和故障期间数据库不变量。
3. 在 `finally` 中恢复 broker 和 proxy，等待 Outbox 全部 PUBLISHED，断言至少一条事件有过重试。
4. 再核对业务不变量与前后指标，将故障阶段和恢复阶段写入 `mqOutage`。

该模式证明消息服务中断期间业务提交与 Outbox 续发。消费者重复投递及版本乱序的业务效果应由对应 Inbox 验收证明，不能仅凭发送端的 PUBLISHED 标记宣称下游已处理。若恢复等待不足，调整 `--drain-timeout` 后用新的空库重放，并检查真实失败原因；不要直接修改数据库状态来制造通过。

停止测试实例使用同一个 Compose 文件与项目名，不要对主项目运行 `down`。
