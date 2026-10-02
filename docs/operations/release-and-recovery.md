# 发布制品与恢复边界

发布单位是同一份审核记录约束的后端制品、迁移 SQL 和部署配置。开发入口 [citycopilot.ps1](../../tools/citycopilot.ps1) 会维护本地环境并执行迁移，Compose 的 `:local` 与 `--build` 会重新构建代码；它们不提供跨环境晋级的制品身份。发布环境应从制品库取得已审核的固定文件或镜像摘要，启动时不重新编译。

## 固定后端发布包

[release_bundle.py](../../tools/release/release_bundle.py) 使用 Python 3.11 及以上版本，无第三方依赖。`create` 只复制已有的四个 `*-exec.jar`、三库规范迁移目录以及构建/部署参考文件，不构建、不连接存储，也不启动进程。Frontend 和基础设施镜像不在该后端包的覆盖范围内。

先在同一审核版本完成打包及本次改动所需的定向检查，再在没有并行构建的目录执行：

```powershell
python tools/release/release_bundle.py create `
  --release-id '<发布编号>' --output '<全新发布目录>'
if ($LASTEXITCODE -ne 0) { throw '发布包未生成，请检查输入制品。' }
```

输出 JSON 包含 `manifestSha256`。将该值与 CI 构建记录、源码版本、定向验证结果、审批人一起保存到**独立且受权限保护的发布记录**；制品库应禁止覆盖已发布目录。接收端使用从该记录取得的值：

```powershell
python tools/release/release_bundle.py verify `
  --bundle '<发布目录>' --manifest-sha256 '<审核记录中的 SHA-256>'
if ($LASTEXITCODE -ne 0) { throw '制品身份校验失败，停止发布。' }
```

校验会拒绝文件修改、缺失、额外 SQL、重复条目、越界路径及错误服务 JAR。程序不会覆盖已存在的发布目录。不要从接收到的 `manifest.json` 临时计算一个新摘要再当作审核值；摘要可以验证完整性，不能单独证明发布者身份。

`checkout.commit` 和 `checkout.dirty` 仅记录封包时观察到的工作区，不能证明已有 JAR 来自它。`jarProvenanceVerified` 因而固定为 `false`；CI 的同次构建、封包与上传记录提供可追溯的来源，仍依赖仓库、工作流和运行器的权限边界，不等于签名认证或 SLSA 证明。`--allow-dirty` 只用于明确标记本地候选包，不能替代 CI 来源记录。包中 `reference/compose.yaml` 和 Dockerfile 是参考快照，**不是独立可执行的部署目录**；运行用配置与密钥由目标环境管理，不从开发 `.env` 复制。

### CI 候选制品来源

[CI 的 backend job](../../.github/workflows/ci.yml) 在同一工作区的 `mvn clean verify` 成功后，直接调用上述工具封包，随后上传 `backend-bundle-<runId>-<runAttempt>`；不会为封包再次构建。输出放在 runner 临时目录，封包不使用 `--allow-dirty`。四个 JAR 与规范迁移集共享一份清单，其他 CI job 不会向该包混入制品。

包外的 `backend-release-record-<runId>-<runAttempt>` 单独保存 JSON，包含 `manifestSha256`、实际 `checkoutCommit`、事件 SHA/ref、workflow ref、run/attempt URL，以及制品上传 ID、URL 和归档摘要；运行摘要也展示清单 SHA 与来源链接。制品归档摘要和 `manifestSha256` 分别标识上传归档与包内清单，不能互换。Pull request 构建通常检出合并提交，应以记录中的实际 `checkoutCommit` 识别代码，不能当作 PR 分支头提交。

该产物仍是后端候选包：上传成功不表示并行的迁移、契约、前端或 Compose job 已通过。晋级前应核对指定 run/attempt 的全部必要结果、四个服务与迁移清单，并将外部记录复制到受权限保护的发布记录；不要只信任随下载目录携带的摘要。GitHub artifact 保留期到期前，应把选定候选包及来源记录保存到长期制品库，保持不可覆盖。CI 工作流接入的验收须覆盖同次构建输入、上传与下载后的清单校验、独立记录对应关系和失败时停止封包；这不替代目标环境的发布与恢复验收。

直接运行 JAR 时，由服务管理器引用 `applications/<service>.jar` 的固定路径，使用环境持有的配置与密钥。升级和回退均重新核验清单，不从 `backend/*/target` 的可变路径启动。

## 镜像与实际 JAR 绑定

镜像构建流水线应将封包内的 JAR 放到 `/app/application.jar`，保留运行用户、健康检查及启动参数。镜像推送后记录 `repository@sha256:...`，按部署节点平台拉取该摘要；标签不是固定身份。[Docker 的 digest 拉取语义](https://docs.docker.com/reference/cli/docker/image/pull/)约束下载的镜像内容。

在具备 Docker 的独立构建或验收环境，执行：

```powershell
python tools/release/release_bundle.py verify-image `
  --bundle '<发布目录>' --manifest-sha256 '<审核记录中的 SHA-256>' `
  --service commerce --image '<镜像仓库>/commerce@sha256:<实际摘要>'
if ($LASTEXITCODE -ne 0) { throw '镜像与已审核 JAR 不一致。' }
```

该命令只接受摘要形式，要求镜像已存在于本机：inspect 后创建一个不启动、无网络的临时容器，用 [docker cp](https://docs.docker.com/reference/cli/docker/container/cp/) 读取 JAR 并比较实际字节，最后删除本次容器。它不 pull、不启动服务、不挂载业务卷。返回 JSON 绑定发布清单摘要、服务、镜像摘要、本机 image ID、平台及 JAR 摘要；在发布记录中逐服务保存。多平台发布需逐平台核验，该回执不证明 JVM 参数、配置或运行时挂载未被覆盖，也不代表应用健康验收。

部署清单使用核验过的镜像摘要，禁止源码构建和覆盖 `/app/application.jar` 的挂载。基础设施、Frontend 和 Flyway 的镜像摘要也需由实际镜像仓库记录，不应手填猜测值。发布后核对运行容器的实际镜像 ID 与核验回执、启动参数及实际生效的非敏感配置，记录密钥**版本号**，不记录密钥内容。

## 迁移窗口

迁移原则和首次接管统一见[数据库迁移运行手册](../database-migrations.md)。执行的 SQL 必须来自通过上述校验的 `migrations/<database>`；工作区后续新增或修改的 SQL 不得混入本次发布。Flyway 历史校验和与发布包 SHA-256 职责不同，应同时保留。

1. 记录三库当前 `info` 输出、目标迁移集、前后应用兼容范围、预计锁表窗口、备份位置与恢复责任人。schema 版本不等于业务兼容性。
2. 涉及 Commerce 准入、分片代际、ledger 或 writer 所有权的变更，先完成 [Commerce 运行门禁](../architecture/commerce-migration-and-cutover.md)。其中 PowerShell drain 脚本只用于本地演练；生产通过文档指定的 Java routing operator 执行带租约与复制确认的门禁。不能直接停 worker 丢下已接受命令。
3. 应用流量与定时写入的隔离范围由本次变更决定；逐库执行 `validate`、`info`、审核后 `migrate`、再 `validate` 和 `info`，任一步失败即暂停发布。三个数据库之间没有原子迁移事务。
4. 扩展后的 schema 与旧应用兼容时才允许滚动；先发布具备版本检查的 Core，再发布使用新工具契约的 Agent/MCP，见 [ADR-0004](../adr/0004-core-agent-service-boundary.md)。最后完成业务核对、实际运行版本与[队列告警](queue-alerts.md)检查后恢复入口。

以下模板只针对一个已确认的数据库。运维先从密钥系统向当前进程注入 `FLYWAY_URL`、`FLYWAY_USER`、`FLYWAY_PASSWORD`，并设置已审核的 Flyway 镜像摘要与迁移网络；连接必须指向本次变更的目标。命令不会替操作者推断数据库或创建账号。

```powershell
$releaseDirectory = (Resolve-Path '<发布目录>').Path
$releaseDigest = '<审核记录中的 SHA-256>'
$databaseOwner = 'commerce' # core / commerce / agent，逐库执行
$flywayImage = '<已审核的 Flyway 仓库>@sha256:<实际摘要>'
$migrationNetwork = '<目标环境迁移网络>'
python tools/release/release_bundle.py verify --bundle $releaseDirectory --manifest-sha256 $releaseDigest
if ($LASTEXITCODE -ne 0) { throw '发布包校验失败。' }
$migrationDirectory = Join-Path $releaseDirectory "migrations/$databaseOwner"
$flywayArgs = @('run', '--rm', '--network', $migrationNetwork,
  '--mount', "type=bind,source=$migrationDirectory,target=/flyway/sql,readonly",
  '--env', 'FLYWAY_URL', '--env', 'FLYWAY_USER', '--env', 'FLYWAY_PASSWORD',
  $flywayImage, '-locations=filesystem:/flyway/sql', '-cleanDisabled=true', '-baselineOnMigrate=false')
docker @flywayArgs validate
if ($LASTEXITCODE -ne 0) { throw '迁移前校验失败，停止发布并保留日志。' }
docker @flywayArgs info
if ($LASTEXITCODE -ne 0) { throw '无法确认迁移历史，停止发布。' }
# 在审核本次 info 输出和迁移计划后，单独执行以下写操作。
docker @flywayArgs migrate
if ($LASTEXITCODE -ne 0) { throw '迁移失败；核对已提交 DDL 和历史，不要继续启动新版。' }
docker @flywayArgs validate
if ($LASTEXITCODE -ne 0) { throw '迁移后校验失败，保持维护窗口。' }
docker @flywayArgs info
if ($LASTEXITCODE -ne 0) { throw '无法保存迁移后历史，保持维护窗口。' }
```

每一步的输出保存到发布记录，不提交凭据或业务数据到仓库。非空库没有 Flyway 历史时，遵循已有首次接管流程，不能为使模板通过而自动 baseline。

## 回退决策

| 所处状态 | 允许的处置 | 必须保留的边界 |
|---|---|---|
| 尚未迁移、未开放新版写入 | 恢复上个已审核应用/配置制品 | 先核验旧发布包与实际镜像摘要 |
| 只执行兼容扩展，旧应用可读写新数据与协议 | 回退应用，保留已执行迁移 | 继续使用当前完整迁移链；不能运行旧发布包的 migrate 覆盖新历史 |
| 新版产生旧版无法理解的数据、审批契约或消息 | 保持入口关闭，前向修复或启动专项恢复 | 不强迫旧工具消费新审批，不重建授权，不回到旧 Core writer |
| 破坏性 DDL、迁移部分提交或业务数据损坏 | 对照实际结构及备份，在隔离环境恢复并核验，制定切回窗口 | 不自动 clean/repair，不删除 Flyway 历史，不猜测逆向 SQL |

MySQL DDL 可能隐式提交，Flyway 失败退出不等于整个迁移回滚。[Flyway 事务说明](https://documentation.red-gate.com/fd/migration-transaction-handling-273973399.html)解释了这一限制。删除字段、约束收缩、旧格式清理应在旧应用退出且回退窗口结束后独立发布。回退窗口没有通用天数：发布记录须写明旧应用可理解的 schema/事件/工具版本，以及最后可回退时点。

## 备份与恢复演练

备份成功与可恢复是两项不同证据。恢复演练必须在新建的隔离网络和空存储上进行，禁止覆盖现有 MySQL、Redis、RocketMQ 或 ES 卷；不使用 `reset -Force`、`FLUSHDB` 或回滚生产卷来验证。

需纳入恢复集的内容与关系：

- Core/Commerce/Agent 的全部业务表和 `flyway_schema_history`。订单、库存、reservation、admission ledger、recovery、Outbox、Inbox/事件版本、审批与执行幂等身份应作为关联事实保存，不能只导出订单表。
- Redis 的路由控制、准入身份、Stream/PEL 与待确认审批，RocketMQ 的可重放事件/消费位置，上传文件和对应数据库引用。数据中心或实例故障恢复需明确这些系统之间的一致性截点；三个独立 MySQL 快照也不会自动形成跨服务快照。
- 各后端发布包及独立清单摘要、镜像回执、有效配置版本、外部密钥版本、备份完成时间、MySQL GTID/binlog 位置（若使用 PITR）和文件摘要。Trace、日志与搜索投影不是交易或授权事实的替代来源。

对小型、已封闭写入口并完成排空的隔离演练，可使用逻辑备份。由各数据库运维身份配置受限的 MySQL option file，避免密码出现在命令行。下面只演示一个 schema，三个 owner 各自执行，文件路径不可复用：

```powershell
mysqldump --defaults-extra-file='<受限备份账号配置>' `
  --single-transaction --routines --events --triggers --hex-blob --no-tablespaces `
  --set-gtid-purged=OFF --result-file='<新备份文件.sql>' '<源 schema>'
if ($LASTEXITCODE -ne 0) { throw '备份失败；该文件不得登记为可恢复备份。' }
Get-FileHash -Algorithm SHA256 -LiteralPath '<新备份文件.sql>'
```

该模板是 schema 逻辑副本，`--set-gtid-purged=OFF` 不建立复制/PITR 能力；PITR 需额外保存可用 binlog 链和对应位置。`--single-transaction` 主要保护 InnoDB 的一致性读取，期间不能并行 DDL；其他引擎、权限和大数据量备份方式按 [MySQL mysqldump 文档](https://dev.mysql.com/doc/refman/8.4/en/mysqldump.html)选择。对跨系统在线恢复必须单独设计并演练一致性截点，不能由这一条命令推断 RPO。

在独立恢复实例预先建立新的空 schema，使用只允许该恢复目标的账号。在 `mysql --defaults-extra-file='<隔离恢复配置>' '<新空 schema>'` 会话中执行 `source <已核验备份文件.sql>`；随后从**匹配该备份历史的发布包**运行 Flyway validate/info。模板未使用 `--databases`，避免自动写回原 schema；仍须检查备份中的限定库名、DEFINER、事件与外部依赖，保持恢复实例事件调度和所有业务 worker 关闭，直到核验完毕。

恢复验证按同一发布记录登记：

1. 核验恢复集摘要、来源实例/截点与账号隔离，再恢复 schema 和文件；先不接流量、不启动事件消费和自动执行。
2. 核对三库历史、关键行数、ID 分配高水位、订单/库存/ledger/recovery、Outbox/Inbox/版本去重和审批状态。ID 分配高水位必须覆盖灾难前已分配的区间，不能仅取恢复后订单 `MAX(id)`；缺失证据时需预留新不相交区间并保持准入关闭。
3. 按 Commerce 现有 routing/fence/一致性修复协议恢复 Redis 投影与未完成请求。同一幂等键的未知结果先查事实，再决定重试；不得根据 Trace 恢复批准状态。RocketMQ 重放保持原 eventId、body 与版本，不能给旧事件生成新身份。
4. 确认搜索/GEO 可从业务事实重建，上传引用能访问。只执行与恢复场景关联的读取/交易核对，不借恢复演练运行无关性能基线。
5. 保存实际恢复时间、可核验的最后业务时间/数据缺口、未决请求与人工处理项，再决定是否开放入口。RPO/RTO 必须由该次实测与业务目标比较，不能从“备份存在”或“进程健康”推断。

## 模型与工具变更

模型名或 provider alias 可以在 JAR 不变时改变行为。发布记录应关联目标模型/供应商实际返回的版本信息、提示词和工具 schema 所在制品摘要、预算配置及变更所涉及的评测用例。复用现有 [EvalRunner](../../backend/eval/src/main/java/com/citycopilot/eval/EvalRunner.java) 的 `--provider`、`--output` 与 `--fail-on-gate`，只运行对应场景；offline 结果仅说明离线规则，不证明真实模型行为，provider 请求需在获授权的隔离环境执行并记录实际来源。

审批哈希绑定与工具版本检查是写入门禁，模型评测不能替代它们。模型回退使用已审核的配置版本；已有待审批/待验证步骤仍以原快照为准，无法满足旧契约时保留人工复核，不通过重算哈希或改写幂等键强行继续。
