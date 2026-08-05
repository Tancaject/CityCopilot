# ADR-0010：Shop Search 与 GEO 版本化投影

状态：接受（已实现当前基线，尚未完成生产验证）
日期：2026-08-04

## 背景

店铺写入由 Core MySQL 提供权威事实，Redis GEO 和 Elasticsearch 提供高性能查询。事务 afterCommit 的尽力写入、JVM `synchronized`、进程内 `AtomicBoolean` 和每副本定时全量重建无法保证多 Core 实例下不丢更新，也无法阻止旧快照覆盖新投影。

## 决策

- Core MySQL 是店铺、分类、坐标和版本的唯一事实源；ES、Redis GEO、缓存和 Commerce 店铺摘要均为可重建投影。
- 经 `ShopService` 的店铺变更在同一事务中递增 `projection_version` 并写入 MySQL `shop_projection_outbox`。
- Search Projector 直接从该 MySQL Outbox claim 事件；可过期 claim 与共享 MySQL writer lease/fencing token 协调多个实例，并与全量重建互斥。当前链路没有再经 RocketMQ，也不虚构未实现的 Consumer Inbox。
- ES 文档使用 `external_gte` 版本写入；Redis GEO 用 Lua 比较投影版本。失败事件永久保持可重试并采用有上限的指数退避，旧版本不能覆盖新状态。
- 全量重建使用带 fencing token 的单活租约、隔离 generation、封闭起始水位、预追平和 cutover barrier 内的最终尾追；最终尾追完成后才切换 ES alias 或 Redis GEO active generation。
- API 实例在本地发现共享 alias 异常后按退避探测共享 readiness；其他实例完成修复后可以自行恢复，不会永久固定在降级路径。
- 查询可以使用完整 ES 展示投影减少回表；下单、权限、库存和店铺可用性必须由 Core/Commerce 再次权威校验。
- ES 故障时回退到 MySQL/Redis GEO，并记录 fallback rate 和 projection lag。
- Agent 的显式偏好只作为 ES/查询排序的 soft boost，不作为硬过滤，也不改变事实数据。

## 被否决的方案

- 应用事务提交后直接双写 ES/GEO：业务提交与投影写没有原子性，失败会永久丢更新。
- JVM 本地锁或布尔状态：不能协调多个实例。
- 每个 Core 副本启动或定时执行全量重建：会并发覆盖和错误切换 generation。
- 把 ES 作为业务事实源：索引生命周期、mapping 变更和故障恢复不适合承担订单前权威校验。
- 为搜索单独创建拥有店铺事实的业务服务：会复制写入所有权并增加跨服务一致性问题。

## 结果

Search/GEO 可以独立扩缩、故障降级和从事实源重建，重复或乱序事件不会让旧版本覆盖新状态。代价是 Core 需要 Shop Outbox，平台需要 Projector、generation 元数据、高水位和重建监控。

## 当前实现边界

- 只保证所有经 `ShopService` 并写入 `shop_projection_outbox` 的店铺新增/更新；店铺删除 tombstone 尚未实现，Projector 会保留事件重试并拒绝静默切换。
- 独立修改 `shop_type` 不会自动为其关联店铺生成相同水位协议下的事件。
- 当前全量基线仍一次读取店铺列表后分批写入 ES；在大数据量上线前必须改成主键游标分页，并补充 generation 清理策略。
- 当前 MySQL Outbox 是投影内部队列。若未来需要把 `ShopChanged` 分发给 Commerce 等跨服务消费者，再增加 RocketMQ Relay/Inbox，而不是把它描述成已经存在。
- 以上结论来自代码和单元级验证；真实 MySQL、Redis Cluster、Elasticsearch 多实例切换与故障注入仍是发布门槛。
