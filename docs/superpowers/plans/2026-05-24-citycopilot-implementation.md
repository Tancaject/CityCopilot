# CityCopilot 实施计划

本文档只保留执行清单、当前状态和验收口径，不再放代码块。具体实现以源码、测试和 Git 提交历史为准。

## 目标

把原 hmdp 项目整理成 CityCopilot：一个面向本地生活业务的 Java 后端 + AI Agent 项目，包含业务工具注册、对话 Agent、MCP 工具层、评测框架、可观测性和前端 Trace 看板。

## 架构边界

| 模块 | 职责 |
|---|---|
| `backend/core` | 店铺、优惠券、订单、秒杀、Geo、审计、权限等核心业务 |
| `backend/agent` | 工具注册表、工具网关、ReAct、Plan-Execute-Replan、SSE、Trace、运营 Agent、审查 Agent |
| `backend/mcp-server` | 复用工具注册表和工具网关，对外暴露 MCP tool callbacks 与 HTTP 兼容工具接口 |
| `backend/eval` | YAML 用例、评测运行器、Markdown 评测报告 |
| `frontend` | Vue 3 前端，包含 AI 面板、工具卡片、Trace 看板和运营页面 |

依赖方向：`mcp-server -> agent -> core`，`eval -> agent`。

## 当前状态

| 项 | 状态 | 说明 |
|---|---|---|
| 项目结构 | 已完成 | Java 后端已移动到 `backend/` 下，形成 4 个 Maven 模块 |
| Git 基线 | 已完成 | 代码已推过 GitHub，当前分支 `codex-project-structure` 持续提交 |
| Gitignore 与敏感信息保护 | 已完成 | 已排除日志、环境文件、构建产物和常见敏感文件 |
| 历史静态前端清理 | 已完成 | `backend/core` 已移除历史 Nginx 静态页面和 Nginx 二进制，前端统一由 `frontend/` 维护 |
| 工具注册表 | 已完成 | Spring bean 扫描 `@AgentTool`，主源码注册 27 个工具名 |
| 工具网关 | 已完成 | 参数校验、权限校验、标准错误、审计、指标和 Resilience4j 已接入 |
| ReAct 执行器 | 已完成 | 支持单步和多步工具调用、重复调用保护、Trace 持久化 |
| Plan-Execute-Replan 执行器 | 已完成 | LLM 优先规划、引用解析、失败重规划、权限预检、审查门禁 |
| 对话 Agent | 已完成 | `/agent/chat` 通过 SSE 输出 `status`、`traceId`、`tool`、`error`、`final` |
| 前端 Agent 面板 | 已完成 | AI 面板使用 `/agent/chat`，渲染工具卡和 traceId；旧 `/ai/chat` 已下线 |
| Trace 看板 | 已完成 | 后端 trace list/detail API 与前端看板已完成，后端要求 `audit:read` 权限 |
| MCP Server | 部分完成 | HTTP tools、权限过滤、Spring AI MCP callbacks、8090 配置、客户端样例和 HTTP smoke 脚本已补；真实 MCP 客户端验收待做 |
| 运营 Agent | 部分完成 | Redis Stream 队列、ack、结果保留、最近结果查询、结构化处理和 live 工具网关执行已完成；真实数据调参待做 |
| 审查 Agent | 已完成 | 高风险工具执行前 fail-closed 审查 |
| 评测框架 | 部分完成 | 30 条 YAML case、fail-on-gate、Markdown 报告、失败原因分布、离线指标和 OpenAI-compatible provider 模式已完成；带真实 key 的指标待跑 |
| 可观测性 | 部分完成 | Actuator、Prometheus、Grafana JSON、工具指标、LLM 指标和 smoke 脚本已完成；真实 dashboard 验收待做 |
| 文档与简历材料 | 部分完成 | README、`docs/RESUME.md`、MCP 文档和离线指标已更新；demo、压测和真实指标待补 |

## 已完成的安全修复

- MCP `tools/call` 不再信任请求体里的 `userId` 或 `permissions`。
- `/agent/chat` 异步线程会恢复当前登录用户上下文。
- `/agent/traces` 与 `/agent/traces/{traceId}` 后端强制要求 `audit:read` 权限。
- 秒杀退款不再破坏一人一单 Redis 集合；取消订单才释放资格。
- 秒杀 stream worker 不可用时不再先执行 Lua 扣库存。
- null 工具参数不会再打断 `AgentDecision`、Trace 或工具网关审计。
- SSE 第一帧固定为 `status`，前端不会把内部状态文本当作回答。

## 当前离线指标

来源：`backend/eval/reports/citycopilot-v01-latest.md`

| 指标 | 值 | 说明 |
|---|---:|---|
| 评测用例数 | 30 | `citycopilot-v01.yaml` |
| 模式准确率 | 100% | Router 模式匹配期望 |
| 工具调用准确率 | 100% | 期望工具包含于预测工具 |
| 关键词准确率 | 100% | 命中必含关键词且避开禁用关键词 |
| 平均评测延迟 | 0 ms | 离线 deterministic runner，不代表真实 LLM 延迟 |
| 平均估算 token | 27 | 启发式 token 估算 |
| 失败原因分布 | none | 当前离线报告全部通过 |
| 已注册工具数 | 27 | 主 Java 源码中的唯一 `@AgentTool` 名称 |

## 剩余任务

### 1. MCP 真实客户端验收

- [ ] 启动 `backend/mcp-server`，确认 `8090` 端口可访问。
- [ ] 用真实 MCP 客户端连接 `http://localhost:8090/mcp/sse`。
- [ ] 验证客户端能执行 `tools/list`。
- [ ] 选择 1 个只读工具做真实调用 smoke。
- [ ] 把客户端名称、连接方式、结果截图或日志补到 `docs/mcp-client.md`。
- [x] 提供 HTTP smoke 脚本：`tools/smoke_mcp_http.ps1`。

验收口径：真实 MCP 客户端能看到工具列表，并至少成功调用一个只读工具。

### 2. 真实模型服务商评测

- [ ] 配置 DashScope 或兼容 OpenAI endpoint 的真实 API key。
- [ ] 扩展评测运行器，记录真实 LLM 延迟、服务商 token 或近似 token。
- [ ] 跑完整 30 条 case，输出新报告。
- [ ] 把真实准确率、延迟和 token 写入 README 与 `docs/RESUME.md`。
- [x] Eval runner 支持 `--provider openai-compatible`、`--model`、`--api-url`、`--api-key-env` 和 provider metadata。

验收口径：README 中明确区分“离线评测指标”和“真实模型服务商评测指标”。

### 3. 真实 MySQL / Redis / AMap 验收

- [ ] 初始化 `citycopilot` 数据库并导入 `citycopilot.sql`。
- [ ] 启动 Redis，验证登录、店铺、优惠券、订单基础链路。
- [ ] 配置 AMap key，验证地址解析、POI 搜索、批量 Geo 任务。
- [ ] 记录失败项、必要环境变量和修复方式。

验收口径：核心业务 API、Agent 工具调用和 Geo 工具能在真实依赖下跑通。

### 4. 运营 Agent 真实数据调参

- [ ] 用 Redis Stream 投递 `geo_calibration`、`order_anomaly_scan`、`daily_report`。
- [ ] 验证 live 工具网关执行结果写入 `agent.ops.results`。
- [ ] 根据真实数据库字段和业务规模调整默认 payload。
- [ ] 补一段 README 或运维文档说明如何触发和查看结果。
- [x] 提供最近结果查询接口：`GET /agent/ops/results?limit=20`。
- [x] 提供 Ops smoke 脚本：`tools/smoke_ops_agent.ps1`。

验收口径：运营 Agent 不只是生成计划，而是能在真实数据上执行并留下结果摘要。

### 5. 压测与可观测性验收

- [ ] 启动 Prometheus，确认能 scrape `/actuator/prometheus`。
- [ ] 导入 Grafana dashboard JSON，确认工具和 LLM 指标可见。
- [ ] 对秒杀或关键工具做一次小规模压测。
- [ ] 记录 QPS、p95、错误率和限流/熔断效果。
- [x] 提供 Prometheus 指标 smoke 脚本：`tools/smoke_observability.ps1`。

验收口径：README 或简历说明里有一组真实压测和可观测性数字。

### 6. Demo 和简历材料

- [ ] 录制 2-3 分钟 demo。
- [ ] Demo 覆盖用户对话、工具卡片、Plan-Execute、Trace 看板。
- [ ] 可选展示 MCP 客户端调用。
- [ ] 将 demo 链接、关键截图或说明补到 README。
- [ ] 根据真实指标更新 `docs/RESUME.md`。

验收口径：项目可以作为 GitHub 简历项目直接展示。

## 后续清理候选

- [ ] 将 `AiChatRequest` 从 `core` 迁到 `agent`，减少 core 对 Agent 请求模型的认知。
- [x] 给 MCP HTTP `tools/list` 增加基于登录用户的工具过滤。
- [x] 增加 live eval 的失败原因分布。
- [x] 给运营 Agent 增加最近结果查询 API。
- [x] 梳理并移除 `backend/core/src/main/resources/nginx-1.18.0/html` 历史静态页面。

## 验证命令

- 后端全量测试：`.\.tools\apache-maven-3.9.9\bin\mvn.cmd -q test`
- 后端完整构建：`.\.tools\apache-maven-3.9.9\bin\mvn.cmd -q clean verify`
- MCP 模块测试：`.\.tools\apache-maven-3.9.9\bin\mvn.cmd -q -pl backend/mcp-server -am test`
- 前端构建：在 `frontend` 目录运行 `npm run build`
- Eval gate：运行 `backend/eval` 的 `EvalRunner`，输出 `backend/eval/reports/citycopilot-v01-latest.md`
- 文本检查：`git diff --check`

## 执行规则

- 每次只推进一个可验证任务组。
- 涉及前端时必须跑 `npm run build`。
- 涉及 Java 后端时必须跑 Maven 测试或完整构建。
- 涉及文档指标时必须写清楚是离线指标还是真实模型服务商指标。
- 外部依赖任务如果缺少 key、数据库、Redis、客户端或录屏环境，不伪造结果，保留为待验收项。

## 最近提交

- `ff46e9b feat(eval): summarize failure reasons`
- `a93d64f feat(mcp): filter listed tools by user permissions`
- `73c0d93 feat(agent): add acceptance smoke tooling`
- `fd474fb docs(plan): translate checklist to Chinese`
- `8c68416 docs(plan): simplify implementation checklist`
- `7ffc042 docs(plan): refresh mcp remaining work`
- `b80b99e docs(mcp): add sse client smoke config`
- `f381c49 chore: remove legacy ai assistant fallback`
- `999addc feat(agent): execute ops tools through gateway`
- `2f9d7b1 feat(eval): report keyword and token metrics`
- `a5889cc feat(agent): add structured ops task processors`
- `9767023 feat(mcp): expose registry as spring ai tool callbacks`
