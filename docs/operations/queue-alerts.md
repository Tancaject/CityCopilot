# 服务与持久化队列告警

[Prometheus 配置](../../deploy/prometheus.yml)加载[告警规则](../../deploy/citycopilot-alerts.yml)。阈值与持续时间只在规则文件维护，均为示例，尚不是实测 SLO。仓库提供规则与排查步骤，没有部署 Prometheus、配置 Alertmanager 接收人或发送通知；上线时还需明确值班负责人和升级路径。

启用时将配置和规则文件放在同一目录，并从监控进程所在网络核对实际目标地址。示例中的 `host.docker.internal` 指 Docker 容器访问宿主机；它不表示所有部署都使用这个地址。抓取标签 `projection_role="true"`、`outbox_relay_role="true"` 表示该目标**应当**运行相应角色，不会开启应用角色。独立部署时把标签移到实际 Projector/Relay 实例，API-only 实例不应带这些标签。

`booking_projection_role` 单独描述预约业务消息消费者，示例默认 `false`。只有确实要求该 Core 实例同时启用 RocketMQ、消费角色和预约反馈时才设为 `true`；它与店铺搜索/GEO 的 `projection_role` 无关。

`ops_case_role` 表示启用 Ops v2 数据库采样的 Agent 实例，不要求该实例同时承担 worker。关闭 Ops v2 时同步移除该预期标签；标签不会启用应用功能。

Core 指标只在 `citycopilot.projection.roles.projector-enabled=true` 时注册；Commerce Outbox 指标只在 `citycopilot.commerce.outbox-relay-enabled=true` 时注册。生产 profile 默认关闭这两个角色。Commerce 的 `rocketmq-enabled=false` 会停止发布，但仍可能存在待发布事实。订单完成采样在所有 Commerce 实例运行，不依赖消费者或 Relay 开关，因此 API-only 实例也应暴露订单完成指标。

多个实例读取同一张队列表，数量和年龄都不能按实例求和。规则对共享队列取 `max`；不同数据库/租户应分成不同 job 或增加队列身份标签后相应分组。Core 年龄告警只采用仍在成功抓取且采样新鲜的实例；不能把未采样、旧样本或 `NaN` 解释为零积压。

| 告警 | 处理入口 |
|---|---|
| `CityCopilotTargetDown` | [抓取失败](#target-down) |
| `CityCopilotBrokerMetricsDown`、`CityCopilotBrokerConsumerBacklog`、`CityCopilotBrokerConsumerLagAge` | [Broker 消费组积压](#broker-consumer-lag) |
| `CityCopilotCommerceOutboxAge` | [Commerce 事件发布](#commerce-outbox) |
| `CityCopilotShopProjectionAge` | [店铺搜索与 GEO 投影](#shop-projection) |
| `CityCopilotOrderCompletionAge`、`CityCopilotAdmissionRepairRequired` | [订单持久化完成](#order-completion) |
| `CityCopilotBookingProjectionConsumerUnavailable`、`CityCopilotBookingProjectionFailure`、`CityCopilotBookingProjectionDelay`、`CityCopilotBookingProjectionClockSkew` | [预约业务消息投影](#booking-projection) |
| `CityCopilotOpsManualActionRequired`、`CityCopilotOpsCaseSampleStale` | [Ops 最终处理结果](#ops-cases) |
| `CityCopilotProjectionSampleStale`、`CityCopilotOutboxMetricUnavailable`、`CityCopilotOrderCompletionSampleStale` | [观测数据不可用](#sample-unavailable) |

<a id="target-down"></a>
## 抓取失败

1. 在 Prometheus `/targets` 找到告警的 `job`、`instance`，读取最近抓取错误；从同一监控网络访问该实例的 `/actuator/prometheus`，核对实际监听端口、进程、路由、TLS/认证和 Actuator 暴露范围。
2. 修复对应进程或抓取配置后，确认该实例 `up` 恢复为 `1`，再检查下列业务队列告警。删除 target 会丢失该目标的 `up` 序列，不是恢复措施。

<a id="commerce-outbox"></a>
## Commerce 事件发布

1. 检查实际 Relay 角色、RocketMQ 发布开关及日志 `Unable to relay Commerce outbox event`。在 **Commerce 数据库**执行有界只读查询，确认事件状态、重试时间和失败原因：

```sql
SELECT event_id, event_type, status, attempts, next_attempt_at,
       claimed_until, last_error, created_at
FROM commerce_outbox_event
WHERE status IN ('READY','FAILED','CLAIMED','DEAD')
ORDER BY created_at LIMIT 20;
```

2. 按 `last_error` 修复数据库连接、RocketMQ Proxy/Broker、topic、TLS 或凭据配置；保持原 `event_id` 和持久化正文。恢复后 [CommerceOutboxRelay](../../backend/commerce-service/src/main/java/com/citycopilot/commerce/CommerceOutboxRelay.java) 会按退避继续处理 READY/FAILED/DEAD，并接管过期 CLAIMED；无需手工改状态或重建事件。
3. 确认最老事件进入 PUBLISHED、`citycopilot_commerce_outbox_unpublished` 下降且年龄告警解除。订单命令的 `commerce_dead_letter` 是另一条恢复链路，不能用它的重放接口处理 Outbox 事件。

<a id="shop-projection"></a>
## 店铺搜索与 GEO 投影

1. 先处理采样失鲜告警，确认 Projector 角色在运行。检查日志 `Shop projection poll failed` 或 `Shop projection event ... failed`，在 **Core 数据库**查看最老事件：

```sql
SELECT id, event_id, shop_id, aggregate_version, status, attempts,
       next_attempt_at, claimed_until, last_error, created_at
FROM shop_projection_outbox
WHERE status IN ('READY','FAILED','CLAIMED','DEAD')
ORDER BY created_at LIMIT 20;
```

2. 根据失败原因恢复 Elasticsearch alias/访问、Redis GEO 或数据库连接；检查是否有重建任务持有 `shop-projection-writer` 租约。不得清除活动租约、降低版本或通过删除 Outbox 行消除告警。恢复依赖后让 [Projector](../../backend/core/src/main/java/com/citycopilot/core/projection/ShopProjectionOutboxProjector.java) 按原版本和退避自动重试。
3. 确认相应事件完成、`citycopilot_shop_projection_backlog` 下降，且成功采样时间持续前进。店铺删除投影等尚未实现的事件需要按[投影边界](../adr/0010-versioned-shop-search-geo-projections.md#当前实现边界)处理，不能标成已成功投影。

<a id="order-completion"></a>
## 订单持久化完成

[订单完成采样器](../../backend/commerce-service/src/main/java/com/citycopilot/commerce/CommerceOrderCompletionMetrics.java)用一个 SQL 语句读取已提交的准入账本与订单。受理请求、重复消费和恢复调用不会直接累加完成计数；重启后从数据库重新取样。它只观察本服务的 MySQL 事实，不证明 RocketMQ 已发布、Core 已投影、用户已收到响应或支付完成。

| Prometheus 指标 | 口径 |
|---|---|
| `citycopilot_commerce_order_pending` | 所有路由代际的 ACCEPTED 账本数，不受近期窗口限制 |
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

<a id="booking-projection"></a>
## 预约业务消息投影

实际业务链路是 Commerce Booking Outbox 的 `BookingEvent` → [独立预约消费组](../../backend/core/src/main/java/com/citycopilot/core/event/RocketMqBookingBehaviorConsumer.java) → [BookingBehaviorProjector](../../backend/core/src/main/java/com/citycopilot/core/booking/BookingBehaviorProjector.java)。Inbox、业务事实、当前预约状态和偏好特征在同一事务内写入；低版本 STALE 事件仍可新增历史事实，但不会把当前预约版本倒退。

[BookingProjectionMetrics](../../backend/core/src/main/java/com/citycopilot/core/event/BookingProjectionMetrics.java)在 **Spring 实际事务提交回调**中记录新事实的 `occurredAt → 提交回调时间`。外层事务尚未提交或最终回滚不会产生成功样本；DUPLICATE 和 QUARANTINED 只记录分类，PROCESSED 与 STALE 才能产生新的延迟样本。延迟包含源服务发布等待、消息传输/排队和 Core 事务处理，不是消费方法自身运行时间；它依赖发布方与 Core 的时钟同步，不能拆解成单独 Broker 延迟。

| Prometheus 指标 | 口径 |
|---|---|
| `citycopilot_booking_business_consume_total{outcome="processed\|stale\|duplicate\|quarantined"}` | 已提交的 Inbox 分类观测；不是所有投递尝试，也不是永久准确的事件总账 |
| `citycopilot_booking_business_consume_total{outcome="failure"}` | 消费尝试抛出异常并请求重投的次数，同一事件可能重复失败 |
| `citycopilot_booking_projection_delay_seconds_count`、`_sum`、`_max` | 新业务事实提交后的非负事件延迟观测；计数为 0 表示该进程尚无有效样本 |
| `citycopilot_booking_projection_last_receipt_seconds` | 本进程最后一次收到消息尝试的时间，不代表已提交 |
| `citycopilot_booking_projection_last_commit_seconds` | 本进程最后一次新业务事实提交回调时间；初始 0 表示未观测 |
| `citycopilot_booking_projection_last_delay_seconds` | 最近一次新业务事实的延迟；初始及负时间差为 NaN，不用零掩盖空样本或时钟偏差 |
| `citycopilot_booking_projection_clock_skew_total` | 提交观测时间早于事件发生时间的次数；该次提交仍记录为成功，但不纳入延迟 Timer |
| `citycopilot_booking_projection_consumer_active` | 本地 PushConsumer 已构建且尚未关闭为 1；不证明 Broker 连接或整个消费组健康 |

这些 Counter/Timer 是**进程观测**，重启会重置；多消费者实例的 `rate`/`increase` 可按同一业务消费组汇总，和读取共享数据库的 Gauge 不同。数据库提交后、回调或 Prometheus 抓取前进程退出可能漏掉样本；不能把它们作为 exactly-once 财务/业务对账记录。历史回放会产生真实的大延迟，需结合重放安排解释。

无事件、只收到重复事件、重启后尚无新事实时，成功延迟是**未知**。规则不会把正常低流量直接判成故障，也不会把 `Timer.max=0` 当成零延迟证明；延迟告警仅采用规则所定义的近期成功样本。过期样本不参与当前延迟判断，告警解除不代表链路已恢复。结合下述 [Broker 消费组积压](#broker-consumer-lag)及源端应产生的事件，区分正常空闲与完全收不到消息；原生消费组指标不替代事务投影结果。

1. 消费者不可用：对照 `booking_projection_role` 检查运行时 `citycopilot.rocketmq.enabled`、`consumer-enabled` 与 `citycopilot.booking-feedback.enabled`，检查启动日志、topic/tag、消费组、TLS 和访问权限。先修复真实角色或抓取配置，不把标签改成 `false` 来消除真实故障。
2. 失败或隔离：读取 `Booking behavior event handling failed` 日志，按原始 eventId 查询 Core `processed_event` 的 `status/quarantine_reason` 和业务事实。异常也可能发生在提交后的回调阶段，不能仅凭 FAILURE 推断数据库没有提交；以 Inbox 和业务事实为准。解析/数据库错误由原消息重试；同 ID 不同载荷需要调查发布方身份与原文哈希，不能清空 Inbox 或换新 ID 重放同一事实。
3. 延迟过高：同时检查 Commerce Outbox 的发布等待、Broker 实际消费组积压、Core 连接池/事务耗时及锁等待。重试恢复后观察**新**提交样本，不能仅看 `last_receipt` 前进或审计消费者 ACK。
4. 时钟偏差：比较事件 `occurredAt` 与源服务、Core 的 UTC 时间和时钟同步状态；修正时钟后观察新非负样本。不得修改已持久化事件正文或把负值直接归零。

边界：Core 的 [RocketMqEventAuditConsumer](../../backend/core/src/main/java/com/citycopilot/core/event/RocketMqEventAuditConsumer.java)只登记审计 Inbox/聚合水位，它的 PROCESSED 不是上述业务投影完成。Agent 的 [OpsEventConsumer](../../backend/agent/src/main/java/com/citycopilot/agent/ops/v2/OpsEventConsumer.java)只处理库存差异事件；[OpsCaseRepository](../../backend/agent/src/main/java/com/citycopilot/agent/ops/v2/OpsCaseRepository.java)在一笔事务内去重并创建/关联 case，现有接收分类也不证明 Ops 诊断、审批或修复已经完成。本批未为这些链路虚构预约或订单业务投影延迟。

<a id="broker-consumer-lag"></a>
## Broker 消费组积压

[Broker 配置](../../deploy/rocketmq/broker.conf)复用 RocketMQ 内建 Prometheus exporter；配置键以 [RocketMQ 5.5.0 BrokerConfig](https://github.com/apache/rocketmq/blob/rocketmq-all-5.5.0/common/src/main/java/org/apache/rocketmq/common/BrokerConfig.java)为依据。它在容器内监听 `5557/metrics`，Compose 没有新增宿主机端口映射，也没有新增 exporter 或 Prometheus 服务。监控进程必须加入 Compose 的 `citycopilot` 网络（实际网络名含项目名前缀），或使用能解析并到达 Broker 的等效私网地址；宿主机不能直接假定 `rocketmq-broker` DNS 可用。生产仅允许监控网络访问该端点。

`broker_metrics_role="true"` 表示 target 应暴露 Broker 原生指标，不会启用 Broker 或应用消费者。当前目标对应单个 Broker；扩容后逐个配置实际服务消息的 Broker，核对主从角色，不能把代理或重复副本当成独立分片求和。现有规则按 `cluster/node_id/topic/consumer_group/is_retry` 分开告警，以 `max` 去掉同一身份的重复抓取，保留普通消息和重试消息的差异；它判断单 Broker 积压，不是集群总量。规则中的消费组选择器须随实际消费组命名调整，不能静默漏掉改名后的组。

| Prometheus 指标 | 单位与口径 |
|---|---|
| `rocketmq_consumer_lag_messages` | Broker 计算的消费组积压数量，Gauge；不是生产计数或业务失败次数 |
| `rocketmq_consumer_lag_latency_milliseconds` | 最早未确认消息就绪至当前时刻的延迟，毫秒；不是事件 occurredAt 到应用事务提交的延迟 |

指标基础名称与标签见 [5.5.0 BrokerMetricsConstant](https://github.com/apache/rocketmq/blob/rocketmq-all-5.5.0/broker/src/main/java/org/apache/rocketmq/broker/metrics/BrokerMetricsConstant.java)，注册与 `milliseconds` 单位见 [5.5.0 BrokerMetricsManager](https://github.com/apache/rocketmq/blob/rocketmq-all-5.5.0/broker/src/main/java/org/apache/rocketmq/broker/metrics/BrokerMetricsManager.java)。Prometheus 导出会附加单位后缀；[Apache Dashboard 的原生 5.x 指标修正](https://github.com/apache/rocketmq-dashboard/pull/2838)明确使用带 `_milliseconds` 的名称，不能直接采用概览文档中的无后缀仪表名称。就绪、处理中和未确认的区别见[官方指标口径](https://rocketmq.apache.org/docs/observability/01metrics/)。

这些观测不证明消息业务处理成功，也不是永久精确的业务总账。初次订阅、消费组离线或订阅信息缺失时，不能把**没有时间序列**或告警解除解释为零积压；`up=1` 只说明端点可抓取，不能证明每个预期消费组都被覆盖。部署验收须分别核对实际预约、审计、Ops 消费组与 topic/tag，检查在线、离线、普通及重试消息的指标覆盖，再和源 Outbox、应用 Inbox/投影结果对照。尚未有数据的组保留“未知”，不能用默认零补齐。

1. 抓取失败：先在 Prometheus `/targets` 检查错误与 Broker 日志，核对私网 DNS/路由、实际 exporter 配置、监听端口和访问限制；本端点是 `/metrics`，不是应用的 `/actuator/prometheus`。不通过开放公网端口解决内网接入问题。
2. 积压或延迟过高：按告警中的 cluster、node_id、topic、consumer_group、is_retry 定位实际订阅；检查消费者启动、连接、重投和数据库依赖。审计组、预约业务组、Ops 组分别负责不同结果，不能用其中一个组的 ACK 代表全部处理完成。
3. 恢复后同时核对新鲜 Broker 样本下降、原 eventId 的 Inbox/业务事实或 Ops Case 状态。不要重置消费位点、删除 topic、清空 Inbox 或更换 eventId 来消除告警。转入死信的事件仍需单独处置，积压下降并不证明已修复。

上述配置不等于监控已部署；目标镜像、端点实际导出名称/标签、消费组离线与重试语义、规则触发恢复及通知接收都属于发布验收范围。覆盖不到的消费组须继续用原生管理信息核对，不能靠应用收到消息的时间推断 Broker 无积压。

<a id="sample-unavailable"></a>
## 观测数据不可用

1. Core 的 `citycopilot_shop_projection_sample_timestamp_seconds=0` 表示从未成功采样；不再前进表示采样失败或调度停滞。检查数据库连接、读取权限、连接池、定时任务日志及数据库与 Prometheus 时钟。恢复后须观察时间戳重新前进，而不只看 HTTP 200。
2. Commerce 的最老年龄 Gauge 在抓取时查询数据库；采样异常可能得到 `NaN`。检查 Relay 日志与数据库访问，恢复后须得到有限数值。它没有独立采样时间戳，不能套用 Core 的时间戳告警。
3. 指标完全缺失时，核对运行版本、实际角色和 target 角色标签。只抓取 API-only 实例无法证明后台队列正常；应抓取承担相应职责的实例。
4. 订单完成采样的时间戳同样必须持续前进；失败时计数和年龄保留旧样本，告警会排除失鲜值。检查采样 SQL、V7 迁移、连接池和定时任务异常。无完成项时耗时 Gauge 为 NaN 是合法空样本，不能据此认定采样失败。

<a id="ops-cases"></a>
## Ops 最终处理结果

[`OpsCaseMetrics`](../../backend/agent/src/main/java/com/citycopilot/agent/ops/v2/OpsCaseMetrics.java) 读取已提交的 `ops_case`，不以消息接受或工具调用成功代替修复完成。部署前执行 [Agent V3 索引迁移](../../deploy/mysql/agent/V3__ops_case_observation_indexes.sql)，按实际表大小安排 DDL 窗口。

- `citycopilot_ops_case_pending` 与 `citycopilot_ops_case_pending_oldest_age_seconds` 描述自动处理/重试中的 Case；年龄从 Case 创建时刻计算，包含此前人工等待，不能当作当前步骤耗时。
- `citycopilot_ops_case_manual_required` 只统计 MANUAL_REVIEW 和 FAILED；正常的 AWAITING_APPROVAL 不触发人工故障处置告警。FAILED 仍可按既有人工重试流程恢复，不可直接删除。
- `citycopilot_ops_case_resolution_recent_succeeded` 统计最近 15 分钟进入 SUCCEEDED 的 Case；`recent_mean_duration_seconds`、`recent_max_duration_seconds` 使用 `created_at` 到 `resolved_at` 的行时间，包含审批等待。取消不算修复成功，负时间差不进入耗时样本。这些是滚动 Gauge，不可用 `rate()`，也不等同于数据库提交延迟、自动处理成功率或持久化累计 SLO。
- `citycopilot_ops_case_sample_timestamp_seconds` 使用数据库时钟。启动未采样为 0，失败保留旧值；无完成项时耗时为 NaN。样本持续更新且计数为 0 才表示该窗口没有完成项；不代表 MQ 没有积压。

收到人工处置告警时，通过现有 Ops Case 列表/详情读取 case、最后错误、证据、计划与执行结果。先处理 MANUAL_REVIEW/FAILED 的具体原因，再沿原 Case/计划审批与幂等身份重试；不自动消费审批、不新建重复修复。采样失鲜时检查 Agent 数据库连接、迁移、定时任务日志及数据库时钟，恢复后观察采样时间前进。多实例读取同一数据库使用 `max`，不能累加。

## 离线规则验证

```text
promtool check config deploy/prometheus.yml
promtool test rules deploy/citycopilot-alerts.test.yml
```

[定向样例](../../deploy/citycopilot-alerts.test.yml)覆盖抓取失败的等待/触发/恢复、共享队列多副本不重复相加、积压解除、订单完成滞后与人工修复、失鲜/零/缺失采样、NaN、角色排除和失败抓取时不重复报采样告警。工具只校验配置和模拟时间序列，不能证明运行环境已抓取、真实故障已演练或通知已经送达。[Prometheus 官方规则测试说明](https://prometheus.io/docs/prometheus/latest/configuration/unit_testing_rules/)提供重放格式。
