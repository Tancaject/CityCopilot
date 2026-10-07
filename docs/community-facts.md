# 社交事实

Core 的社交事实保存在 MySQL：`note_like` 保存谁在何时点赞，`user_follow` 保存有方向的关注关系。`note.liked` 是与点赞关系同一事务维护的计数；它不是可独立修改的点赞事实。`note_like.liked_at` 使用 Unix 毫秒，保留旧 Redis ZSET 的点赞时间；删除笔记会通过外键删除它的点赞关系。

Feed 查询当前关注作者的全部笔记，按发布时间倒序、笔记 ID 倒序排列。因此新关注后可看到历史笔记，取关后这些笔记不再出现在后续查询中。旧 `feed:*` 投递记录不参与新 Feed；原有 `lastId`（毫秒）和 `offset` 分页参数继续使用。Redis 的 `blog:liked:*`、`follow:*`、`feed:*` 均不再是新版本的社交事实来源。

## 新数据库

通过[数据库迁移说明](database-migrations.md)执行 Core Flyway 迁移。V5 创建 `note_like`、索引和 `community_facts_cutover` 单例标记；同一关注关系若存在多条物理记录，仅保留最小 `id` 的记录。仅当 `note` 表为空时，V5 直接标记 `ready=true`；此后导入的开发样例从 `0` 赞开始，不虚构点赞用户。

V5 不从 Redis 读数据，也不会清零已有计数。旧版 Redis 点赞关系的离线导入工具和启动门禁已于 2026-10 移除，`community_facts_cutover` 标记不再被读取；从更早版本升级的库，其旧点赞关系不会迁入 `note_like`。
