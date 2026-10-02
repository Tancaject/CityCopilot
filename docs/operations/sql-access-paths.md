# 业务查询与索引边界

索引由所属服务的 Flyway 迁移维护。下面的对应关系来自查询条件和排序结构，不代表生产执行计划或延迟结果。部署索引迁移前按实际表规模安排 DDL 锁、磁盘空间与复制延迟窗口；不要修改已执行的迁移文件。

| 查询 | 对应索引与用途 |
|---|---|
| Core 热门笔记 `ORDER BY liked DESC,id DESC` | [Core V6](../../deploy/mysql/core/V6__note_popularity_index.sql) 的 `(liked,id)` 与排序一致，可反向遍历；作者动态使用 V5 的 `(user_id,create_time,id)`，两条访问路径不同。 |
| Agent 最近 Case `ORDER BY created_at DESC LIMIT ?` | [Agent V5](../../deploy/mysql/agent/V5__ops_case_list_index.sql) 的 `(created_at)`；状态前缀索引不能直接覆盖不筛选状态的最近列表。 |
| Agent 自动积压、人工待处置与近期成功 Case | [Agent V3](../../deploy/mysql/agent/V3__ops_case_observation_indexes.sql) 分别按 `(status,created_at)` 和 `(status,resolved_at,created_at)` 支持后台采样。 |
| Commerce 库存恢复 `WHERE source_type='STATE_TRANSITION' AND phase=? ORDER BY created_at LIMIT 100` | [Commerce V9](../../deploy/mysql/commerce/V9__commerce_read_path_indexes.sql) 的 `commerce_order_recovery(source_type,phase,created_at)` 对应 [StreamWorker](../../backend/commerce-service/src/main/java/com/citycopilot/commerce/CommerceOrderStreamWorker.java) 的恢复批次；既有 `(phase,claimed_until)` 仍用于按 claim 期限访问，保留。 |
| Commerce 个人预约 `WHERE user_id=? ORDER BY created_at DESC,id DESC LIMIT 20 OFFSET ?` | [Commerce V9](../../deploy/mysql/commerce/V9__commerce_read_path_indexes.sql) 的 `commerce_booking(user_id,created_at)` 对应 [BookingService](../../backend/commerce-service/src/main/java/com/citycopilot/commerce/booking/BookingService.java) 的列表；InnoDB 二级索引隐含主键 `id`。V4 的 `(user_id,status,start_at)` 服务于另一种访问形态，不能直接承接创建时间排序。 |
| Commerce 我的预约券 `WHERE c.user_id=? ORDER BY c.created_at DESC LIMIT 100` | [Commerce V9](../../deploy/mysql/commerce/V9__commerce_read_path_indexes.sql) 的 `commerce_user_booking_coupon(user_id,created_at)` 对应 [BookingPricingService](../../backend/commerce-service/src/main/java/com/citycopilot/commerce/pricing/BookingPricingService.java) 的券列表，规则表仍按主键连接。指定店铺时的 `r.shop_id` 属于连接表过滤，不因此宣称扫描量已限定为 100 行。 |

Commerce V9 只增加非唯一索引，不更改表中业务数据、查询 SQL 或原有约束；沿用[数据库迁移运行手册](../database-migrations.md)的 Commerce 迁移入口。应用回滚时保留这些增量索引，若需移除则另发迁移，不回写 V9 或修改 Flyway 历史。新增索引会占用空间，并随插入及相关键值变更维护；执行计划、DDL 影响和写入成本仍需结合目标库确认。

热门索引的代价是点赞数变更时同时维护排序索引；不再额外建立仅 `liked` 的重复索引。页码深翻仍有 OFFSET 成本，该索引不把旧页码接口变成游标接口。旧 API 的分页、过滤和返回字段保持原契约；引入新的游标接口须同时定义调用方协议，不能静默截断原有完整列表。

后台全量任务使用固定起始最大主键与有界页面：Commerce 初始化/巡检每页处理一批，Core 搜索与 GEO 基础重建逐页写入待发布代际，并以 Outbox 追尾覆盖扫描期间的更新。分页只限制单批读取和写入量，不代表整轮总耗时有上界；原租约、fencing、切换屏障与逐券处理语义继续生效。

`LIKE '%关键词%'` 不因增加普通 B-tree 索引获得前缀定位能力；没有改变关键词匹配语义或另外加入全文引擎。删除订单历史兼容查询前，仍须满足[交易迁移与切换协议](../architecture/commerce-migration-and-cutover.md)，不能仅凭当前代码已采用 Commerce 就推断生产回填已经完成。
