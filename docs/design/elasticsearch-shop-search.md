# Elasticsearch 店铺搜索投影

## 目标与边界

`backend/search` 是随 Core 部署的可选搜索库，不是独立微服务。它只维护可重建的店铺搜索投影：

- MySQL 继续是店铺与分类事实源；
- Redis GEO 继续承载无关键词的附近店铺召回；
- Elasticsearch 处理店名、分类、商圈和地址的全文检索，并支持类型、价格、评分和距离过滤；
- Agent 的结构化用户偏好仍保存在 Agent MySQL。`preferredCategory` 只用于软加权，不会成为排除其他店铺的硬过滤条件；
- Elasticsearch 只返回有序店铺 ID，Core 必须从 MySQL 回表并重新检查店铺是否仍可用。

当前增量同步复用店铺写事务的 `afterCommit` 钩子，并按店铺 ID 重新读取 MySQL 最新行后再写入索引，避免并发回调用旧快照覆盖新值。失败不会回滚已经提交的业务事务；失败后由定时全量重建修复。因此这是最终一致、可降级的读模型，不是事务双写或强一致链路。需要多实例可靠投影时，再升级为 Transactional Outbox、幂等事件消费和版本检查。

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

Elasticsearch 位于独立的 Compose profile，固定使用与 Spring Boot 3.2.2 依赖管理兼容的 8.10.4：

```powershell
docker compose --profile search up -d elasticsearch
$env:CITYCOPILOT_SEARCH_ENABLED="true"
$env:CITYCOPILOT_SEARCH_MODE="shadow"
.\.tools\apache-maven-3.9.9\bin\mvn.cmd -pl backend/core -am spring-boot:run
```

确认 shadow 日志和相关性用例后，再把模式切为 `elasticsearch`。宿主机调试地址默认是 `http://127.0.0.1:9200`；Compose 中的 Core 使用 `http://elasticsearch:9200`。

## 索引生命周期

- 首次启用或索引不健康时，从 MySQL 全量构建新 generation；
- 全量文档写入成功后原子切换 alias，避免查询看到半成品索引；
- 旧 generation 暂时保留用于回滚，不会自动删除；生产环境需要另设受控的保留与清理策略；
- 店铺创建、更新和坐标变更在事务提交后按 ID 重新读取 MySQL 最新值，再执行幂等 upsert；
- 任一增量写入失败会把本进程标记为 stale，reconciliation job 随后重新构建；
- Elasticsearch 返回的命中若在 MySQL 回表时缺失或已失效，会把投影标记为 stale 并回退原链路；真正的零命中仍按空结果返回；
- Elasticsearch 不可用时，查询回退 MySQL/Redis GEO，业务写入仍以 MySQL 提交结果为准。

Elasticsearch 的 Actuator health indicator 不参与 Core 整体健康判定，避免可选搜索故障触发 Core 容器重启；索引是否 ready 由搜索网关与 reconciliation 状态单独判断。

不应把当前能力表述为向量检索或 RAG；项目尚未增加 embedding、混合召回、引用链路或对应评测。
