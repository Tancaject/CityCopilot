# 服务与持久化队列告警

[Prometheus 配置](../../deploy/prometheus.yml)加载[告警规则](../../deploy/citycopilot-alerts.yml)。阈值与持续时间只在规则文件维护，均为示例，尚不是实测 SLO。仓库提供规则与排查步骤，没有部署 Prometheus、配置 Alertmanager 接收人或发送通知；上线时还需明确值班负责人和升级路径。

启用时将配置和规则文件放在同一目录，并从监控进程所在网络核对实际目标地址。示例中的 `host.docker.internal` 指 Docker 容器访问宿主机；它不表示所有部署都使用这个地址。抓取标签 `projection_role="true"`、`outbox_relay_role="true"` 表示该目标**应当**运行相应角色，不会开启应用角色。独立部署时把标签移到实际 Projector/Relay 实例，API-only 实例不应带这些标签。

Core 指标只在 `citycopilot.projection.roles.projector-enabled=true` 时注册；Commerce Outbox 指标只在 `citycopilot.commerce.outbox-relay-enabled=true` 时注册。生产 profile 默认关闭这两个角色。Commerce 的 `kafka-enabled=false` 会停止发布，但仍可能存在待发布事实。订单完成采样在所有 Commerce 实例运行，不依赖消费者或 Relay 开关，因此 API-only 实例也应暴露订单完成指标。

多个实例读取同一张队列表，数量和年龄都不能按实例求和。规则对共享队列取 `max`；不同数据库/租户应分成不同 job 或增加队列身份标签后相应分组。Core 年龄告警只采用仍在成功抓取且采样新鲜的实例；不能把未采样、旧样本或 `NaN` 解释为零积压。

| 告警 | 处理入口 |
|---|---|
| `CityCopilotTargetDown` | [抓取失败](#target-down) |
| `CityCopilotBrokerMetricsDown`、`CityCopilotBrokerConsumerBacklog`、`CityCopilotBrokerConsumerLagAge` | [Broker 消费组积压](#broker-consumer-lag) |
| `CityCopilotCommerceOutboxAge` | [Commerce 事件发布](#commerce-outbox) |
| `CityCopilotShopProjectionAge` | [店铺 GEO 投影](#shop-projection) |
| `CityCopilotOrderCompletionAge`、`CityCopilotAdmissionRepairRequired` | [订单持久化完成](#order-completion) |
| `CityCopilotProjectionSampleStale`、`CityCopilotOutboxMetricUnavailable`、`CityCopilotOrderCompletionSampleStale` | [观测数据不可用](#sample-unavailable) |

<a id="target-down"></a>
## 抓取失败

1. 在 Prometheus `/targets` 找到告警的 `job`、`instance`，读取最近抓取错误；从同一监控网络访问该实例的 `/actuator/prometheus`，核对实际监听端口、进程、路由、TLS/认证和 Actuator 暴露范围。
2. 修复对应进程或抓取配置后，确认该实例 `up` 恢复为 `1`，再检查下列业务队列告警。删除 target 会丢失该目标的 `up` 序列，不是恢复措施。

<a id="commerce-outbox"></a>
## Commerce 事件发布

1. 检查实际 Relay 角色、Kafka 发布开关及日志 `Unable to relay Commerce outbox event`。在 **Commerce 数据库**执行有界只读查询，确认事件状态、重试时间和失败原因：

```sql
SELECT event_id, event_type, status, attempts, next_attempt_at,
       claimed_until, last_error, created_at
FROM commerce_outbox_event
WHERE status IN ('READY','FAILED','CLAIMED')
ORDER BY created_at LIMIT 20;
```

2. 按 `last_error` 修复数据库连接、Kafka 节点、topic、ISR（`NotEnoughReplicas` 表示分区 leader 还在但同步副本少于 `min.insync.replicas`；`Expiring N record(s) ... ms has passed since batch creation` 表示分区 leader 不可达，常见于多个节点同时宕机）或连接配置；保持原 `event_id` 和持久化正文。恢复后 [CommerceOutboxRelay](../../backend/commerce-service/src/main/java/com/citycopilot/commerce/CommerceOutboxRelay.java) 会按退避继续处理 READY/FAILED，并接管过期 CLAIMED；无需手工改状态或重建事件。
3. 确认最老事件进入 PUBLISHED、`citycopilot_commerce_outbox_unpublished` 下降且年龄告警解除。订单命令的 `commerce_dead_letter` 是另一条恢复链路，不能用它的重放接口处理 Outbox 事件。

<a id="shop-projection"></a>
## 店铺 GEO 投影

1. 先处理采样失鲜告警，确认 Projector 角色在运行。检查日志 `Shop projection poll failed` 或 `Shop projection event ... failed`，在 **Core 数据库**查看最老事件：

```sql
SELECT id, event_id, shop_id, aggregate_version, status, attempts,
       next_attempt_at, claimed_until, last_error, created_at
FROM shop_projection_outbox
WHERE status IN ('READY','FAILED','CLAIMED','DEAD')
ORDER BY created_at LIMIT 20;
```

2. 根据失败原因恢复 Redis GEO 或数据库连接；检查是否有重建任务持有 `shop-projection-writer` 租约。不得清除活动租约、降低版本或通过删除 Outbox 行消除告警。恢复依赖后让 [Projector](../../backend/core/src/main/java/com/citycopilot/core/projection/ShopProjectionOutboxProjector.java) 按原版本和退避自动重试。
3. 确认相应事件完成、`citycopilot_shop_projection_backlog` 下降，且成功采样时间持续前进。店铺删除投影等尚未实现的事件需要按[投影边界](../adr/0010-versioned-shop-search-geo-projections.md#当前实现边界)处理，不能标成已成功投影。

<a id="order-completion"></a>
## 订单持久化完成

[订单完成采样器](../../backend/commerce-service/src/main/java/com/citycopilot/commerce/CommerceOrderCompletionMetrics.java)用一个 SQL 语句读取已提交的准入账本与订单。受理请求、重复消费和恢复调用不会直接累加完成计数；重启后从数据库重新取样。它只观察本服务的 MySQL 事实，不证明 Kafka 已发布、Core 已投影、用户已收到响应或支付完成。

| Prometheus 指标 | 口径 |
|---|---|
| `citycopilot_commerce_order_pending` | ACCEPTED 账本数，不受近期窗口限制 |
| `citycopilot_commerce_order_pending_oldest_age_seconds` | 全部 ACCEPTED 中最早 `accepted_at` 的年龄，使用数据库时钟 |
| `citycopilot_commerce_order_repair_required` | 全部 REPAIR_REQUIRED 账本数 |
| `citycopilot_commerce_order_completion_recent_admitted` | 采样器定义的近期受理窗口内，ACCEPTED、ORDER_CREATED、REPAIR_REQUIRED 身份数；排除 `legacy-order-v0` 历史订单补录 |
| `citycopilot_commerce_order_completion_recent_persisted` | 同一受理窗口内，按订单主键能读取到已提交 `commerce_order` 的身份数 |
| `citycopilot_commerce_order_completion_recent_mean_duration_seconds`、`citycopilot_commerce_order_completion_recent_max_duration_seconds` | 上述已创建订单的 `create_time - accepted_at`；只包含非负时间差，无有效耗时样本时为 NaN |
| `citycopilot_commerce_order_completion_sample_timestamp_seconds` | 最近一次完整成功采样的数据库 Unix 时间；初始为 0，失败不前进 |

这些值都是 **Gauge**，不能使用 `rate()` 或把各实例数值相加。近期完成比例可按同一实例的 `recent_persisted / recent_admitted` 计算，先排除零分母和失鲜采样，再对共享数据库的副本取 `max`。这是滚动受理群组当前完成比例，包含仍在途的请求，不是固定截止期限的成功率，也不是历史累计订单数。已完成项离开窗口时计数会下降；窗口外仍未完成的项由全量 pending 和年龄告警持续覆盖。

人工重排会重置账本 `accepted_at`，因此窗口与耗时属于**最近受理轮次**；已有订单早于该轮受理时仍计入已创建，但不产生负耗时。`create_time` 是订单行写入时间，不含后续事务提交耗时；补录或修复可能延迟账本终态，故没有把通用 `terminal_at` 当成落库时刻。这些指标不能重建初次受理至最终恢复的完整耗时；该口径需要保留不可变的初次受理及完成审计时间后再定义 SLO。

1. 先确认采样新鲜，再在 **Commerce 数据库**检查最老待完成项和人工修复原因：

```sql
SELECT order_id, voucher_id, status, accepted_at, attempts,
       routing_generation, physical_shard_count, claimed_until, last_error
FROM commerce_admission_ledger
WHERE status IN ('ACCEPTED','REPAIR_REQUIRED')
ORDER BY accepted_at, order_id LIMIT 20;
```

2. 对 ACCEPTED，检查实际 Stream 消费角色、Redis 连通性及持久化准入恢复日志；按 `order_id` 核对订单和 `commerce_order_recovery` 阶段。依赖恢复后由原身份继续处理，不能换幂等键重新下单，也不能删除账本来降低积压。
3. 对 REPAIR_REQUIRED，按 `last_error` 调查库存/身份冲突和重试耗尽；通过现有一致性快照、修复预览、修复与验证流程处理，保留证据和原始订单身份。存在补偿差异时，先修复并验证，再使用现有管理入口重排；不手工把账本改为 ORDER_CREATED。
4. 确认已提交订单存在、对应账本最终进入 ORDER_CREATED，且新鲜采样中的全量 pending/repair 数量下降。仅 HTTP 202 或 Stream ACK 不能证明订单已落库。

采样依赖 [Commerce V7 索引迁移](../../deploy/mysql/commerce/V7__order_completion_metrics_indexes.sql)：近期查询按受理时间范围扫描，订单按主键连接；全量积压按状态索引扫描。新增索引的线上构建窗口、空间与执行计划仍需在目标数据库评估，不通过缩短观察窗口隐藏长期积压。

<a id="kafka-consumer-lag"></a>
## Kafka 消费积压

积压取自 Core 应用自身的 Kafka 客户端指标：Spring Boot 把消费者的 `records-lag-max` 暴露到 `/actuator/prometheus`，名称为 `kafka_consumer_fetch_manager_records_lag_max`，标签 `client_id` 对应具体消费者线程（如 `consumer-citycopilot-event-audit-v1-1`）。不额外部署 exporter，也不抓取 broker。

| Prometheus 指标 | 单位与口径 |
|---|---|
| `kafka_consumer_fetch_manager_records_lag_max` | 该消费者客户端在其分到的分区上、最近采样窗口内的最大积压条数；Gauge |

同一消费组内各客户端分到的分区互斥，规则对客户端取 `max`，不相加。这个指标只在消费者在线并完成分区分配后才有；消费者全部离线时没有时间序列，告警不会触发，所以“没有告警”不等于零积压，需要同时看 `CityCopilotTargetDown`，或在 broker 侧用 `kafka-consumer-groups.sh --describe --group <组名>` 核对。

1. 积压持续过高：在任一 Kafka 节点执行 `kafka-consumer-groups.sh --bootstrap-server kafka-1:9092 --describe --group citycopilot-event-audit-v1`，看各分区 LAG 和分配到的消费者；检查 Core 日志里的消费失败、退避重试和数据库依赖。
2. 失败消息在有限次重试后转入 `<topic>.DLT`，不会无限阻塞分区。死信主题需要单独处置：确认原因后用原始字节重新投递，保持原 eventId，由 Inbox 去重。
3. 恢复后核对积压下降和原 eventId 的 Inbox 记录。不要重置消费位点、删除 topic、清空 Inbox 或更换 eventId 来消除告警。

上述配置不等于监控已部署；目标镜像、端点实际导出名称/标签、消费组离线与重试语义、规则触发恢复及通知接收都属于发布验收范围。覆盖不到的消费组须继续用原生管理信息核对，不能靠应用收到消息的时间推断 Broker 无积压。

<a id="sample-unavailable"></a>
## 观测数据不可用

1. Core 的 `citycopilot_shop_projection_sample_timestamp_seconds=0` 表示从未成功采样；不再前进表示采样失败或调度停滞。检查数据库连接、读取权限、连接池、定时任务日志及数据库与 Prometheus 时钟。恢复后须观察时间戳重新前进，而不只看 HTTP 200。
2. Commerce 的最老年龄 Gauge 在抓取时查询数据库；采样异常可能得到 `NaN`。检查 Relay 日志与数据库访问，恢复后须得到有限数值。它没有独立采样时间戳，不能套用 Core 的时间戳告警。
3. 指标完全缺失时，核对运行版本、实际角色和 target 角色标签。只抓取 API-only 实例无法证明后台队列正常；应抓取承担相应职责的实例。
4. 订单完成采样的时间戳同样必须持续前进；失败时计数和年龄保留旧样本，告警会排除失鲜值。检查采样 SQL、V7 迁移、连接池和定时任务异常。无完成项时耗时 Gauge 为 NaN 是合法空样本，不能据此认定采样失败。

## 离线规则验证

```text
promtool check config deploy/prometheus.yml
promtool test rules deploy/citycopilot-alerts.test.yml
```

[定向样例](../../deploy/citycopilot-alerts.test.yml)覆盖抓取失败的等待/触发/恢复、共享队列多副本不重复相加、积压解除、订单完成滞后与人工修复、失鲜/零/缺失采样、NaN、角色排除和失败抓取时不重复报采样告警。工具只校验配置和模拟时间序列，不能证明运行环境已抓取、真实故障已演练或通知已经送达。[Prometheus 官方规则测试说明](https://prometheus.io/docs/prometheus/latest/configuration/unit_testing_rules/)提供重放格式。
