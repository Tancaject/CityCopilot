# Commerce 迁移、切换与回滚

## 选择

当前实现采用“维护窗口全量回填 + 单一写入者切换”，不宣称已经具备通用在线 CDC。原因是旧应用的所有交易 mutation 尚未形成覆盖完整、单调版本化的 change feed；在此条件下应用层双写会制造不可证明的一致性。

切换前由旧 Core 保持唯一写入者。完成队列排空、全量回填和核对后，先 fence 旧 Stream Worker、旧 reconciliation/relay，再启用 Commerce writer。Core 仅通过版本化 Commerce Port 转发。切换后 Commerce 是交易表、交易 Outbox、Reservation、DLQ 和 `commerce:*` Redis key 的唯一 owner。

## 核对门禁

`tools/migrations/verify-commerce-cutover.ps1` 比较：

- 迁移行数；
- 优惠券与订单主键最小/最大值；
- 优惠券面值和实付金额汇总；
- 关键字段 CRC32 抽样汇总；
- 库存汇总、Reservation 数和未发布 Outbox。

`tools/migrations/commerce-cutover-gate.ps1 -Action Preflight` 进一步要求：

- 未发布/认领/失败 Outbox 为 0；
- `RESERVED`/`RELEASE_REQUIRED` Reservation 为 0；
- 未重放 DLQ 为 0；
- 旧 Redis Stream pending 为 0。

## 所有权与顺序

- 同一时刻只能存在一个 writer 和一个 relay owner。
- 业务事务先提交订单/Reservation/Outbox，Relay 再用 claim token、lease 和 fencing token 发布。
- 同一 aggregate 的 `aggregateVersion` 单调递增；Inbox 用 `(consumer_group,event_id)` 去重，同 eventId 不同 payload 进入 quarantine。
- 切换点之前的 Outbox 必须由旧 owner 发布或显式迁移；切换点之后只允许 Commerce Relay 发布。
- Redis pending 由创建该 Stream 消息的旧 worker 排空；不把 pending 记录伪装成新服务事件。

## 回滚

切换后已有新订单时禁止直接把写流量切回旧 Core。安全回滚流程是：

1. 关闭新交易准入，保留 Place/Community 和历史快照查询；
2. 等待 Commerce Outbox、Reservation、Stream pending 排空；
3. 导出切换点后的 Commerce mutation，反向同步到旧 schema；
4. 执行同一套行数、范围、金额、库存与 hash 核对；
5. 运行 `commerce-cutover-gate.ps1 -Action Rollback -Force`；
6. 在维护窗口切换单一 writer；Commerce 数据保留只读，禁止删除审计记录。

本地回滚演练使用服务隔离与恢复，不删除卷，也不把已有 Commerce 新订单写回过期旧表。
