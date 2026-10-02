# 消息队列：在高峰与故障中比较业务结果

在仓库根目录使用 JDK 17 或更高版本运行，无需 Maven、Docker 或额外依赖：

```powershell
java "-Dfile.encoding=UTF-8" tools/mq-demo/MqUsefulnessDemo.java
```

程序运行两组对照，输出每笔业务的状态变化；所有检查通过后才打印 `PASS`，失败以非零状态退出。参数以 [MqUsefulnessDemo.java](MqUsefulnessDemo.java) 为准，终端和回放展示实际生效的值。

## 餐券高峰：排队保留工作，避免在等待连接时放弃

同一批请求同时进入两套相互独立、容量相同的模拟订单处理流程：

- **同步方案**：请求等待数据库连接有时限。超过时限即放弃，本次不创建订单；已经获得连接的请求正常完成。
- **排队方案**：任务已受理并进入有限容量的队列，消费者按同样的并发上限与单笔耗时逐步完成。队满时拒绝额外受理。

重点观察最终完成数、超时数和积压变化。消费者与同步请求从同一个发令信号开始工作；排队方案没有额外增加处理能力，也没有把“已受理”记成“订单完成”。

**结果依赖这里明确设置的等待时限。** 这是等待连接超时，尚未执行数据库写入；不能推广为真实 HTTP 超时一定没有落单。延长同步等待时间也可能完成全部订单；流量持续超过处理能力时，队列也会满。线程池和限流同样可以限制并发，不能把控制并发说成 MQ 独有能力。

只模拟通过准入后的最终订单工作。实际项目由 [Lua](../../backend/commerce-service/src/main/resources/lua/commerce_seckill.lua) 检查、预扣库存并写 Redis Stream，[CommerceOrderStreamWorker](../../backend/commerce-service/src/main/java/com/citycopilot/commerce/CommerceOrderStreamWorker.java) 消费后调用订单服务提交最终订单事务。当前请求路径也会写 MySQL 准入账本。

## 核销统计：故障后补齐，消费者进程中断后还能继续

两组主流程均已完成核销，对照的是后续统计：

1. **直接调用且不保存重试任务**：统计存储故障后记录失败。服务恢复本身不会补回漏掉的统计。
2. **保存待办再消费**：消息实际写入文件。统计存储故障时消费者返回失败，待办保留。
3. 恢复存储，启动一个消费者 JVM：写入第一条统计事实后，使用 `Runtime.halt(23)` 中断它，故意不确认消息。
4. 启动全新 JVM：从磁盘读取待办和统计事实，跳过已经处理的事件，补齐剩余统计；确认后删除对应待办。

终端会显示实际子进程 PID 和退出码。只有演示自己启动的消费者被中断。统计事实的文件名代表事件唯一键，统计值是唯一事实数，体现幂等。

**可靠性来自待办持久保存、失败重试、确认和幂等的配合。** 无 MQ 的方案如果也实现持久待办与重试，同样可以补齐。此实验没有模拟消费者自动重启调度，也没有验证机器断电、多写者事务或生产者写业务与发布事件之间的原子性。

真实实现入口：

- [BookingService](../../backend/commerce-service/src/main/java/com/citycopilot/commerce/booking/BookingService.java)：预约业务与 Outbox 事件同事务提交。
- [CommerceOutboxRelay](../../backend/commerce-service/src/main/java/com/citycopilot/commerce/CommerceOutboxRelay.java)：向 RocketMQ 发布事件，失败可重试。
- [行为消费者](../../backend/core/src/main/java/com/citycopilot/core/event/RocketMqBookingBehaviorConsumer.java)：处理失败交由 RocketMQ 重投。
- [BookingBehaviorProjector](../../backend/core/src/main/java/com/citycopilot/core/booking/BookingBehaviorProjector.java)：Inbox 登记与行为写入同一事务，避免重复累计。

## 运行记录与回放

默认输出到本目录下被 Git 忽略的 `results/`，也可追加 `--report 输出目录`：

- `trace.json`：本次真实线程/子进程实验的逐事件状态与相对时间；重新运行会更新。
- `mq-outcomes.html`：由 [回放模板](demo-view.html) 和本次轨迹生成的 Codex 内嵌回放片段；播放、逐事件查看和拖动只回放记录，不重新执行实验。
- `recovery-*/`：每次新建的独立证据目录，保留核销记录、未确认消息目录和统计事实目录；程序不清理历史运行目录。

回放为便于观察而逐事件放慢，界面显示的时间仍是实际记录。切换两个场景即可查看各自对照，初始显示最终状态。

这是线程、文件和 JVM 进程实验；数据库处理时间与服务故障是显式注入条件，未连接真实 Redis、RocketMQ 或项目 MySQL。真实服务验收入口见[预约验收](../loadtest/run_booking_acceptance.ps1)和[秒杀压测](../loadtest/run_seckill_baseline.py)。
