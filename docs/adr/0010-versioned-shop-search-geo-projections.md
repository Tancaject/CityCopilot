# ADR-0010：店铺 GEO 版本化投影

状态：接受（已实现当前基线，尚未完成生产验证）
日期：2026-08-04（2026-10-06 移除 Elasticsearch 投影）

## 背景

店铺写入由 Core MySQL 提供权威事实，Redis GEO 提供附近店铺查询。事务 afterCommit 的尽力写入、JVM `synchronized`、进程内 `AtomicBoolean` 和每副本定时全量重建无法保证多 Core 实例下不丢更新，也无法阻止旧快照覆盖新投影。

## 决策

- Core MySQL 是店铺、分类、坐标和版本的唯一事实源；Redis GEO、缓存和 Commerce 店铺摘要均为可重建投影。关键词检索直接走 MySQL。
- 经 `ShopService` 的店铺变更在同一事务中递增 `projection_version` 并写入 MySQL `shop_projection_outbox`。
- Projector 直接从该 MySQL Outbox claim 事件；可过期 claim 与共享 MySQL writer lease/fencing token 协调多个实例，并与全量重建互斥。当前链路不经过 Kafka，也不虚构未实现的 Consumer Inbox。
- Redis GEO 用 Lua 比较投影版本。失败事件永久保持可重试并采用有上限的指数退避，旧版本不能覆盖新状态。
- 全量重建使用带 fencing token 的单活租约、隔离 generation、封闭起始水位、预追平和 cutover barrier 内的最终尾追；最终尾追完成后才切换 active generation。
- 基础扫描先记录店铺主键上界，再使用 [`ShopMapper`](../../backend/core/src/main/java/com/citycopilot/core/mapper/ShopMapper.java) 的有界主键游标页；后续新增/修改通过原 Outbox 水位追平。基础页使用多成员 GEOADD 与 Hash 批量写入，增量仍保留每家店铺的版本 Lua。基础页与追尾页之间续租，失去写租约后中止本次构建。
- 下单、权限、库存和店铺可用性必须由 Core/Commerce 再次权威校验，GEO 只负责召回候选。Redis GEO 不可用时回退 MySQL 包围盒加球面距离查询。
- 投影积压由 [`ShopProjectionMetrics`](../../backend/core/src/main/java/com/citycopilot/core/projection/ShopProjectionMetrics.java) 独立于消费循环采样，覆盖 READY、FAILED、CLAIMED、DEAD；指标抓取只读内存样本。数量和最老事件年龄必须结合最后成功采样时间判断，数据库采样失败不能解释为积压清零。仅 projector 角色注册这些指标，多副本共享同一队列，积压不得按实例求和。

## 被否决的方案

- 应用事务提交后直接写 GEO：业务提交与投影写没有原子性，失败会永久丢更新。
- JVM 本地锁或布尔状态：不能协调多个实例。
- 每个 Core 副本启动或定时执行全量重建：会并发覆盖和错误切换 generation。
- Elasticsearch 全文检索投影：曾作为可选读模型实现，但店铺量级下 MySQL `LIKE` 已满足关键词查询，额外的索引代际、alias 切换和故障降级成本没有对应收益，2026-10-06 移除。

## 结果

GEO 可以故障降级并从事实源重建，重复或乱序事件不会让旧版本覆盖新状态。代价是 Core 需要 Shop Outbox，平台需要 Projector、generation 元数据、高水位和重建监控。

## 当前实现边界

- 只保证所有经 `ShopService` 并写入 `shop_projection_outbox` 的店铺新增/更新；店铺删除 tombstone 尚未实现，Projector 会保留事件重试并拒绝静默切换。
- 独立修改 `shop_type` 不会自动为其关联店铺生成相同水位协议下的事件。
- 分页限制单批记录数量，不是对单次数据库/Redis 调用耗时的保证；需根据单页耗时设置维护租约，续租失败时中止构建而不发布。重建返回数量仍为基础页写入数，不包含随后追尾的新增店铺。
- 旧 generation 通过下述一次性维护入口受控回收，默认关闭；不在重建尾部或常驻定时任务中自动删除。
- 当前 MySQL Outbox 是投影内部队列。若未来需要把 `ShopChanged` 分发给 Commerce 等跨服务消费者，再增加 Kafka Relay/Inbox，而不是把它描述成已经存在。
- 有界重建的发布验收须覆盖主键跨页、空页、重建期间写入、分页续租失败及批量写部分失败时仍不发布。真实 MySQL、Redis Cluster 多实例切换与故障注入仍是发布门槛。

## 旧代际受控回收

[`ShopProjectionRetirementRegistry`](../../backend/core/src/main/java/com/citycopilot/core/projection/ShopProjectionRetirementRegistry.java) 只登记成功发布时解除引用的旧代际。退役时间与候选截止时间均使用 Redis `TIME`，不混用 Core 多副本时钟。新构建、失败构建、v1 兼容键、历史未登记代际都不进入候选。登记失败会记录日志并保留旧数据，不把已成功发布的新代际改回失败；记录丢失时也不会根据名称或创建时间补造退役证据。

回收至少保留活跃代际和登记集合中最近退役的一代；更早代际还必须超过显式保留期。每批获取原 `shop-projection-writer` 租约，每个候选前续租，并在 `executeFenced` 的数据库行锁内重读外部引用、完成一批删除：

- 重读共享 ready 指针和构建元数据引用。仅扫描已登记的精确 v2 generation 前缀，每次最多 UNLINK 配置数量的键；扫描未结束就保留候选供下一次请求继续。它不使用 KEYS、不删除当前 ready/meta、不清空数据库。SCAN 的 COUNT 是扫描提示，删除数量上限不代表整次遍历有固定耗时上界。
- 后继写者在同一 writer fence 结束前不能发布；外部手工指针变更也必须遵守该维护互斥。删除跨 MySQL 与 Redis 并不原子，失败不会恢复已经删除的键。只有确认删除完成或目标本就不存在时才移除退役记录。

GEO 每次读取 ready generation，查询后再次比较指针，未缓存代际。请求预算和客户端超时不能证明暂停线程已经退出，因此这里**不以一个超时值推断旧读者已消失**。入口仅用于运维在所有副本上实际封闭读取、等待在途请求退出的维护窗口。无法证明排空时必须保留旧代际。`readersQuiesced=true` 只是每次调用的显式声明，不会关闭流量，也不是排空证明；恢复读取之后不得再以该声明调用清理。

### 配置与一次性操作

清理入口是 [`GeoController`](../../backend/core/src/main/java/com/citycopilot/core/controller/GeoController.java) 的 `POST /geo/shop-index/retired/cleanup`，复用管理会话、GEO_WRITE 权限和操作审计。一次请求只处理一批，完成后没有继续执行的调度器。配置权威来源为 [`ShopProjectionProperties.Cleanup`](../../backend/core/src/main/java/com/citycopilot/core/config/ShopProjectionProperties.java)：

| 配置 / 环境变量 | 生效条件 |
|---|---|
| `citycopilot.projection.cleanup.enabled` / `CITYCOPILOT_PROJECTION_CLEANUP_ENABLED` | 默认关闭，获准的维护窗口才启用 |
| `retention` / `CITYCOPILOT_PROJECTION_CLEANUP_RETENTION` | 从退役时刻计算的回滚保留期，必须显式设置为至少一毫秒的正 Duration |
| `reader-grace` / `CITYCOPILOT_PROJECTION_CLEANUP_READER_GRACE` | 显式读者宽限期，必须为至少一毫秒的正 Duration，且 retention 不小于它；不能替代实际排空 |
| `generation-batch-size` / `CITYCOPILOT_PROJECTION_CLEANUP_GENERATION_BATCH_SIZE` | 单次候选上限，允许范围 1–100 |
| `key-batch-size` / `CITYCOPILOT_PROJECTION_CLEANUP_KEY_BATCH_SIZE` | 每个候选单批删除上限，允许范围 1–1000 |

执行实例须启用 reconciliation 角色。其他副本及维护客户端必须使用相同 MySQL writer 租约和 Redis 退役记录。记录存储需要持久化和受限写权限；Redis 恢复或时钟跳变后须重新核对退役时间，不能直接继续清理。该 SCAN 路径沿用当前独立 Redis 部署；Redis Cluster 下未经全节点扫描与覆盖证据，不得把局部扫描完成当作整代已删除。

先保存当前 ready 指针、拟回滚代际、实际配置与读者排空证据，封闭业务读取，仅放行已授权的维护会话。将实际维护地址和登录会话请求头放入当前 PowerShell 进程，再逐次调用（不要自动循环）：

```powershell
$maintenanceUrl = '<已审核的 Core 私网维护地址>'
# $maintenanceHeaders 由当前已登录且具备 GEO_WRITE 的管理会话提供，不把令牌写入脚本或文档。
$result = Invoke-RestMethod -Method Post `
  -Uri "$maintenanceUrl/geo/shop-index/retired/cleanup?readersQuiesced=true" `
  -Headers $maintenanceHeaders
$result
```

响应逐项包含 `projection/generation/outcome/unlinkedKeys`。`REMOVED_OR_ABSENT` 表示该目标已删除或本来不存在；`PARTIAL_RETRY_REQUIRED` 表示仍需下一次有界请求；`RETAINED_ACTIVE/RETAINED_METADATA_REFERENCE/RETAINED_UNREADY` 说明为何保留。空列表只表示这批没有到期可回收候选，不证明没有历史代际。失败响应明确已完成删除不回滚；结合逐代际日志与 `projection_generation_cleanup` 审计复核后，再决定是否重试。维护结束关闭开关、保存处置结果，再恢复读取；旧代际删除后不能再用于回滚，应使用保留的代际或重新构建。

发布验收须覆盖默认关闭、未声明排空、零保留期拒绝、最近退役代际保留、活跃/building 引用保护、退役后宽限、分页部分删除续跑、租约丢失和删除响应未知。编译与这些场景的运行证据不由上述源码或配置推出。
