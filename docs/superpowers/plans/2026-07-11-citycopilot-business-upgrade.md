# CityCopilot 业务级升级计划

日期：2026-07-11。目标：把项目从"hmdp 改造的个人 demo"升级为经得起面试追问的业务级系统。本文档只写规划（拆解、改动点、测试点、验收口径），不贴实现代码。

## 背景与诊断

通读全部后端模块与前端后的结论：**agent / mcp-server / eval 三个模块是差异化资产，问题集中在 core 模块的 hmdp 基因和若干正确性硬伤。**

保留并放大的资产：

- `@AgentTool` 注册表 + ToolGateway（权限/参数校验/审计/Resilience4j/自定义指标）
- ReAct + Plan-Execute-Replan 双执行器、Critic 前审、`$stepN.field` 引用解析
- MCP 层复用同一套 Registry/Gateway、30 条 eval 用例、秒杀压测证据
- 四模块依赖方向干净：`mcp-server -> agent -> core`，`eval -> agent`

必须解决的问题（按严重度）：

| # | 问题 | 位置 | 性质 |
|---|---|---|---|
| 1 | 真实 AMap key / DashScope key 曾提交进仓库默认值 | `backend/core/src/main/resources/application.yaml` | 安全泄露 |
| 2 | LLM 不可用时启发式兜底虚构写操作（默认对 voucherId=1 下单、orderId=1 支付） | `PlanExecuteExecutor.heuristicPlan` | 正确性/安全 |
| 3 | merchant 角色持全局 `shop:write`，可修改任何店铺，无归属模型 | `PermissionPolicy` + `ShopServiceImpl` | 权限设计缺陷 |
| 4 | 订单状态为魔法数字 1-6，散落在条件更新中，无状态机 | `VoucherOrderServiceImpl` | 业务正确性 |
| 5 | LLM 集成为 prompt 拼 JSON + 字符串截取解析，非 function calling；SSE 为假流式 | `OpenAiCompatibleAgentLlmClient`、`AgentController` | 技术含量 |
| 6 | 无多轮对话记忆，chat 无状态 | agent 模块 | 产品完整性 |
| 7 | hmdp 视觉指纹：虎哥 javadoc、教学注释、注释掉的教学代码、`tb_*` 表名、16MB nginx 残留、873 行规则式假 AI | core 全域 | 身份识别 |
| 8 | 无集成测试、无 CI | 全仓 | 工程化 |

## 范围与非目标

**做**：P0 摘硬伤 → P1 Agent 升级 → P2 业务正确性 → P3 schema 去 hmdp → P4 工程化。

**明确不做**（并写入 ADR 作为面试取舍材料）：

- 不拆微服务——单体多模块 + 清晰依赖方向是更好的故事
- 不换 RocketMQ——Redis Stream 补齐 DLQ/对账后是可辩护的选择（单机部署、不为简历堆中间件）
- 不重写前端——只新增高风险操作确认卡片一个交互
- Spring Security + JWT 全套替换默认不做（见"可选项"），改为拦截器收敛 + ADR

---

## P0 摘硬伤（约 0.5 天）

### P0.1 密钥与危险默认值（已完成）

- [x] `application.yaml`：AMap key、DashScope key 默认值清空，只读环境变量；Redis 默认 host 改 127.0.0.1；DB 默认密码清空
- [x] `tools/loadtest/seed_loadtest.py` 同步默认值
- [ ] 用户侧：到高德/阿里云控制台作废并重发两个已泄露 key（git 历史已推 GitHub，删引用不等于止损）

### P0.2 删除教学残留

- [x] 删 `backend/core/src/main/resources/nginx-1.18.0`（16MB，会打进 jar）
- [x] 删无引用教学类 `utils/SimpleRedisLock.java`、`utils/ILock.java`（已核验 main/test 均无引用；`RedisData`/`CacheClient` 仍被逻辑过期使用，保留）
- [x] 清理 README / backend/README 中关于 nginx 残留的说明段落

### P0.3 删除规则式假 AI 链路

- [x] 删 `core/service/AiAssistantService.java`（873 行正则+关键词的"假 AI"，已标 `@Deprecated`，项目旧计划文档本就列为清理候选）
- [x] 删 `core/controller/AiController.java` 及仅被其使用的 DTO（`AiChatResponse`、`AiChatMessageDTO`、`AiAssistantActionDTO`、`AiAssistantShopDTO`；逐一核验引用后删）
- [x] `AiChatRequest` 从 core.dto 迁到 agent 模块 http 包（`AgentController` 是唯一真实使用方）
- [x] 前端删 `frontend/src/api/citycopilot.ts` 的 `sendAiChat` 及 `AiAssistantPanel.vue` 中 `/ai/chat` 兜底分支
- [x] `application.yaml` 中仅服务于旧链路的 `citycopilot.ai.*` 限流/历史条目参数评估后收敛（`AiProperties` 仍被 LLM client 使用，保留主体）

### P0.4 清除 hmdp 教学痕迹

- [x] 全仓移除 `@author 虎哥 @since 2021-12-22` javadoc（core 所有 entity/service/mapper）
- [x] 删 `ShopServiceImpl` 中整段注释掉的 `queryWithMutex` 教学代码
- [x] 删 `VoucherOrderServiceImpl` 无用的 `proxy` 字段与 `AopContext.currentProxy()` 残留调用
- [x] 内部类 `voucherOrderHandler` 更名 `SeckillOrderWorker`（类名大写规范）
- [x] 教学式逐行中文注释（"//判断消息是否获取成功"式）精简为约束性注释；保留讲设计意图的注释

### P0.5 修复危险兜底

- [x] `PlanExecuteExecutor.heuristicPlan`：LLM 不可用时只允许生成只读工具步骤（search/list/get 类），含写操作意图时直接返回"需要 AI 服务可用才能执行下单/支付类操作"的最终回答，绝不虚构 voucherId/orderId
- [x] 同步核查 `HeuristicAgentLlmClient` 是否存在同类问题
- [x] 更新受影响的 `PlanExecuteExecutorTest` 用例

**P0 测试点**：`mvn clean verify` 全绿；`npm run build` 通过；grep 全仓无"虎哥"、无泄露 key 串；`/agent/chat` 在无 AI key 环境下对"帮我下单"返回拒绝而非虚构订单。

---

## P1 Agent 升级（2-3 天，简历主线）

### P1.1 Function calling 替代 prompt-JSON

- [x] 改动点：`OpenAiCompatibleAgentLlmClient` 请求体改标准 `tools` 参数（`type:function` + JSON Schema），解析 `tool_calls` 响应；复用 agent 模块已有 `JsonSchemaGenerator` 从 `ToolMetadata.parameters()` 生成 schema
- [x] ReAct 循环改为真正的 messages 多轮数组（system + user + assistant(tool_calls) + tool 角色回填），替换现在把 observations 塞进 user JSON 的做法
- [x] 保留 Heuristic fallback 接口不变（只读化后的版本）
- [x] 测试点：`OpenAiCompatibleAgentLlmClientTest` 重写为 tool_calls 响应夹具；`ScriptedAgentLlmClient` 适配

### P1.2 人在回路确认（旗舰特性）

- [x] 高风险工具（现 `HIGH_RISK_TOOLS` 集合）不再由 Critic 自动放行，改为：执行器暂停 → SSE 下发 `confirmation` 事件（操作摘要、参数、pendingId）→ Redis 存 pending action（TTL 5 分钟，绑定 userId + traceId）→ 前端确认卡片 → `POST /agent/confirm/{pendingId}` 校验同一用户后经 ToolGateway 执行 → pendingId 一次性消费（幂等）
- [x] Critic 降级为 confirm 卡片生成前的自动预审（预审拒绝则直接不出卡片）
- [x] 前端：`AiAssistantPanel.vue` 新增确认/取消卡片，复用现有工具卡片样式体系
- [x] 面试故事：LLM 审 LLM 不可靠，写操作责任落到人；一次性 pendingId 解决重复点击幂等
- [x] 测试点：pending 过期、跨用户 confirm 拒绝、重复 confirm 拒绝、confirm 后 trace 补全链路

### P1.3 多轮会话记忆

- [x] sessionId（前端生成并保持）+ Redis LIST/Hash 存对话轮次，注入 LLM messages，按条数与总长度截断
- [x] 测试点：第二轮引用第一轮上下文（"就订它"能解析出上一轮的店铺/券）

### P1.4 真流式输出

- [x] final answer 阶段 `stream:true`，逐 token 经 SSE `delta` 事件转发；工具执行阶段维持现有事件粒度
- [x] 测试点：SSE 事件顺序（status → tool* → confirmation? → delta* → final）

**P1 验收口径**：配真实 DashScope key 后，多轮对话可完成"找店→看券→下单（弹确认卡→确认执行）"完整链路，trace 记录含 confirm 环节；eval 30 条离线用例保持通过。

---

## P2 业务级正确性（2-3 天）

### P2.1 订单状态机

- [x] 新建 `OrderStatus` 枚举（UNPAID/PAID/USED/CANCELLED/REFUNDING/REFUNDED）+ 显式转移表（如 UNPAID→{PAID,CANCELLED}），`VoucherOrderServiceImpl` 的 pay/cancel/use/refund 全部走 `transition(from,to)` 校验，替换散落的魔法数字条件更新
- [x] DB 仍存 int，entity 层转换；`orderState()/orderStatusText()` 收敛进枚举
- [x] 测试点：全部非法转移矩阵单测；条件更新并发下仍保证单次生效

### P2.2 商户-店铺归属（行级权限）

- [x] `tb_shop` 加 `owner_user_id`；merchant 写店铺/券时服务层校验归属（admin 豁免）；HTTP 接口与 Agent 工具两条路径同点生效（校验放服务层而非拦截器）
- [x] `citycopilot.sql` 与既有数据迁移脚本同步
- [x] 测试点：merchant 改他人店铺返回 403 语义错误；admin 不受限；`shop_create` 自动落 owner

### P2.3 库存对账与死信

- [x] 定时任务比对 Redis `seckill:stock:*` 与 DB 库存，偏差记审计 + Micrometer 指标 + 可选自愈
- [x] 秒杀 stream 消费失败重试 N 次后进死信 stream + 告警指标，替换现在教学式 pending-list 循环
- [x] 秒杀入口支持幂等键（同 key 重试返回原 orderId）
- [x] 测试点：人为制造 Redis/DB 偏差后对账检出；毒消息进死信不阻塞主流

**P2 验收口径**：状态机矩阵单测全绿；商户越权路径全部被拒；对账 job 有指标可观测。

---

## P3 数据库 schema 去 hmdp 化（1-2 天，机械）

- [x] `tb_*` 全部重命名（shop / shop_type / coupon / coupon_order / seckill_coupon / note / note_comment / user_follow / user_profile / operation_log / geo_batch_job...，最终命名口径实施前给用户过目）
- [x] 产出：`citycopilot-v2.sql` 全新建库脚本 + 旧库→新库迁移脚本
- [x] entity `@TableName`、mapper、XML、`RedisConstants` 中键名同步；API 路径与前端不动
- [x] 顺带清理 hmdp 字段风格（`ZEROFILL`、"维度"错别字注释等）
- [ ] 测试点：新库导入后全链路 smoke（登录/店铺/券/下单/发现）
- 风险：本地库需重建重导；压测 seed 脚本同步改表名

---

## P4 工程化（1-2 天）

- [x] Testcontainers 集成测试：MySQL+Redis 容器跑秒杀 200 并发，断言零超卖 + 一人一单（本机 Docker 管道有已知坑：启 TCP 2375 或在 IDEA 里跑，CI 上无此问题）
- [x] GitHub Actions：`mvn -q clean verify` + `cd frontend && npm ci && npm run build` 双 job
- [x] springdoc-openapi：`/swagger-ui` 暴露 API 契约（替代手写 `ApiContractController` 的部分职责）
- [x] ArchUnit：守护 `mcp-server -> agent -> core` 依赖方向不被破坏
- [x] `docs/adr/`：3 篇 ADR（为什么单体多模块、为什么 Redis Stream 而非 MQ、为什么人在回路而非纯 Critic）

**P4 验收口径**：CI 徽章绿；集成测试可在 CI 重复执行；ADR 可直接作为面试答题底稿。

---

## P5 双服务边界落地（2026-07-22）

本期把现有模块化单体演进为 `core-service + agent-service` 两个独立 Spring Boot 进程。本期只处理代码、协议、配置和验证边界；Docker Compose、一键启动/停止脚本、观测组件编排留到下一期。

### P5.1 共享契约与 Core 内部 API

- [x] 新增无业务实现依赖的 `backend/contracts` 模块，承载 Tool 元数据、调用上下文、调用请求/响应与统一错误协议
- [x] Core 独立打包为可执行应用，只由 Core 持有 MySQL、业务 Redis、权限和权威业务审计
- [x] Core 暴露版本化 `/internal/v1` 会话解析与 Tool API；校验用户 token 与服务 token，且拒绝伪造用户身份
- [x] 写 Tool 以稳定 `Idempotency-Key` 执行；相同 key 不同请求指纹返回冲突

### P5.2 Agent 独立服务

- [x] Agent 移除对 `core` 实现模块的 Maven 依赖和包扫描，只依赖 `contracts`
- [x] `ToolGateway` 抽象为端口并增加 `RemoteToolGateway`，通过 HTTP 调用 Core，传播用户 token、服务 token、请求 ID 与幂等键
- [x] Agent 使用独立 `application.yaml`、端口与 Redis DB/前缀，不持有 MySQL 凭据、不直接写业务审计表
- [x] 远程调用补齐超时、熔断、隔舱和安全重试；写操作仅在稳定幂等键存在时重试

### P5.3 MCP、入口和架构守卫

- [x] MCP 改为无状态远程适配器，不再扫描或装载 Core/Agent 业务 Bean
- [x] Nginx 将 `/api/agent/**` 路由到 Agent，其余业务 API 路由到 Core，并为 SSE 关闭代理缓冲
- [x] 更新 ArchUnit、README 与 ADR，明确新依赖方向、数据所有权和单机部署不等于主机级高可用

### P5.4 验证

- [x] 契约、服务认证、用户身份转发、幂等冲突、远程降级和 MCP 适配测试通过
- [x] `mvn -q clean verify`、前端 `npm run build` 与离线 eval gate 通过
- [x] 启动编排明确留到下一阶段，不在本期引入 Nacos/Eureka、Spring Cloud Gateway、专用 MQ、Seata 或 Kubernetes

**P5 验收口径**：Agent 编译期不依赖 Core、两个应用可独立打包，Agent 无业务数据库访问能力；Core 不可用时 Agent 保持存活并返回受控错误，恢复 Core 后无需重启 Agent 即可继续调用。

**P5 执行记录（2026-07-22）**：Core、Agent、MCP 专项测试及架构守卫通过；Core/Agent 可执行 JAR 打包通过；`mvn -q clean verify`、前端生产构建和 30 条离线 eval gate 通过。Testcontainers 因本机 Docker daemon 不可用继续按 `disabledWithoutDocker` 跳过，启动编排按计划留到下一阶段。

---

## P6 单机 Docker 一键启动（2026-07-22）

本期只解决一台电脑上的可重复启动、停止、状态查看和数据持久化。Compose 负责进程编排与服务健康依赖，但不把单机多容器描述为主机级高可用。

### P6.1 镜像与运行边界

- [x] Core、Agent、MCP 使用统一的多阶段 Java 17 Dockerfile，运行阶段使用非 root 用户并提供容器健康探针
- [x] 前端使用 Node 构建、Nginx 运行的多阶段镜像，复用 `/api/agent/**`、`/api/**` 与 `/uploads/**` 路由
- [x] 增加 `.dockerignore`，排除 Git、本地依赖、构建产物、测试截图、简历工作区和本地密钥

### P6.2 Compose 与一键运维

- [x] `compose.yaml` 编排 MySQL、Redis、Core、Agent、MCP 与前端，按健康状态启动并限制端口只绑定本机
- [x] MySQL 首次启动自动导入 `citycopilot-v2.sql`，MySQL、Redis 与上传文件使用命名卷持久化
- [x] 增加 `.env.example`；首次启动脚本自动生成本地 `.env` 随机密码和内部服务令牌，不把密钥提交到 Git
- [x] 增加 PowerShell 一键启动、停止、重启、状态、日志和显式数据重置，并在 Docker Desktop 未运行时自动尝试启动

### P6.3 文档与验证

- [x] README 写明一键命令、默认端口、首次构建耗时、数据卷与重置风险
- [x] `docker compose config` 与脚本语法检查通过
- [x] 六个主服务镜像拉取/构建成功，完整栈实际启动后 Core、Agent、MCP、前端健康及 Nginx 路由冒烟通过
- [x] 停止后再次启动数据卷仍保留；最终栈可由一条命令安全停止

**P6 验收口径**：新电脑安装 Docker Desktop 后，在仓库根目录执行一个脚本即可生成本地密钥、构建并启动完整栈；首页统一从 `http://localhost:8080` 访问，服务异常能通过 `status`/`logs` 快速定位。

**P6 执行记录（2026-07-22）**：一键脚本自动启动 Docker Desktop、生成本地 `.env`、拉取 MySQL/Redis 并构建 Core、Agent、MCP、前端镜像；六个主容器均达到 `healthy`，Nginx 到 Core/Agent、Agent 到 Core、MCP 到 Core 的冒烟请求通过。写入 Redis 探针后执行安全停止与 `start -NoBuild`，探针仍保留；最终工作树再次完整构建启动通过。Docker 29 兼容配置补齐后，MySQL 8.4.10 + Redis 7.2 Testcontainers 的 2 个集成用例实际执行且 0 跳过。

### P6.4 复用 Ubuntu WSL Redis（2026-07-22）

- [x] Ubuntu WSL Redis 启用认证且配置过程不输出密钥
- [x] Compose 移除全部 Redis 容器，Core DB 0、Agent DB 1 保留外部 WSL Redis 配置
- [x] WSL Redis 配置脚本幂等创建消费组 `g1`，Compose 不再包含 `redis-init`
- [ ] Docker 到 WSL 的认证 PING、完整栈、Redis DB/Stream 与持久化验证通过

**P6.4 执行记录（2026-07-22）**：Compose 最终服务/镜像清单只包含 MySQL、Core、Agent、MCP 和前端，不再包含 Redis 服务端或 `redis-init`；WSL Redis 认证 PING 与消费组 `g1` 在 Ubuntu 内验证通过。实际执行 Compose 后 MySQL 达到 `healthy`，Core 在 Redisson 初始化时因 `host.docker.internal:6379` 不可达而退出，其余服务被健康依赖阻止；失败的 Windows 转发、计划任务、防火墙与网卡转发配置已全部回滚，因此 Docker 到 WSL 与完整栈验收保持未勾选。本节覆盖 P6.2/P6.3 中原 Docker Redis 编排结论。

### P6.5 恢复项目专用 Docker Redis（2026-07-22）

- [x] Compose 恢复 `redis:7-alpine` 服务、`redis-data` 数据卷和宿主机 `6380` 调试端口，不影响 WSL Redis `6379`
- [x] 恢复 `redis-init`，Core 使用 DB 0、Agent 使用 DB 1，应用只通过 Compose 网络访问 `redis:6379`
- [x] MySQL、Redis、Core、Agent、MCP、前端完整启动并全部健康
- [x] Docker Redis 认证、DB 隔离、消费组 `g1` 与重启持久化验证通过

**P6.5 执行记录（2026-07-22）**：Compose 配置恢复项目专用 `redis:7-alpine`，Windows 端口 `6380` 与 WSL Redis `6379` 并存；MySQL、Redis、Core、Agent、MCP、前端全部达到 `healthy`，首页、Core、Agent 与 MCP 路由冒烟通过。Docker Redis 认证返回 `PONG`，DB 0/1 同名探针分别返回 `db0`/`db1`，消费组 `g1` 存在；写入持久化探针并重启 Redis 后数据仍保留，Core/Agent 无需重启即恢复健康。本节取代 P6.4 的 WSL Redis 跨网络方案。

---

## 可选项（默认不做，待拍板）

| 项 | 内容 | 代价 | 建议 |
|---|---|---|---|
| Spring Security + JWT | 替换手写三拦截器，access/refresh 双 token | 3-4 天，前后端都动 | 不换；做拦截器配置收敛（`/shop/**` 排除登录但靠 admin 拦截器兜底的绕路写法）+ ADR 讲清 Redis 会话取舍 |
| RocketMQ 替换 Redis Stream | 校招 MQ 考点 | 引入部署依赖，demo 变重 | 不换；P2.3 的 DLQ/对账 + ADR 足以答题 |

## 风险与前置条件

1. 工作树上有一批未提交的发现链路/前端定位改动——大改应从提交后的干净基线开始
2. P3 需重建本地数据库并重导数据
3. P1 真实链路验收依赖新的 DashScope key（旧 key 作废后需配新）
4. 每期结束必须 `mvn clean verify` + `npm run build` 全绿才进入下一期；期间不合并跨期改动

## 验证命令

- 后端：`.\.tools\apache-maven-3.9.9\bin\mvn.cmd -q clean verify`
- 前端：`cd frontend && npm run build`
- Eval gate：`backend/eval` EvalRunner `--fail-on-gate`
- 冒烟：`tools/smoke_mcp_http.ps1`、`tools/smoke_ops_agent.ps1`、`tools/smoke_observability.ps1`

## 工作量汇总

| 期 | 内容 | 预估 |
|---|---|---|
| P0 | 摘硬伤 | 0.5 天 |
| P1 | Agent 升级 | 2-3 天 |
| P2 | 业务正确性 | 2-3 天 |
| P3 | schema 迁移 | 1-2 天 |
| P4 | 工程化 | 1-2 天 |
| 合计 |  | 7-10 天 |

## 执行记录（2026-07-11）

- [x] P0–P2：`mvn -q clean verify` 与 `npm run build` 通过
- [x] P3：运行时代码、v2 建库脚本和压测 seed 中无 `tb_*` / `ZEROFILL` / “维度”错字
- [x] P4：最终 `mvn -q clean verify` 通过，ArchUnit 与普通单元测试通过
- [x] Eval gate：30 条离线用例通过 `--fail-on-gate`
- [x] Testcontainers 实跑：Docker 29 下使用 API 1.44，MySQL 8.4.10 + Redis 7.2 的 2 个集成用例实际执行且 0 跳过
- [ ] 真实 DashScope 多轮链路：等待配置新 key
- [ ] GitHub Actions 徽章：workflow 已加入，需提交并推送后由 GitHub 首次运行确认
