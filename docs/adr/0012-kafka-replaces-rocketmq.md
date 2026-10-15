# ADR-0012：Kafka 替换 RocketMQ 承载跨服务事件

状态：接受  
日期：2026-10-15  
替代：[ADR-0005](0005-redis-stream-and-rocketmq-layered-messaging.md)（分层决策保留，只替换跨服务事件的消息系统）

## 背景

ADR-0005 的分层仍然成立：秒杀准入的 Redis Lua 必须原子写入 Redis Stream，跨服务领域事件走独立 MQ。原 RocketMQ 部署是单 Broker、无副本，副本与故障切换的语义无法演练；跨服务事件当前只有 Core 的审计消费者，替换的代码面很小。

## 决策

- 部署 3 节点 Kafka KRaft 集群，每个节点同时是 broker 和 controller。业务主题与 `.DLT` 死信主题均为 6 分区、复制因子 3、`min.insync.replicas=2`，关闭自动建主题和 unclean leader election。三个节点在同一主机上，演示副本语义，不是机器级高可用。
- 生产端仍是事务 Outbox + Relay：认领、fencing token、指数退避不变。发送用 `acks=all` 与幂等生产者，拿到确认才标记已发布；失败记录最内层原因。消息 key 为 aggregateId，eventId、eventType、原文哈希放在消息头。
- Kafka 事务只覆盖 Kafka 内部读写，覆盖不到“MySQL 提交 + 发送”，因此不使用。投递语义仍是至少一次，重复由消费端 Inbox 去重。
- 消费端用 `@KafkaListener`，处理成功（Inbox 事务提交）后容器才提交 offset。暂时性失败按指数退避重试 3 次，仍失败或信封不合法时转发到同分区的 `<topic>.DLT`，不阻塞分区。分区分配使用 cooperative-sticky。
- 不依赖分区内顺序：多个 Relay 的发送与重试顺序本来就无法保证，消费者继续按 aggregateVersion 判新旧。
- 消费积压告警使用 Core 暴露的客户端指标 `kafka_consumer_fetch_manager_records_lag_max`，不部署 exporter。

## 业务消费者：店铺销量

审计消费者只登记事件、没有业务效果。新增独立消费组 `citycopilot-shop-sales-v1`，按订单事件维护 Core 的 `shop.sold`：

- 订单事件载荷自 schemaVersion 2 起带 `shopId`、`voucherId`（只新增字段，旧消费者忽略即可）；没有 shopId 的旧载荷跳过。
- `shop_order_sales` 记录每张订单在 Core 侧最后看到的状态与版本；已支付(2)、已使用(3)计入销量。新事件版本高于已存版本才覆盖，按“是否计入”的前后变化给 `shop.sold` 加减 1。Inbox 登记、投影行与销量在同一事务提交，提交后失效店铺缓存。退款先于支付到达时，迟到的支付因版本更低被丢弃。
- `sold` 为 UNSIGNED，账不平导致减到负数时报错，消息重试后进死信并告警，不做截断。
- 历史订单用 `python tools/backfill_shop_sold.py` 一次性回填：导入 Commerce 全部订单的当前状态（版本取状态码），再由投影表整体重算 `shop.sold`。可重复执行；种子数据中的销量会被真实订单数覆盖。

## 验证

- 单元测试覆盖消息 key 与消息头、发送失败转为可重试状态并记录根因；`KafkaEventPipelineIT` 在真实集群上验证重复投递 Inbox 不变、坏消息进死信且后续消息照常处理、存储暂时故障重试后成功而不进死信。
- [压测与故障演练](../evidence/2026-10-15-kafka-load-and-faults.json)（2 Core + 2 Commerce）：1 个节点宕机期间 200 个事件全部发布并被消费，其中 1 个因分区重新选主重试了一次；2 个节点宕机时写入被拒、事件进入 FAILED 退避，恢复后发布且 Inbox 只处理一次。原有的 Outbox 投递实例被杀、Stream Worker 被杀、Redis 重启演练全部通过。
- 切换时把一条已由 RocketMQ 投递并处理过的事件改回待发布，经 Kafka 重投后 Inbox 记为重复，处理记录不变。
- 店铺销量：IT 覆盖支付计入一次、重复投递不再计、退款减回、退款先于支付到达时不计、未支付取消不计；主环境端到端走“建券 → 下单 → 模拟支付 → 退款”，`shop.sold` 与店铺接口依次为 1 → 2 → 1。

## 结果与回滚

RocketMQ 的 namesrv/broker/proxy/dashboard 共 6 个容器被 3 个 Kafka 节点、1 个建主题任务和可选 UI 取代，内存预算相近。`CITYCOPILOT_KAFKA_ENABLED=false` 关闭发布与消费，业务链路与 Redis Stream 不受影响；关闭期间不得删除未发布 Outbox。Outbox 与具体 MQ 无关，切换前只需等待旧消费积压清零，未发布事件由 Relay 自动发往新集群。
