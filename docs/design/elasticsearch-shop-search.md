# Elasticsearch 店铺搜索投影

## 目标与边界

`backend/search` 是随 Core 部署的可选搜索库，不是独立微服务。它只维护可重建的店铺搜索投影：

- MySQL 继续是店铺与分类事实源；
- Redis GEO 继续承载无关键词的附近店铺召回；
- Elasticsearch 处理店名、分类、商圈和地址的全文检索，并支持类型、价格、评分和距离过滤；
- Agent 的结构化用户偏好仍保存在 Agent MySQL。`preferredCategory` 只用于软加权，不会成为排除其他店铺的硬过滤条件；
- Elasticsearch 只返回有序店铺 ID，Core 必须从 MySQL 回表并重新检查店铺是否仍可用。

店铺写事务内递增事实版本并追加投影 Outbox；Projector 持有写入租约、按 claim token 认领记录，重新读取 MySQL 最新事实，再以版本幂等方式更新 Elasticsearch 和 Redis GEO。两份投影完成后才确认 Outbox；失败保留记录并退避重试。它仍是最终一致、可降级的读模型，详细约束见 [ADR 0010](../adr/0010-versioned-shop-search-geo-projections.md)。旧 `afterCommit` 路径仅由兼容开关保留，默认关闭。

## 查询模式

通过 `CITYCOPILOT_SEARCH_MODE` 控制读流量：

| 模式 | 行为 |
|---|---|
| `mysql` | 默认值。完全沿用 MySQL + Redis GEO 链路，不访问 Elasticsearch。 |
| `shadow` | 同时执行 Elasticsearch 查询并比较店铺 ID，但仍返回原链路结果。 |
| `elasticsearch` | 有关键词或品类偏好时使用 Elasticsearch；索引未就绪、超时或异常时回退原链路。 |

`CITYCOPILOT_SEARCH_ENABLED=false` 会关闭索引写入、重建和查询。默认配置不会让 Elasticsearch 成为 Core 的启动依赖。

进入 `shadow` 或 `elasticsearch` 模式后，投影补偿重建固定开启；当前没有另设关闭开关或管理端手动重建入口，避免已知 stale 状态无法恢复。

## 本地启动

Elasticsearch 位于独立的 Compose profile，服务端镜像版本以 [compose.yaml](../../compose.yaml) 为准，并与父 POM 管理的 Java 客户端配套升级：

```powershell
docker compose --profile search up -d elasticsearch
$env:CITYCOPILOT_SEARCH_ENABLED="true"
$env:CITYCOPILOT_SEARCH_MODE="shadow"
.\.tools\apache-maven-3.9.9\bin\mvn.cmd -pl backend/core -am spring-boot:run
```

确认 shadow 日志和相关性用例后，再把模式切为 `elasticsearch`。宿主机调试地址默认是 `http://127.0.0.1:9200`；Compose 中的 Core 使用 `http://elasticsearch:9200`。

### 从旧搜索卷升级

Compose 为新主版本使用独立的 `elasticsearch-v9-data` 卷，旧 `elasticsearch-data` 卷保留，不直接挂载给新容器。旧 `.env` 中的 `CITYCOPILOT_ELASTICSEARCH_VERSION` 已不再控制镜像，应删除该过时配置；镜像版本统一在 Compose 中维护。

先让 Core 保持 `mysql` 模式，停止旧搜索容器，再用上面的命令启动新搜索容器。随后启用搜索写入及 `shadow` 模式，从 MySQL 重建投影；确认新 alias 已就绪、重建无积压且业务查询符合预期后，才切换读流量。回退时先将 Core 恢复为 `mysql` 模式；如需恢复旧搜索容器，必须同时恢复与其兼容的应用和 Compose 配置。整个过程不复制旧索引文件，不删除旧卷，也不改写 MySQL 业务事实。

## 索引生命周期

- 首次启用或索引不健康时，从 MySQL 全量构建新 generation；
- 全量构建记录 Outbox 起始水位，追赶增量，并在切换屏障内追平尾部后原子切换 alias；
- 旧 generation 暂时保留用于回滚，不会自动删除；生产环境需要另设受控的保留与清理策略；
- 店铺创建、更新和坐标变更通过同事务 Outbox 驱动，按 ID 重新读取 MySQL 最新事实，再执行版本幂等 upsert；
- 增量失败保留 Outbox 重试；索引未就绪或被标记为 stale 时，reconciliation job 重建；
- Elasticsearch 返回的命中若在 MySQL 回表时缺失或已失效，会把投影标记为 stale 并回退原链路；真正的零命中仍按空结果返回；
- Elasticsearch 不可用时，查询回退 MySQL/Redis GEO，业务写入仍以 MySQL 提交结果为准。

Elasticsearch 的 Actuator health indicator 不参与 Core 整体健康判定，避免可选搜索故障触发 Core 容器重启；索引是否 ready 由搜索网关与 reconciliation 状态单独判断。

不应把当前能力表述为向量检索或 RAG；项目尚未增加 embedding、混合召回、引用链路或对应评测。
