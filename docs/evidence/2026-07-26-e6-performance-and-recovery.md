# E6 性能、故障恢复与交付证据

验收日期：2026-10-06。范围是本机 Docker 的独立 `citycopilot-redesign-load` 项目：两个 Core、两个 Commerce、两个 MySQL、Redis、RocketMQ 和 nginx。数据库及数据卷与日常开发环境隔离；不涉及多机高可用或外部支付机构。

本文是[后端重规划](../architecture/backend-redesign-2026-10.md)实测数字的唯一来源。最终完整运行 `redesign-dc1f8d2099ab` 的 `passed=true`。执行命令见[隔离多实例验证](../docker-compose.md#隔离多实例验证)。负载产物记录请求规模、镜像 SHA、Docker 资源、逐请求耗时和 CPU 采样。

## 正确性验证

| 验证 | 实际范围 |
|---|---|
| `SeckillLifecycleIT` | 16/16 通过，无跳过；真实 MySQL + Redis：准入、Stream 恢复、不超卖、取消重买、旧恢复者不能重新占用取消资格、关单/支付竞态、回调验签/幂等、迟到回调退款、支付修复证据、券缓存及 V10→V13 迁移 |
| `BookingLifecycleIT` | 20/20 通过，无跳过；独立 UUID 数据库，覆盖既有预约状态与锁序场景 |
| `CoreCacheAndRateLimitIT` | 3/3 通过，无跳过；真实 MySQL/Redis，验证两份本地缓存失效、旧加载不能回填、共享令牌桶及 Redis 错误失败关闭 |
| 编译 | Core、Commerce、Frontend 镜像构建通过；前端 `npm run build` 通过 |

IT 复现时分别设置源码中声明的 `CITYCOPILOT_SECKILL_TEST_*`、`CITYCOPILOT_BOOKING_TEST_*`、`CITYCOPILOT_MIGRATION_TEST_*` 环境变量，再运行：

```powershell
mvn -pl backend/commerce-service -am test '-Dtest=SeckillLifecycleIT,BookingLifecycleIT' '-Dsurefire.failIfNoSpecifiedTests=false'
mvn -pl backend/core -am test '-Dtest=CoreCacheAndRateLimitIT' '-Dsurefire.failIfNoSpecifiedTests=false'
```

秒杀/预约数据库必须为专用 `_test` 库，秒杀 IT 会清空显式指定的 Redis DB（编号至少为 2）。迁移及 Core 缓存 IT 需要能创建、删除 UUID 数据库的本地测试账号。没有这些环境变量时测试会跳过，不能作为通过证据。本轮没有以全仓单元测试替代这些真实系统验证。

## 三组负载

Docker 可见 32 个逻辑 CPU、约 15.51 GiB 内存，共享给本轮容器。秒杀和读请求通过只绑定回环地址的 Core nginx 入口进入，用户限流仍生效；按 IP 的限流在前台入口另测。预约通过 Commerce 内部入口执行。每组为一次短压，不是容量上限或持续稳定性测试。

| 场景 | 请求规模 | 实测吞吐 | P99 | 业务结果 |
|---|---|---|---|---|
| 秒杀准入 | 1000 用户、100 并发、库存 1000 | 386.93 请求/秒 | 537.49 ms | 1000 个 202；最终订单/不同用户/账本均为 1000，MySQL/Redis 库存均为 0 |
| 店铺详情与券列表，80% 热点 | 5000 请求、100 并发 | 2257.84 请求/秒 | 80.05 ms | 5000 个 200 |
| 单场地时段争抢 | 1000 用户、100 并发、1001 个 HTTP 请求 | 565.69 HTTP 请求/秒 | 暂留请求 479.73 ms | 1 个确认、999 个名额已满；无超订、占用偏差、缺版本事件或遗留暂留 |

秒杀吞吐计量的是 202 准入响应，随后单独等待异步落单和库存对账；大量请求撞上售罄的场景见下文[售罄洪峰对照](#售罄洪峰对照)。预约拒绝是容量为 1 时的预期业务结果，不能当成 1000 个预约成功。**本轮没有达到秒杀 1 万 QPS、成功 P99 小于 100 ms 或混合读 5000 QPS 的设计目标。**

各阶段 Docker CPU 采样如下（均值/峰值；100% 表示约一个逻辑 CPU）：

| 阶段 | Redis | Commerce MySQL | Core MySQL | 每容器样本数 |
|---|---|---|---|---|
| 秒杀，含等待落单与对账 | 1.83% / 10.19% | 16.83% / 56.24% | 4.45% / 12.65% | 29 |
| 热点混合读 | 5.79% / 5.79% | 10.08% / 10.08% | 9.68% / 9.68% | 1 |
| 预约 | 2.35% / 2.35% | 39.14% / 39.14% | 5.17% / 5.17% | 1 |

短阶段的 CPU 样本稀疏，不能据此确定峰值瓶颈。Redis 没有出现本轮可观测的单核打满证据，因此没有触发库存分桶。

原始汇总：[负载结果](2026-10-06-redesign-load.json)。逐请求数据与 CPU 采样在 `tools/loadtest/results/redesign/load-final.samples.jsonl`、`load-final.cpu.jsonl`，执行以下命令可重新生成：

```powershell
python tools/loadtest/run_redesign_load.py --users 1000 --concurrency 100 --read-requests 5000 --faults --output tools/loadtest/results/redesign/load-final.json
```

本节只使用最终完整运行。定位阶段的[首次负载记录](2026-10-06-redesign-load-attempt1.json)与[首次独立故障记录](2026-10-06-redesign-faults-attempt1.json)保留原始失败，不能代替最终通过证据。

## 缓存调用量对照

- 热店铺预热后，5000 次 HTTP 读取只产生目标缓存键的 2 次 Redis GET；等量直接 Redis 读取参照为 5000 次，降幅 **99.96%**。按同一 HTTP 时间窗口归一为约 1.16 与 2900.23 GET/秒；不是 Redis 吞吐容量比较。
- 售罄正常缓存下，64 次请求的目标库存 Lua 调用为 0；每请求前广播失效的对照为 64 次，即每请求 0 对 1。只比较目标键的 Lua 调用，不代表全系统 Redis 流量减少 100%；MySQL 侧的效果见下文[售罄洪峰对照](#售罄洪峰对照)。
- MONITOR 仅在内存累计本轮 key，不保存命令原文。后台同 key 读取和 Lua 内部 GET 保留在统计中，控制广播与屏障读取排除；原始计数及限制见负载 JSON。

## 售罄洪峰对照

2026-10-07，库存 100 的秒杀券被 5000 个不同用户各请求一次，经 Core 入口，100 并发，2 Core + 2 Commerce。两组只差 Commerce 镜像：「改动前」在分配订单号并写入一行 REJECTED 账本之后才检查售罄标记；「改动后」先按幂等键查已有账本，命中售罄直接返回 409，不写账本、不分配订单号。每组在同一批容器上连续跑 3 轮，第 1 轮含 JVM 预热。

| 组别 | 轮次 | 吞吐（请求/秒） | P50 | P99 | 新增账本行 | MySQL INSERT（扣除空闲） | MySQL SELECT | MySQL 总查询（扣除空闲） |
|---|---|---|---|---|---|---|---|---|
| 改动前 | 1 | 720.85 | 115.48 ms | 392.98 ms | 5000 | 4936 | 10326 | 13955 |
| 改动前 | 2 | 744.64 | 110.40 ms | 385.67 ms | 5000 | 4869 | 10432 | 13980 |
| 改动前 | 3 | 760.55 | 105.10 ms | 403.68 ms | 5000 | 4895 | 10448 | 14093 |
| 改动后 | 1 | 1196.64 | 69.08 ms | 358.54 ms | 231 | 217 | 5634 | 5012 |
| 改动后 | 2 | 1453.47 | 62.76 ms | 206.73 ms | 185 | 173 | 5628 | 5191 |
| 改动后 | 3 | 1512.16 | 59.94 ms | 193.22 ms | 218 | 320 | 5723 | 5750 |

六轮都是 100 个 202、4900 个 `SECKILL_STOCK_NOT_ENOUGH`，最终订单、不同用户和 ORDER_CREATED 账本均为 100，MySQL 与 Redis 库存均为 0。

- 以预热后的第 2、3 轮比较：吞吐约 750 → 1480 请求/秒，P99 约 395 → 200 ms；每轮新增账本从 5000 行降到 185–218 行，扣除空闲后的 INSERT 降约 95%，总查询降约 60%。
- 改动后仍有约百余行账本：售罄标记按实例缓存 1 秒，每次过期后有一批在途请求重新走 Lua，被拒后按原流程持久化。剩余的 SELECT 主要是每个请求一次的幂等键查找，用于保证同键已接受的请求返回原订单。
- MySQL 计数是 `SHOW GLOBAL STATUS` 增量，包含 Outbox、关单、对账等后台轮询；表中「扣除空闲」减去了压测结束后同等时长空闲窗口的增量。压测客户端是同机单个 Python 进程 100 线程，吞吐可能受客户端限制，不代表生产容量。

原始汇总：[售罄洪峰结果](2026-10-07-sold-out-storm.json)。「改动前」镜像由临时恢复旧逻辑构建，源码已还原；复现「改动后」：

```powershell
python tools/loadtest/run_redesign_load.py --sold-out-storm --storm-stock 100 --users 5000 --concurrency 100 --output tools/loadtest/results/storm/after.json
```

## 双实例缓存失效

通过商家 HTTP 创建并修改店铺，预热两台 Core 后更新名称。写成功到两台 Core 分别读到新值为 **236.956 ms、227.678 ms**，包含 Docker exec 和 HTTP 传输开销，均低于 1 秒；对应 Outbox 版本 2、状态 `PROCESSED`、attempts=1。没有直接 SQL 修改店铺。原始结果见[失效证据](2026-10-06-redesign-shop-invalidation.json)。

## 浏览器

在 `http://127.0.0.1:18090` 使用真实登录、HTTP 和异步 worker：

- 抢购显示“处理中”，随后显示“已抢到”；受理后尚未落库时查询返回 `processing`，不再误报订单不存在。
- 支付先显示等待渠道回调并禁用按钮，回调后显示已支付。支付结果来自再次查询订单。
- 同一账号连续请求触发真实 `429`，接口返回 `Retry-After: 1`；DOM 中按钮显示“请稍后重试”并禁用，窗口结束后恢复。

截图：[订单生成](2026-10-06-redesign-order-created.jpg)、[支付确认](2026-10-06-redesign-payment.jpg)。429 的证据是实时响应与 DOM 状态检查，截图未捕获该瞬间。复现时创建新券，按“登录→店铺抢购→订单支付”操作；限流复现需在同一账号的退避窗口内观察按钮。

## 多实例与故障

| 场景 | 实测结果 |
|---|---|
| nginx 分发 | 实际健康请求到达两个不同的 Core 上游；两个 Commerce 同时运行 |
| 对账任务持有者中断 | 32.062 秒后由另一实例接管，fencing token 从 1 增至 2；原实例恢复健康 |
| 双实例超时关单 | 1000 笔订单全部 `PAY_TIMEOUT` 关闭；恢复记录恰为 1000 条且全部 `RESTORED`，MySQL/Redis 库存均回到 1000 |
| Stream 消费者中断 | 真实 XPENDING 确认的持有者被终止；946 个请求直接受理，54 个 502 用原幂等键重放；最终订单/不同用户/账本均为 1000、库存均为 0、PEL 清空 |
| Outbox 发布者中断 | 暂停 Proxy，确认目标事件已被领取后终止发布者；恢复后事件到达 Core Inbox。再次发送同一事件，duplicate 指标增加 1，原 Inbox 处理时间不变，业务订单仍只有 1 笔 |
| Redis 重启与库存键丢失 | 停机请求返回 504，没有新增 `ACCEPTED`/`ORDER_CREATED` 账本；恢复 ACL 和连接后删除本轮库存键，通过新鲜证据哈希修复，再次准入成功，最终订单为 1、MySQL/Redis 剩余库存均为 1 |
| nginx IP 限流 | 200 个前台入口请求中 158 个被限流，均带 `Retry-After: 1`；其余 42 个未登录请求返回 401，没有进入交易写入 |

租约与路由原始结果见[拓扑报告](2026-10-06-redesign-topology.json)。中断产生的 HTTP 错误没有计为“零错误”；准入结果未知时使用同一幂等键恢复。

## Broker 运行预算

Broker 的 POP 检查点写入曾因直接内存不足阻断消费。运行实例使用 80 个拉取线程，每个编码缓冲约 4.06 MiB，超过原直接内存预算；[RocketMQ 5.5.0 的线程默认值](https://github.com/apache/rocketmq/blob/rocketmq-all-5.5.0/common/src/main/java/org/apache/rocketmq/common/BrokerConfig.java)按可见 CPU 数计算。现有配置已调整为与这些线程匹配的预算，没有修改业务重试、消费超时或验收断言。

`jcmd VM.flags` 确认实际堆上限 512 MiB、直接内存上限 768 MiB，容器上限 2 GiB。重启后原有 2292 条积压清空，再执行本次完整负载与故障验收；验收结束时新增直接内存错误为 0、消费积压与在途消息为 0，相关服务全部健康。运行参数和日志检查见[Broker 恢复证据](2026-10-06-redesign-broker-recovery.json)。

## 验证边界

- 超时关单通过修改本轮测试订单的截止时间触发，没有等待完整支付窗口。
- 支付使用持久化模拟渠道；每日对账逻辑和状态修复由 IT 执行，没有等待真实每日调度时间点，也未新增支付管理页面。
- 历史已取消订单的 Redis 买家集合通过[升级修复流程](../database-migrations.md#commerce-v11取消后重新购买)处理，不会在迁移 SQL 中盲删 Redis 数据。
- P2 的库存分桶、Feed 推拉、分库分表、全链路追踪没有预先实现；是否进入下一步应使用对应瓶颈的测量证据。
