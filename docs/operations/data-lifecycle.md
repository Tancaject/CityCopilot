# 数据生命周期

业务有效期不等于删除期限。过期报价、已发布事件和已消费审批具有不同的恢复责任，不共用一个 TTL。

## 未消费报价

[`BookingQuoteCleanup`](../../backend/commerce-service/src/main/java/com/citycopilot/commerce/pricing/BookingQuoteCleanup.java) 仅删除超过保留期且 `consumed_booking_id IS NULL` 的报价。已消费报价、预约价格快照、命令幂等记录、优惠券和 Outbox 均保留。查询已清理报价沿用 404 与重新报价提示；客户端需重新展示并确认价格，不能把新报价替换进原来的批准或幂等请求。

先执行 Commerce Flyway 迁移，再在承担清理任务的 Commerce 实例启用 `CITYCOPILOT_BOOKING_QUOTE_CLEANUP_ENABLED`。必须显式设置 `CITYCOPILOT_BOOKING_QUOTE_RETENTION_DAYS` 为过期后继续保留的非负天数，0 表示过期后即可回收；未配置保留期时启用会拒绝启动。默认值及批量、周期参数以[应用配置](../../backend/commerce-service/src/main/resources/application.yaml)为准，[Compose](../../compose.yaml)透传同名环境变量；无需修改生产数据库来启用功能。

每次只处理一批，不循环追空队列。按数据库 UTC 时间确定截止点，使用未消费状态与到期时间的联合索引；`FOR UPDATE SKIP LOCKED` 跳过正在被暂留或改期事务锁定的报价。选取与删除在同一事务，失败全部回滚，后续周期仍可重试。日志输出生效的保留期、批量上限，以及成功删除时的实际截止时间和数量。

启用前在 Commerce 数据库做有界只读核对，确认业务选择的保留窗口：

```sql
SELECT id, user_id, expires_at, consumed_booking_id
FROM commerce_booking_quote
WHERE consumed_booking_id IS NULL AND expires_at < UTC_TIMESTAMP(3)
ORDER BY expires_at, id LIMIT 20;
```

关闭清理开关并重启该实例可停止后续执行；已经删除的未消费报价不会重建。已接受交易通过原命令幂等键查询，不依赖重新生成报价。上线前仍需按实际数据量评估新增索引的 DDL 窗口和数据库备份恢复能力。

## 已发布 Commerce 事件正文

[`CommerceOutboxArchive`](../../backend/commerce-service/src/main/java/com/citycopilot/commerce/CommerceOutboxArchive.java) 将超过在线保留窗口的 PUBLISHED 正文原样移至同一数据库的 `commerce_outbox_payload_archive`，记录原文 SHA-256。热表保留 eventId、聚合身份/版本、唯一约束、topic、状态和发布历史，以 `payload_archived_at` 标明正文位置；不删除整行，不把尚未发布的事件移出恢复链路。这是数据库内的正文分离，**不减少总历史数据量，也不替代异地备份或对象存储归档**。

选取行、复制原文与摘要、清空热表正文在一个事务内完成。每轮只处理一批，跳过被其他事务锁定的行；复制冲突或后续更新失败全部回滚。READY、FAILED、CLAIMED、DEAD 以及未到保留窗口的事件不受影响。PUBLISHED 只表示 Broker 接受发布，不证明所有下游已经处理，因此历史正文继续保留，不设置自动删除期限。

发布顺序：

1. 执行 [Commerce V8](../../deploy/mysql/commerce/V8__outbox_payload_archive.sql)，保持归档关闭。评估新增索引、表结构调整的锁与空间窗口。
2. 将**所有** Relay 实例升级为能够读取归档正文的版本，包括暂时停用而可能恢复的实例。新 Relay 需要 V8 的表和字段，即使归档开关关闭也应先完成迁移。
3. 明确在线重投、排障和审计窗口，显式设置 `CITYCOPILOT_COMMERCE_OUTBOX_ARCHIVE_RETENTION_DAYS`（从 `published_at` 起计的非负天数），再开启 `CITYCOPILOT_COMMERCE_OUTBOX_ARCHIVE_ENABLED`。未配置窗口时启用会拒绝启动。默认开关、批量与周期以[应用配置](../../backend/commerce-service/src/main/resources/application.yaml)为准，不提供一个擅自确定的业务保留天数。

归档后如按既有受控运维流程重投，Relay 继续使用原 eventId、topic 和原始正文，不重新序列化历史事件。正文缺失或摘要不匹配时拒绝发布并保持失败重试状态；应从已核验备份恢复原文，再继续，不能生成新事件身份绕过。归档本身不重投消息，也没有新增公开重投接口。

按事件身份进行有界取证：

```sql
SELECT e.event_id, e.status, e.published_at, e.payload_archived_at,
       CASE WHEN e.payload_archived_at IS NULL THEN e.payload_json ELSE a.payload_json END AS payload_json,
       a.payload_sha256,
       SHA2(CAST(a.payload_json AS BINARY),256) AS actual_archive_sha256
FROM commerce_outbox_event e
LEFT JOIN commerce_outbox_payload_archive a ON a.event_id=e.event_id
WHERE e.event_id = '<待核验的原 eventId>';
```

备份与恢复必须同时覆盖两张表；删除历史正文会破坏重投与审计能力。停用归档仅停止后续迁移，不自动回填已有正文。启用归档后，应用回退必须选择仍支持 V8 读取的版本；要退到旧 Relay，需先在维护窗口专项回填并核验全部已归档正文，不能仅关闭开关后直接启动旧版。本功能不提供自动回填或外部存储删除。

## 已关闭 Ops Case 的证据正文

[`OpsEvidenceArchive`](../../backend/agent/src/main/java/com/citycopilot/agent/ops/v2/OpsEvidenceArchive.java) 仅处理 SUCCEEDED/CANCELLED 且关闭时间超过显式保留窗口的 Case，并要求证据本身已过期。FAILED、人工复核、待审批和其他仍可推进的状态均不进入归档。每轮最多移动配置数量的证据，使用数据库时钟和 `FOR UPDATE SKIP LOCKED`；复制原始正文与 SHA-256、更新正文位置在同一事务提交。

`ops_evidence` 保留证据 ID、case 关联、原 `payload_hash`、采集和到期时间。Case、Inbox、计划原文/哈希、审批决定/消费时间、执行参数/幂等键/结果以及审计时间线继续在线保留；归档不会重算计划哈希、重新生成审批或重置执行状态。Case 详情沿原查询返回归档前的证据字段，并核验历史正文摘要；正文缺失或改变会中止读取并提示恢复原文，不显示空证据代替。

先执行 [Agent V4](../../deploy/mysql/agent/V4__ops_evidence_payload_archive.sql)，再将所有 Agent 实例升级到归档读取版本，期间保持开关关闭。新读取代码依赖 V4，即使未启用归档也应先迁移。选择从 `resolved_at` 起计算的在线审计保留天数后，设置 `CITYCOPILOT_OPS_EVIDENCE_ARCHIVE_RETENTION_DAYS` 并开启 `CITYCOPILOT_OPS_EVIDENCE_ARCHIVE_ENABLED`；未配置保留期时启用会拒绝启动。批量上限与默认值见[应用配置](../../backend/agent/src/main/resources/application.yaml)。

此功能只把正文移至同库历史表，不删除历史、不减少总存储量。备份必须同时包含 `ops_evidence` 与 `ops_evidence_payload_archive`。关闭开关只停止后续迁移；已有正文不会自动回填，应用只能直接回退到兼容 V4 读取的版本。回退旧版前需另行安排原文回填及核验的维护窗口。

## 恢复身份与其他保留策略

- Admission ledger 的用户/幂等键、请求指纹、订单身份与最终结果，以及 Inbox 的消费组/eventId/payload hash，继续在线保留；不按创建时间直接删除。
- Core 投影 Outbox 的正文仅包含事件类型、店铺/版本及类型/GEO 状态等标量；投影与全量重建追尾使用在线身份列回读店铺事实，不读取正文。当前保留原表，不另建正文归档。若以后移除历史身份行，必须与现有 writer 租约及 fencing 协调，并保留 eventId、店铺/版本唯一约束和重建追尾所需范围；仅持有租约不足以证明删除安全。
- Ops 的 FAILED 仍允许人工重试；证据或审批过期不是删除许可。已关闭 Case 的证据正文按上述策略分离，其他事件/计划/审批/执行身份及正文继续保留；尚无这些历史的自动删除策略。
- Trace 与待确认动作沿用各自存储的 TTL；不得从 Trace 重建已经失效或消费过的授权。

上述恢复身份与 Core 投影历史不设自动删除期限；报价清理、Commerce 正文分离与 Ops 证据正文分离不改变它们的保留策略。
