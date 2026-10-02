# Commerce JVM 重启后的暂留到期验收

验收结果及时间戳以 [booking-restart-2026-09-10.json](booking-restart-2026-09-10.json) 为权威记录。执行器为 [run_booking_restart_acceptance.py](../../tools/loadtest/run_booking_restart_acceptance.py)。

本次真实重启和自然到期释放通过；MQ 在扩展只读观察中最终完成入账。首次脚本因 35 秒内未观察到业务消费而退出 1，随后 `--verify-report` 只读续验退出 0。JSON 保留 `initialObservation.passed=false` 及原超时信息，最终 `passed=true` 表示完整事实已核对，不能解释为原 35 秒窗口达标。

## 实验方法

在隔离的 Core / Commerce 测试服务与 MySQL `33319` 测试库中，使用普通用户 `100297`，由测试商家创建独立的餐厅资源与未来时段。资源没有固定套餐，预约使用 `ON_SITE`，不计算不存在的消费总价。

先从实际规则 API 确认暂留时长为 120 秒，再通过 Core HTTP 创建 HELD。读取 MySQL，记录持久化截止时间和占用名额后，核对 `.tools/booking-runtime/commerce.pid` 指向的 Java 进程及完整启动命令。

实验只停止该指定测试 JVM，使用相同的 `.tools/booking-runtime/commerce-app.jar` 及原启动参数重新启动，保留 Outbox、RocketMQ 参数；未使用构建目录中的 JAR。`Start-Process` 使用隐藏窗口，更新 PID 文件。进程启动信息通过临时文件采集，避免 Windows 子 JVM 继承管道导致验收进程等待输出结束。

新 JVM 启动后再次读取 MySQL，要求预约仍为 HELD、名额仍被占用、原截止时间完全相同。随后只用 SELECT 观察状态，等真实截止时间到达，由预约后台任务释放名额。实验未通过 SQL 修改截止时间，也未调用确认、取消等接口来触发成功路径的到期逻辑。

## 通过条件

- 原进程与新进程 PID 不同，复制 JAR 的 SHA-256 在实验前后相同。
- 重启前后 HELD 及原截止时间保持不变；到期后状态为 `HOLD_EXPIRED`，占用从 2 恢复为 0。
- `commerce.booking.held` v0 与 `commerce.booking.hold_expired` v1 均实际发布到 MQ，并进入 Core Inbox 与行为事实；Core 当前状态投影为 `HOLD_EXPIRED` v1。
- 本实验没有方案曝光，因此事件不伪造归因。
- 独立测试资源完成后标为 `UNAVAILABLE`，不继续参与公开预约查询。脚本成功退出码为 0；失败时会尝试取消本次残留预约并清理资源。

`observedExpiryDelayMs` 表示客户端轮询首次观察到释放的时间与截止时间之差，包含轮询间隔；它不是精准的后台任务执行耗时。

## 消息延迟与诊断边界

本次 HELD、HOLD_EXPIRED 从 Outbox 发布到行为组入账分别约 87.3、87.7 秒。到期事件在审计组几乎立即入账，在行为组明显较晚入账；两个组的记录来自同一 MySQL 实例，因此该差异不能用 UTC 与本地时区的显示差异解释。同期 Windows、MySQL 与 Broker 容器 UTC 均落在同一个不足一秒的观测窗口内，采样保存在 JSON 的 `diagnostics.clocks`。

事后消费进度显示八个队列的积压和处理中消息均为 0。旧版 retry-topic 路由查询无结果，直接连接查询返回组不在线，不能据此重建历史 gRPC 客户端状态；Core 当时只有一个 JVM，事后线程快照中消费线程空闲，未取得能解释历史延迟的异常日志。延迟根因未定位，本次证据不声称实时同步或性能达标，也未为此引入重试框架或修改业务状态。

## 复现

本脚本明确限定已准备好的回环测试环境、测试账号和测试数据库。先核对当前 Commerce PID，确认该隔离服务允许短暂重启，再执行；PID 必须来自当次核对，不能重复使用历史值。

```powershell
python tools/loadtest/run_booking_restart_acceptance.py `
  --test-only `
  --expected-pid <本次核对的 Commerce PID> `
  --output target/booking-restart-acceptance.json
```

默认使用本机 `pwsh` 与 MySQL 8 客户端；路径不同时通过 `--powershell`、`--mysql-client` 指定。测试账号可经隔离 Redis 中的验证码登录，或者使用 `CITYCOPILOT_BOOKING_RESTART_TOKEN`、`CITYCOPILOT_BOOKING_RESTART_MERCHANT_TOKEN` 环境变量；令牌不写入证据。

MQ 只读观察窗口通过 `--mq-observation-seconds` 配置，默认 180 秒。它是实验等待上限，不是服务性能目标。若真实重启、到期释放与清理均已通过，仅消息观察超时，可以对原报告续验；该模式不重启进程，也不写业务状态：

```powershell
python tools/loadtest/run_booking_restart_acceptance.py `
  --test-only `
  --verify-report target/booking-restart-acceptance.json `
  --output target/booking-restart-acceptance.json `
  --mq-observation-seconds 180
```

本次原始运行的结果和结束时间保留在 `initialObservation`；延迟消息的实际发布时间、处理时间及差值保留在 `messageDelivery`，续验时间保留在 `extendedObservation`。

这是一次单机、单 JVM 的真实重启恢复验收，不代表生产可用性、集群故障切换或数据库灾备能力。服务地址、进程和时间信息仅描述本次实验快照。
