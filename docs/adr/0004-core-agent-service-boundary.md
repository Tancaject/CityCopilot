# ADR-0004：拆分 Core 与 Agent 服务

状态：接受  
日期：2026-07-22  
取代：ADR-0001

## 背景

模块化单体已固定业务边界，但 Agent 的模型调用、流式连接和工具编排与交易业务具有不同的扩缩容、超时和故障特征。继续装载同一进程会让 Agent 故障影响核心业务，也无法独立发布。

## 决策

- `core-service` 是业务数据、权限和权威审计的唯一所有者，持有 MySQL 与业务 Redis。
- `agent-service` 只负责 Agent 编排、会话记忆、Trace 和远程 Tool 调用，使用独立 Redis DB，不连接业务数据库。
- 两者仅共享无实现依赖的 `contracts` 模块。Agent 通过版本化 `/internal/v1` HTTP API 调用 Core。
- Core 从用户 `Authorization` 解析身份，同时校验服务共享令牌；调用方不能通过请求体或用户 ID 请求头伪造权限。
- 非幂等 Tool 必须携带稳定 `Idempotency-Key`，Core 对请求指纹、执行结果和冲突进行权威判定。
- MCP 是无状态 Core 适配器，不装载 Core 或 Agent Bean。Nginx 分流业务 API 与 Agent SSE。

## 结果

- Core 与 Agent 可独立构建、发布、重启和扩缩容，Agent 故障不会直接终止 Core 进程。
- 跨服务调用增加网络超时、部分失败和配置管理成本，因此 Agent 使用超时、熔断、隔舱与受幂等约束的重试。
- 当前允许在一台电脑上运行多个进程，获得的是进程级隔离，不是主机级高可用；主机故障仍会同时影响全部服务。
- 本阶段不引入 Nacos/Eureka、Spring Cloud Gateway、专用 MQ、Seata 或集群编排平台。启动编排与多机部署在后续按实际需要补充。

### 工具契约与已批准计划

`ToolDefinition.contractVersion` 标识工具的输入、成功结果和业务语义契约。改变参数含义、结果形状或副作用语义时必须递增版本；不能在同一版本下替换既有含义。`resultSchema` 是声明的结果形状，不代表已实现通用 JSON Schema 运行时校验。`planVersion` 是快照格式，Ops 的计划版本是 case 内序号，二者都不代替工具契约版本。

`list_shop_types` 的公开分类视图由 [`IShopTypeService`](../../backend/core/src/main/java/com/citycopilot/core/service/IShopTypeService.java) 声明。其返回类型会参与生成 `resultSchema`，因此从持久化实体切换为读取 DTO 时同步递增工具契约版本；即使 HTTP JSON 字段兼容，Agent/MCP 也须刷新工具目录，旧计划不得静默沿用新声明。

Agent 将 Core 目录的版本和结果 Schema 原样带入规划元数据。新消费者检查点在 `toolContracts` 中一次保存主步骤与复核工具的契约，Ops 步骤同时保存写工具及独立复核工具的契约，均纳入现有审批哈希。快照来自服务端目录，不采用模型或手工计划自行声明的版本。恢复前重新读取权威目录，契约不匹配或目录不可达时不消费待确认动作；Ops 契约不匹配转人工复核。

每次实际调用通过 `ToolInvocationRequest.expectedVersion` 发送已绑定版本。Core 在抢占幂等状态和执行业务前比较当前版本，不匹配返回不可自动重试的 `tool_contract_mismatch`。这也约束目录检查后发生的滚动发布：调用不能默默切换到新版工具。原 pendingId、planHash 和业务幂等键继续使用，拒绝后必须重新审阅，不能自动换键重放。

发布时先升级并确认全部 Core 工具入口实例已执行 `expectedVersion` 检查，再启用新 Agent/MCP 和工具契约版本升级。仍运行旧二字段请求实现的 Core 可能忽略新增字段；新客户端本身不能为旧服务提供版本拒绝能力。

旧快照缺少契约字段时保持 `null`，规范化 JSON 省略这些字段，以保留原 V1 字节与哈希；旧请求缺少 `expectedVersion` 时仅表示工具版本 1。旧审批只可在工具仍为版本 1 时恢复，其历史结果 Schema 未记录，不能宣称已校验。新增字段为可选字段不改变已发出的审批版本；不能在读取旧数据时自动补入新版契约并重算哈希。若部署恰好发生在确认消费与实际调用之间，Core 仍拒绝版本变化，审批不会被转移到另一个工具版本。

## 面试回答

“我先用模块化单体建立边界，后来拆出了故障模型和伸缩需求明显不同的 Core 与 Agent。当前 [Commerce 边界](0006-commerce-place-service-boundaries.md)统一持有交易事实与事务，Core 提供身份、权限与工具入口，Agent 通过版本化 HTTP 契约调用它；这样得到独立发布和进程隔离，同时避免把订单、库存、优惠券和秒杀拆成分布式事务。”
