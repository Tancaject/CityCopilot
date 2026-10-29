# 业务查询与索引边界

索引由所属服务的 Flyway 迁移维护。下面的对应关系来自查询条件和排序结构，不代表生产执行计划或延迟结果。部署索引迁移前按实际表规模安排 DDL 锁、磁盘空间与复制延迟窗口；不要修改已执行的迁移文件。

| 查询 | 对应索引与用途 |
|---|---|
| Core 热门笔记 `ORDER BY liked DESC,id DESC` | [Core V6](../../deploy/mysql/core/V6__note_popularity_index.sql) 的 `(liked,id)` 与排序一致，可反向遍历；作者动态使用 V5 的 `(user_id,create_time,id)`，两条访问路径不同。 |
| Commerce 库存恢复 `WHERE source_type='STATE_TRANSITION' AND phase=? ORDER BY created_at LIMIT 100` | [Commerce V9](../../deploy/mysql/commerce/V9__commerce_read_path_indexes.sql) 的 `commerce_order_recovery(source_type,phase,created_at)` 对应 [StreamWorker](../../backend/commerce-service/src/main/java/com/citycopilot/commerce/CommerceOrderStreamWorker.java) 的恢复批次；既有 `(phase,claimed_until)` 仍用于按 claim 期限访问，保留。 |
| Commerce 个人预约 `WHERE user_id=? ORDER BY created_at DESC,id DESC LIMIT 20 OFFSET ?` | [Commerce V9](../../deploy/mysql/commerce/V9__commerce_read_path_indexes.sql) 的 `commerce_booking(user_id,created_at)` 对应 [BookingService](../../backend/commerce-service/src/main/java/com/citycopilot/commerce/booking/BookingService.java) 的列表；InnoDB 二级索引隐含主键 `id`。V4 的 `(user_id,status,start_at)` 服务于另一种访问形态，不能直接承接创建时间排序。 |
| Commerce 库存对账 `commerce_reservation WHERE voucher_id=? AND status…` | [Commerce V15](../../deploy/mysql/commerce/V15__reservation_voucher_index.sql) 的 `commerce_reservation(voucher_id,status)` 对应 [CommerceConsistencyService](../../backend/commerce-service/src/main/java/com/citycopilot/commerce/CommerceConsistencyService.java) 的买家比对与未结预约查询；此前每张进行中的券每轮都全表扫描预约表。 |
| Commerce 订单过期 `WHERE status=1 AND pay_deadline<=… AND voucher_id NOT IN (…) ORDER BY pay_deadline,id LIMIT 1` | [Commerce V16](../../deploy/mysql/commerce/V16__order_expiry_voucher_index.sql) 把 `idx_commerce_order_expiry` 改为 `(status,pay_deadline,id,voucher_id)`：`id` 紧跟 `pay_deadline` 保持排序顺序，`voucher_id` 在索引内排除库存维护中的券。若把 `voucher_id` 放在 `id` 之前，排序退化为全表扫描加 filesort。 |
| Commerce Outbox 投递取数（每秒） | READY/FAILED 按 `next_attempt_at`、过期 CLAIMED 按 `claimed_until`，各走既有 `idx_commerce_outbox_ready` / `idx_commerce_outbox_claim` 取 50 条，再按 `created_at` 合并取 50 条。READY 行插入时两列取同一时间，顺序与按创建时间一致。合写成一条 `OR … ORDER BY created_at` 时，积压一大优化器改走全表扫描加排序（bigdata 108 万行：16.2 s → 10 ms，取出的 50 条完全相同）。 |
| Commerce Outbox 最老未发布事件指标 | [Commerce V17](../../deploy/mysql/commerce/V17__outbox_status_created_index.sql) 的 `(status,created_at)`：按状态分别取 `MIN(created_at)` 再取最小，每个子查询只读一行；`status IN (…)` 一次取最小值要读遍全部未发布行（bigdata 慢查询日志：平均每次扫描 33 万行、耗时 4.4 s）。 |
| Commerce 我的预约券 `WHERE c.user_id=? ORDER BY c.created_at DESC LIMIT 100` | [Commerce V9](../../deploy/mysql/commerce/V9__commerce_read_path_indexes.sql) 的 `commerce_user_booking_coupon(user_id,created_at)` 对应 [BookingPricingService](../../backend/commerce-service/src/main/java/com/citycopilot/commerce/pricing/BookingPricingService.java) 的券列表，规则表仍按主键连接。指定店铺时的 `r.shop_id` 属于连接表过滤，不因此宣称扫描量已限定为 100 行。 |

Commerce V9 只增加非唯一索引，不更改表中业务数据、查询 SQL 或原有约束；沿用[数据库迁移运行手册](../database-migrations.md)的 Commerce 迁移入口。应用回滚时保留这些增量索引，若需移除则另发迁移，不回写 V9 或修改 Flyway 历史。新增索引会占用空间，并随插入及相关键值变更维护；执行计划、DDL 影响和写入成本仍需结合目标库确认。

热门索引的代价是点赞数变更时同时维护排序索引；不再额外建立仅 `liked` 的重复索引。页码深翻仍有 OFFSET 成本，该索引不把旧页码接口变成游标接口。旧 API 的分页、过滤和返回字段保持原契约；引入新的游标接口须同时定义调用方协议，不能静默截断原有完整列表。

后台全量任务使用固定起始最大主键与有界页面：Commerce 初始化/巡检每页处理一批，Core 搜索与 GEO 基础重建逐页写入待发布代际，并以 Outbox 追尾覆盖扫描期间的更新。分页只限制单批读取和写入量，不代表整轮总耗时有上界；原租约、fencing、切换屏障与逐券处理语义继续生效。

`LIKE '%关键词%'` 不因增加普通 B-tree 索引获得前缀定位能力；没有改变关键词匹配语义或另外加入全文引擎。删除订单历史兼容查询前，仍须满足[交易迁移与切换协议](../architecture/commerce-migration-and-cutover.md)，不能仅凭当前代码已采用 Commerce 就推断生产回填已经完成。
