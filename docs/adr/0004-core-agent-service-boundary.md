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

## 面试回答

“我先用模块化单体建立边界，后来只拆出了故障模型和伸缩需求明显不同的 Core 与 Agent。Core 仍统一持有交易数据、权限、审计和幂等，Agent 通过小型版本化 HTTP 契约调用它；这样得到独立发布和进程隔离，同时避免把订单、库存、优惠券和秒杀拆成分布式事务。”
