# 数据库迁移运行手册

Core、Commerce 的业务 DDL 只维护在各自的 Flyway 目录。空库与已有库升级使用同一组版本化 SQL，每个数据库独立保存 `flyway_schema_history`。应用启动不执行 DDL，MySQL 镜像初始化只负责创建数据库和账号。

| 数据库 | 迁移唯一来源 | Compose 任务 |
|---|---|---|
| Core | [deploy/mysql/core](../deploy/mysql/core/) | `core-migrate` |
| Commerce | [deploy/mysql/commerce](../deploy/mysql/commerce/) | `commerce-migrate` |

镜像、连接和执行选项以 [compose.yaml](../compose.yaml) 为准。任务固定关闭 `clean` 和 `baselineOnMigrate`；已有库只允许在显式检查后采用版本 `1`。不要把业务 SQL 挂回 `docker-entrypoint-initdb.d`，也不要手工按文件顺序执行版本化 SQL。

## 新库与日常升级

在仓库根目录执行：

```powershell
.\tools\citycopilot.ps1 start
```

脚本创建缺失的本地 `.env`，启动数据库等基础设施，等待数据库可连接，再依次执行两个迁移任务。迁移成功后才能从 IDE 启动应用。`restart` 同样等待数据库就绪并执行迁移；已有数据库不需要删除数据卷。

只启动数据库并执行迁移：

```powershell
.\tools\citycopilot.ps1 migrate
```

每次脚本执行都会重新创建迁移任务，Flyway 校验已执行文件的校验和，只运行尚未记录的新版本。任务失败即停止后续步骤，失败容器保留日志。重复运行 `migrate` 不会重新执行已经成功记录的版本。

已有 `.env` 时，`docker compose up -d` 也会启动迁移任务；后台命令返回不代表迁移已经成功，应查看任务状态。完整容器应用使用 `docker compose --profile app up -d --build --wait`，应用通过 `service_completed_successfully` 等待对应迁移任务。数据库健康检查只验证连接，不承担 schema 完整性判断。

## 已有库首次接管

没有 `flyway_schema_history` 的非空库会被 `migrate` 拒绝。这是接管门禁，不应通过开启 `baselineOnMigrate`、删除表或重置数据卷绕过。

1. 确认连接指向目标数据库，备份并保留可恢复副本；停止该数据库对应的写入进程。Commerce 还必须完成下节的消息排空步骤。
2. 核对数据库属于本仓库支持的历史结构。各目录的 `beforeBaseline.sql` 会检查必需的表、列及关键约束；缺失或不兼容结构会使接管失败。回调不会修复结构，也不替代对额外表、索引、业务数据的人工核对。
3. 仅对已确认没有迁移历史的那个数据库，显式执行下表中的对应命令。不要对两个库不加区分地批量 baseline。

| 接管对象 | 显式命令 |
|---|---|
| Core | `docker compose run --rm core-migrate -baselineVersion=1 baseline` |
| Commerce | `docker compose run --rm commerce-migrate -baselineVersion=1 baseline` |

`baseline` 先运行 `beforeBaseline.sql`；成功后仅登记版本 `1`，不执行 V1，也不表示后续升级已经完成。必须检查命令退出码为 `0`，再运行对应任务，例如 Commerce：

```powershell
docker compose run --rm commerce-migrate migrate
if ($LASTEXITCODE -ne 0) { throw 'Commerce 迁移失败，请保留输出并停止发布。' }
docker compose run --rm commerce-migrate validate
if ($LASTEXITCODE -ne 0) { throw 'Commerce 迁移历史校验失败。' }
docker compose run --rm commerce-migrate info
```

Core 使用 `core-migrate` 任务名。确认各服务迁移成功、业务核对通过后，才恢复相应进程。未知结构应先定位差异并制定独立的数据迁移方案，不能提高 baseline 版本、移除 callback 或改写迁移历史强行接管。


### Commerce 首次接管的业务门禁

数据库结构检查无法证明 Redis 中没有在途请求。首次建立 admission ledger 前，必须先停止新秒杀准入和其他交易写入，保留旧 Stream worker，等待全部旧 Stream、PEL 和正在处理的消息归零，再停止旧 worker，执行 baseline 和 migrate。

迁移后应核对历史订单与 ledger 身份、金额和库存，替换旧 API/worker 后再开放请求。这一过程不能与不写 ledger 的旧版本混合滚动；Flyway 的结构版本不会替代消息排空或业务对账。详细业务核对要求统一维护在 [Commerce 所有权与运行门禁](architecture/commerce-migration-and-cutover.md#首次-admission-ledger-升级)。

## Commerce V11：取消后重新购买

V11 将“一人一券”约束改为仅约束未取消订单，并释放已取消订单的 Reservation。升级前停掉交易写入并排空 Stream；不要让新旧交易代码同时运行。历史幂等键、订单和 Outbox 保留，V12 为历史已支付订单补录支付事实，不会重新发起扣款。

旧取消流程可能在 Redis 留有买家标记。升级前记录 `SELECT DISTINCT voucher_id FROM commerce_order WHERE status=4` 的结果；升级后保持这些券停止准入，通过现有 `/admin/seckill/consistency/{voucherId}` 读取证据，出现买家偏差时携带返回的 `evidenceHash` 调用其 `/repair` 接口，再通过 `/verify` 确认一致后开放。不要直接清空买家集合或库存键。接口及修复权限见 [Commerce 所有权与运行门禁](architecture/commerce-migration-and-cutover.md)。

## 可选开发样例数据

[Core seed](../deploy/mysql/seed/core.sql) 和 [Commerce seed](../deploy/mysql/seed/commerce.sql) 不属于 schema 迁移，启动和升级都不会自动导入。仅在**新建、迁移完成、尚无业务数据的开发库**中显式执行；不要对已有开发库或生产库重放，不要用 seed 修补迁移失败。

需要样例时，在启动业务应用前逐库导入。以下命令复用容器内已有凭据，不把口令写入命令行：

```powershell
docker compose cp deploy/mysql/seed/core.sql mysql:/tmp/citycopilot-core-seed.sql
if ($LASTEXITCODE -ne 0) { throw 'Core seed 复制失败。' }
docker compose exec -T mysql sh -ec 'MYSQL_PWD="$MYSQL_PASSWORD" mysql --default-character-set=utf8mb4 -u"$MYSQL_USER" "$MYSQL_DATABASE" < /tmp/citycopilot-core-seed.sql'
if ($LASTEXITCODE -ne 0) { throw 'Core seed 导入失败，请检查目标库，不要重复导入。' }

docker compose cp deploy/mysql/seed/commerce.sql commerce-mysql:/tmp/citycopilot-commerce-seed.sql
if ($LASTEXITCODE -ne 0) { throw 'Commerce seed 复制失败。' }
docker compose exec -T commerce-mysql sh -ec 'MYSQL_PWD="$MYSQL_PASSWORD" mysql --default-character-set=utf8mb4 -u"$MYSQL_USER" "$MYSQL_DATABASE" < /tmp/citycopilot-commerce-seed.sql'
if ($LASTEXITCODE -ne 0) { throw 'Commerce seed 导入失败，请检查目标库，不要重复导入。' }
```

样例数据不代表实际商家归属或可预约排期；演示需要的商家、资源和排期仍通过业务入口配置。

## 专用测试数据库

测试和负载库也运行同一迁移目录，不能另抄一份 schema。使用 Compose 任务连接专用数据库时，先在进程环境设置容器可达的 `FLYWAY_URL`、`FLYWAY_USER` 和 `FLYWAY_PASSWORD`，确认地址及库名后显式传入：

```powershell
docker compose run --rm -e FLYWAY_URL -e FLYWAY_USER -e FLYWAY_PASSWORD commerce-migrate migrate
if ($LASTEXITCODE -ne 0) { throw '专用 Commerce 数据库迁移失败。' }
```

不传入这三个覆盖值的任务仍连接 Compose 的日常数据库。测试必须使用独立数据库和身份；负载工具对 loopback 和 `_load_test` 库名的限制继续适用。

### 迁移专项检查

[verify-booking-migrations.ps1](../tools/migrations/verify-booking-migrations.ps1) 调用 [DatabaseMigrationsIT](../backend/core/src/test/java/com/citycopilot/core/migration/DatabaseMigrationsIT.java)，只验证数据库迁移流程，不启动应用或执行负载测试。先准备独立的本地 MySQL 测试服务器及具有建库、删库权限的测试账号，通过 `CITYCOPILOT_MIGRATION_TEST_USER`、`CITYCOPILOT_MIGRATION_TEST_PASSWORD` 传入凭据。

```powershell
$env:CITYCOPILOT_MIGRATION_TEST_URL = 'jdbc:mysql://127.0.0.1:33319/'
.\tools\migrations\verify-booking-migrations.ps1
```

URL 只允许 `localhost` 或 `127.0.0.1`，必须包含显式端口和结尾 `/`，不接受数据库名或查询参数。检查创建随机命名的 `cc_migration_` 数据库，并在结束时删除这些测试库；不要指向承载日常数据的 MySQL 服务器。覆盖内容以测试代码为准，包括空库、显式接管、重复迁移、样例导入、校验和及不支持结构的拒绝。

[CI 的 database-migrations 任务](../.github/workflows/ci.yml) 在独立 MySQL 服务中运行同一专项检查，镜像与 Compose 保持一致。该任务与完整应用拓扑的 `compose-smoke` 分开，迁移检查通过不代表应用或消息链路验收完成。

## 失败处置与后续变更

脚本失败时会给出具体任务名和日志命令，例如：

```powershell
.\tools\citycopilot.ps1 logs -Service commerce-migrate
docker compose run --rm commerce-migrate info
```

显式 `run --rm` 的输出需在执行时保留；它结束后没有可供 `logs` 读取的任务容器。按错误定位数据库连接、`beforeBaseline` 结构检查、具体迁移 SQL 或校验和差异，再决定处置。MySQL DDL 可能已经部分提交，不能把失败退出等同于自动回滚；应先核对实际结构和迁移历史。

- 不自动 baseline，不执行 `clean`，不使用 `repair` 或直接修改 `flyway_schema_history` 抹平错误。
- 已执行的 `V*.sql` 不再修改；后续结构变化新增版本，按 expand/contract 发布。
- 校验和变化先恢复与已发布制品一致的迁移文件；部分 DDL 失败须在备份和实际结构核对后制定专项恢复步骤。
- 应用回滚不自动回滚数据库。旧制品必须兼容扩展后的 schema；破坏性收缩另行发布。
