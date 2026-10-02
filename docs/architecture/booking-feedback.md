# 预约行为反馈

行为入口与独立预约事件消费者由 `citycopilot.booking-feedback.enabled` 控制。运行时规则通过登录后的 `GET /api/booking-feedback/rules` 获取，包括原始记录保留天数、特征窗口、缓存秒数和清理间隔；这些值以服务端实际绑定配置为准。

## 曝光与反馈

Core 为一次返回结果生成曝光 ID，随后将实际返回的候选店铺、用户、顺序与排序版本登记到 `search_impression`。签发表示候选已返回，只有收到合法 `VIEW` 才计为浏览，不能用签发数冒充真实可见曝光。报价可先携带曝光 ID；若 Core 登记失败，该报价没有有效曝光归因。

`POST /api/booking-feedback` 接收 `impressionId`、`shopId`、`eventType`。类型只允许 `VIEW`、`CLICK`、`FAVORITE`、`NOT_INTERESTED`，用户从登录态取得。服务端验证曝光的用户、候选和有效期，并从持久化候选顺序得出位置。同一曝光、店铺与类型只保存一次；前端不能自报预约确认或核销。

`DELETE /api/booking-feedback/negative/{shopId}` 清除该店铺的负反馈。清除会标记已有反馈失效并保留其幂等键，旧请求重投与派生特征重建均不能恢复已清除的反馈；在新曝光上的再次选择仍可形成新负反馈。

## 业务事实与派生结果

`RocketMqBookingBehaviorConsumer` 使用独立消费组，仅订阅 Commerce 主题的 `BookingEvent` 标签，复用既有 RocketMQ 客户端配置与 `InboxRepository`。Inbox 登记、业务事件保存、当前状态投影和特征更新在同一个 MySQL 事务提交后才 ACK。

同 `eventId` 的相同消息不会重复计数；不同载荷进入既有 Inbox 隔离记录，并保存原始与新载荷哈希。晚到的低版本真实事件仍写入累计事实，只有 `booking_behavior_projection` 的当前状态按 `aggregateVersion` 防止旧状态覆盖。首次确认和改期使用不同事件类型，改期不重复计为预约确认。

首次有效归因必须匹配服务端曝光的用户、候选店铺和有效时间。未签发、过期、跨用户或跨候选的归因不会进入已归因转化；历史非规范追踪串也按未归因处理，真实业务事实仍保留。已经验证的预约归因跟随业务投影保存，原始曝光清理后可以继续关联后续履约事件。

`user_preference_feature` 从近期事实聚合浏览、点击、收藏、确认、履约、取消、爽约和有效负反馈。受影响的用户与店铺在事件事务内更新，窗口边界过期时重建。Redis 仅缓存该结果，缓存故障回读 MySQL；缓存最大陈旧时间由实际缓存秒数约束。清空派生表后可调用服务端 `rebuild(userId, shopId)` 从保留事实恢复，新用户查询不触发逐候选重建。这里的隐式行为不覆盖 Agent 已有的显式长期偏好。

原始曝光及普通浏览反馈按运行时保留期清理。有效“不感兴趣”保留至用户清除，清除后的幂等记录再按原始保留期删除。业务事件与预约记录采用相同保留边界；当前预约记录没有自动删除策略，因此业务事件也不自动删除。清理任务每轮有界处理，避免一次大事务影响在线预约。

## 可观测与验收

`citycopilot.booking.feedback` 以 `action`、`outcome` 记录签发、合法反馈、重复反馈、无效归因、业务归因及缓存结果。`citycopilot.booking.business.consume` 记录处理、重复、晚到、隔离与失败；其中 `stale` 表示当前状态版本落后，不表示历史事实被丢弃。指标不包含用户或店铺等高基数标签。

空库与升级共同使用 [Core 版本化迁移](../../deploy/mysql/core/)，执行方式见 [数据库迁移运行手册](../database-migrations.md)。[BookingFeedbackIT](../../backend/core/src/test/java/com/citycopilot/core/booking/BookingFeedbackIT.java) 需要显式提供 `CITYCOPILOT_FEEDBACK_TEST_DB_URL`，且数据库名称必须以 `_test` 结尾。测试验证重复与乱序、并发聚合、事务回滚、伪造归因拒绝、负反馈清除重建、窗口保留与迁移可重复执行。Redis 在该数据库测试中使用替身，真实消息代理和缓存链路需要另行验证。

离线排序评估与线上点击、预约、履约转化分开报告。没有真实有效浏览与业务归因数据时，不对 CTR 或转化率提升作结论。
