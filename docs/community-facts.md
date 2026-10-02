# 社交事实与旧点赞数据切换

Core 的社交事实保存在 MySQL：`note_like` 保存谁在何时点赞，`user_follow` 保存有方向的关注关系。`note.liked` 是与点赞关系同一事务维护的计数；它不是可独立修改的点赞事实。`note_like.liked_at` 使用 Unix 毫秒，保留旧 Redis ZSET 的点赞时间；删除笔记会通过外键删除它的点赞关系。

Feed 查询当前关注作者的全部笔记，按发布时间倒序、笔记 ID 倒序排列。因此新关注后可看到历史笔记，取关后这些笔记不再出现在后续查询中。旧 `feed:*` 投递记录不参与新 Feed；原有 `lastId`（毫秒）和 `offset` 分页参数继续使用。Redis 的 `blog:liked:*`、`follow:*`、`feed:*` 均不再是新版本的社交事实来源。

## 新数据库

通过[数据库迁移说明](database-migrations.md)执行 Core Flyway 迁移。V5 创建 `note_like`、索引和 `community_facts_cutover` 单例标记；同一关注关系若存在多条物理记录，仅保留最小 `id` 的记录。仅当 `note` 表为空时，V5 直接标记 `ready=true`；此后导入的开发样例从 `0` 赞开始，不虚构点赞用户。

V5 不从 Redis 读数据，也不会清零已有计数。已有任何笔记时均标记为 `ready=false`，即使所有计数都是 `0`，也必须核对 Redis 原集合。Core 启动检查会拒绝接收业务流量，提示先完成以下切换。不要通过手工更新 `ready` 绕过校验。

## 已有数据库的停写切换

1. 停止所有旧 Core 实例以及直接写社交表/Redis 的脚本；浏览器、Agent、MCP 和管理端都必须停止写入。保存对应时间点的 MySQL 备份和 Redis 持久化备份。停写须持续到导入完成、新 Core 上线；导入器的 MySQL 锁只排斥并发导入器，不能阻止旧应用写 Redis。
2. 按[数据库迁移说明](database-migrations.md)迁移已有 Core 数据库到最新版本。检查 `community_facts_cutover` 的单例行；`ready=false` 时继续下面的导入。不要删除旧 Redis 键。
3. 构建包含导入器的 Core 可执行 JAR。将连接值显式传给当前进程环境，值必须与被停写的旧 Core 实际连接一致。导入器不会加载 `.env`、Spring 配置或默认项目数据库。

   | 环境变量 | 含义 |
   |---|---|
   | `CITYCOPILOT_DB_URL` | 必填，包含 Core 数据库名的完整 JDBC URL |
   | `CITYCOPILOT_DB_USERNAME` / `CITYCOPILOT_DB_PASSWORD` | 与 Core 相同；未设置时分别为 `root`、空密码 |
   | `CITYCOPILOT_REDIS_HOST` / `CITYCOPILOT_REDIS_PORT` | 必填，旧 Core 使用的 Redis 地址和端口 |
   | `CITYCOPILOT_REDIS_PASSWORD` | 未设置时为空 |
   | `CITYCOPILOT_REDIS_DATABASE` | 未设置时为 `0`，必须核对旧 Core 实际使用的逻辑库 |

   导入器使用 Core 现有的 Lettuce 和 JDBC 依赖，支持当前 Core 的独立 Redis 配置；检测到 Redis Cluster 会拒绝，不能把单个分片的扫描当作完整来源。凭据通过受控环境注入，不放入文档、命令历史或报告。

4. 从项目根目录运行只读检查；省略最后的 `--check` 也是只读检查。PowerShell 示例：

   ```powershell
   java '-Dloader.main=com.citycopilot.core.community.LegacyCommunityLikesMigration' -cp backend/core/target/core-1.0-SNAPSHOT-exec.jar org.springframework.boot.loader.launch.PropertiesLauncher --check
   ```

   此入口直接运行独立 `main`，不会启动 Core Web 服务。[Spring Boot PropertiesLauncher](https://docs.spring.io/spring-boot/specification/executable-jar/property-launcher.html)支持通过 `loader.main` 指定入口并加载可执行 JAR 中的依赖。可先将最后一个参数换为 `--help`，检查 JAR 入口是否可用；`--help` 不连接数据库或 Redis。

5. `CHECK_OK` 表示当前停止写入的数据可导入。确认停写仍然有效，然后执行写入：

   ```powershell
   java '-Dloader.main=com.citycopilot.core.community.LegacyCommunityLikesMigration' -cp backend/core/target/core-1.0-SNAPSHOT-exec.jar org.springframework.boot.loader.launch.PropertiesLauncher --apply
   ```

   导入器首先完整预检，再以最多 `500` 条关系一批提交，最后再次完整检查，并以单条 SQL 校验全部计数、原子设置 `ready=true`。默认检查不写表；`--apply` 不改原 `note.liked`，不覆盖已有关系的时间，不删除 Redis。任何中断都会保留已成功提交的批次；保持停写、解决原因后重跑相同命令即可。只有最终完整校验通过才发布切换标记。
6. `APPLY_OK ... ready=true` 后启动新 Core。`ALREADY_READY` 表示已经完成切换，导入器不会再次从旧 Redis 覆盖新 MySQL。上线后保留旧备份和 Redis 键至既定保留期结束。新版本已接受写入后，旧 Redis 已不再更新；回滚旧二进制前须停写并制定反向数据迁移，不能直接启动旧版本。

## 校验拒绝时

每篇笔记必须满足 `Redis ZCARD = 原 note.liked`；每个成员必须是存在于 `user_account` 的正整数用户 ID，分数必须是有效的过去 Unix 毫秒时间。导入器还扫描全部 `blog:liked:*`，拒绝不存在对应笔记的键，并检查已有目标关系是否属于同一个源数据集、时间是否一致。

计数不一致、Redis 键丢失、孤立成员、未来时间或已有目标冲突都会阻止切换，错误会指出对应笔记/用户及下一步。应从停写备份恢复或核实数据来源，然后重跑检查。Redis 无法提供的用户身份不能从一个总数还原；不得为了通过检查而清零计数、补造用户或直接置 `ready=true`。旧开发库若含有历史样例的虚构计数，同样会被拒绝：保留原库，通过迁移和新版 seed 创建独立空开发库，不对现有数据做隐式抹除。

## 专项验证

`LegacyCommunityLikesMigrationIT` 仅连接显式指定的本机临时 MySQL 服务，创建并删除随机命名的测试数据库，Redis 使用固定夹具；不使用项目数据库。它覆盖只读预检、可重复导入、部分导入续跑、计数/身份/时间冲突拒绝、启动门禁与 V5 关注唯一约束。配置方式与 `DatabaseMigrationsIT` 的 `CITYCOPILOT_MIGRATION_TEST_URL` 相同。
