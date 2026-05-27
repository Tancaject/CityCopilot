# CityCopilot 设计文档

- 文档日期：2026-05-24
- 项目代号：CityCopilot（原 hmdp）
- 目标投递：2026 校招 Java 后端（含 AI Agent 方向）
- 时间窗口：4-6 周（v0.1 MVP 第 3 周末投递，后续面试间隙打磨）

## 0. 当前实施状态（2026-05-25）

详细执行状态以 `docs/superpowers/plans/2026-05-24-citycopilot-implementation.md` 的 **Current Execution Status** 为准。当前进度摘要如下：

<<<<<<< HEAD
- **已完成**：Maven 本地解阻、多模块 `backend/core`、`backend/agent`、`backend/mcp-server`、`backend/eval`、Spring bean 扫描式 Tool Registry、ToolGateway 校验/权限/审计/标准错误、ReAct、基础 Plan-Execute、`/agent/chat` SSE、前端工具卡片、trace 列表/详情、HTTP MCP-style tools/list/tools/call、27 个已注册工具名、Resilience4j、单 YAML 套件 10 条 eval case、Actuator/Prometheus/Grafana JSON、README/RESUME 初版。
=======
- **已完成**：Maven 本地解阻、多模块 `backend/core`、`backend/agent`、`backend/mcp-server`、`backend/eval`、Spring bean 扫描式 Tool Registry、ToolGateway 校验/权限/审计/标准错误、ReAct、LLM-first Plan-Execute-Replan、Critic Agent 高风险拦截、`/agent/chat` SSE、前端工具卡片、trace 列表/详情、Spring AI MCP tool callbacks、HTTP MCP-style tools/list/tools/call、MCP 8090 配置和 HTTP/SSE 客户端样例、27 个已注册工具名、Resilience4j、Ops Agent live ToolGateway 执行、单 YAML 套件 30 条 eval case、离线 eval 指标报告、Actuator/Prometheus/Grafana JSON、工具/LLM 自定义 metrics、README/RESUME 初版。
>>>>>>> 4c697ae (新增 /shop/discovery 统一发现接口：ShopController.java (line 107))
- **已修复的安全问题**：MCP 不再信任调用方权限、Agent 异步线程保留用户身份、trace 后端加 `audit:read`、秒杀退款不再破坏一人一单、Redisson/stream worker 不可用时不再先扣库存、null 工具参数不再打断审计/trace。
- **仍未完成**：MCP 客户端真实连通验收、真实 provider 准确率/延迟/token 指标、真实 MySQL/Redis/AMap/DashScope 环境验收、demo 视频。

## 1. 项目身份

### 1.1 一句话定位

CityCopilot 是一个基于 Spring AI Alibaba 的"动作型"AI Agent 本地生活平台。用户用自然语言表达意图（"附近 50 块以内有券的火锅，帮我下单"），由 Agent 自主完成意图理解 → 工具调用 → 多步推理 → 事务性执行的全流程。后端通过统一 Tool Registry 将业务能力同时暴露为 LLM Function Calling 工具与 MCP server tools，使整个本地生活系统可被 Claude / Cursor 等任意 MCP 客户端直接调用。

### 1.2 与其他项目的差异化

- 与原 hmdp 教程项目：身份、命名、技术叙事、AI 能力深度均不同；hmdp 的业务代码被复用为 Agent 的工具底层。
- 与同作者 TradingAgent 项目：本项目走 Acting Agent 路线（Tool Calling + 系统级事务），TradingAgent 走 Reasoning Agent 路线（RAG + 文本检索）；两个项目互补不重复，简历可同时挂出。

### 1.3 校招卖点（按面试官关注度排序）

1. Spring AI Alibaba 工业级框架使用（非自拼 RestTemplate）
2. ReAct + Plan-Execute-Replan 双模式 Agent
3. 自研 Tool Registry：一份 `@AgentTool` 注解同时驱动 Function Calling 与 MCP server
4. MCP server 实现（2025-2026 大厂内部高频术语）
5. Multi-Agent 编排（Dialog / Ops / Critic）
6. Eval Harness 出可写进简历的客观指标（准确率、延迟、token）
7. 保留底层硬功夫：Redis Lua 秒杀、分布式锁、Redis GEO、Resilience4j、Micrometer + Prometheus

## 2. 整体架构

### 2.1 系统视图

```text
                ┌─────────────────────────────────────────┐
                │  前端 Vue 3 SPA (现有)                  │
                │  - 用户对话 UI (SSE 流式)               │
                │  - Agent traces 可视化看板 (新增)       │
                └─────────────────┬───────────────────────┘
                                  │ HTTP + SSE
                ┌─────────────────▼───────────────────────┐
                │  Agent 层 (agent 模块)                  │
                │  ┌────────────────────────────────────┐ │
                │  │ Orchestrator: ReAct / Plan-Execute │ │
                │  └──────┬────────────────────┬────────┘ │
                │         │                    │          │
                │  ┌──────▼─────┐      ┌───────▼───────┐  │
                │  │ Dialog Agt │      │ Ops Agent (异步)│  │
                │  └────────────┘      └────────────────┘  │
                │                          ▲              │
                │                          │ Redis Stream │
                │  ┌────────────────────────────────────┐ │
                │  │ Tool Registry + JSON Schema 校验   │ │
                │  │ Resilience4j (限流/熔断/重试)      │ │
                │  └──────┬────────────────────┬────────┘ │
                └─────────┼────────────────────┼──────────┘
                          │ in-process call    │ stdio/HTTP
                ┌─────────▼──────────┐  ┌──────▼──────────┐
                │ core 模块 (业务)    │  │ mcp-server 模块  │
                │ Shop/Voucher/      │  │ tools/list       │
                │ Order/Geo/Blog/    │  │ tools/call       │
                │ Audit/User         │  │ → Claude/Cursor  │
                └─────────┬──────────┘  └──────────────────┘
                          │
                ┌─────────▼──────────┐  ┌──────────────────┐
                │ MySQL + Redis      │  │ LLM Provider     │
                │ (现有, 含分布式锁、 │  │ Spring AI        │
                │  Lua、Geo、Stream) │  │ Alibaba          │
                └────────────────────┘  └──────────────────┘

旁路：
  eval 模块 (CLI + yaml 用例集)
    └ 输出 Markdown 报告 + JSON 供前端 traces 看板渲染
  Micrometer → Prometheus → Grafana
```

### 2.2 Maven 多模块拆分

```text
city-copilot/
 ├─ backend/
 │  ├─ core/         业务实体与服务（原 hmdp 主体代码）
 │  │                 Shop/Voucher/Order/Geo/Blog/User/Audit
 │  │                 现有秒杀 Lua、分布式锁、Redis GEO 均在此
 │  ├─ agent/        Agent 主体和主后端入口
 │  │                 Orchestrator、Tool Registry、@AgentTool 注解、
 │  │                 ReAct / Plan-Execute 实现、Multi-Agent、
 │  │                 SSE 接口、Traces 落 Redis
 │  ├─ mcp-server/   MCP 协议实现
 │  │                 复用 agent 模块的 Tool Registry
 │  │                 stdio + HTTP 双协议
 │  └─ eval/         评估子系统
 │                    CLI、yaml 用例集、报告生成
 └─ deploy/       部署与可观测
                  Docker compose、Prometheus 配置、
                  Grafana 看板 JSON
```

**依赖方向**：`mcp-server` → `agent` → `core`；`eval` 依赖 `agent`；模块间禁止反向依赖。

### 2.3 关键设计决策

1. **不做 RAG / 不引入向量数据库**：与 TradingAgent 明确分工，避免简历重复。
2. **Agent 编排 = ReAct + Plan-Execute-Replan 双模式**：router 按意图复杂度自动选择。
3. **MCP server 与业务服务共享 Tool Registry**：一份注解，两处生效。
4. **现有 hmdp 业务代码不重写**：仅在 service 方法上加 `@AgentTool` 注解。
5. **Spring AI Alibaba 替换现有手写 RestTemplate LLM 调用**：对标校招标杆项目（yu-ai-agent / OnCall Agent）。
6. **模块化单体而非真微服务**：保留 Agent 主线深度，用模块边界 + 异步事件 + 可观测性 + 熔断限流向面试官传达"我懂分布式"的信号词。

## 3. 核心组件详细设计

### 3.1 Tool Registry

#### 注解定义

```java
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface AgentTool {
    String name();                  // LLM 看到的工具名
    String description();           // 工具描述（影响 LLM 选择准确率）
    String[] requiredPermissions() default {};  // 复用现有 PermissionCodes
    int rateLimitPerMinute() default 60;
    boolean idempotent() default true;
}
```

#### 注册流程

1. Spring 启动时扫描所有 `@AgentTool` 方法
2. 对每个方法生成 JSON Schema（参数类型 + 注解 `@Schema` 描述）
3. 同时注册到：
   - LLM Function Calling 列表（Spring AI Alibaba 的 `FunctionCallback`）
   - MCP server 的 `tools/list` 响应
4. 调用统一过 `ToolGateway.invoke(toolName, args, ctx)`：
   - 参数 JSON Schema 校验
   - 权限码校验（复用现有 `PermissionPolicy`）
   - Resilience4j 限流 / 熔断 / 重试
   - 反射调用业务方法
   - 错误标准化为 `ToolError(code, message, retryable)`
   - 异步落 trace 到 Redis

#### v0.2 工具清单（15-20 个）

| 工具名 | 业务方法 | 模块 |
|---|---|---|
| search_nearby_shops | ShopService.searchNearby | core |
| search_shops_by_type | ShopService.queryShopByType | core |
| get_shop_detail | ShopService.queryById | core |
| list_vouchers_by_shop | VoucherService.queryVoucherOfShop | core |
| place_seckill_order | VoucherOrderService.seckillVoucher | core |
| get_my_orders | VoucherOrderService.listOfCurrentUser | core |
| pay_order / cancel_order / refund_order | VoucherOrderService.* | core |
| follow_user / unfollow_user | FollowService.* | core |
| get_follow_feed | BlogService.queryBlogOfFollow | core |
| get_blog_detail | BlogService.queryBlogById | core |
| publish_blog | BlogService.saveBlog | core |
| geocode_shop / poi_match_shop | AmapGeoService.* | core |
| start_batch_geocode | ShopGeoBatchService.create | core |
| query_audit_logs | OperationLogService.query | core |
| ops_summary | AdminController.opsSummary | core |

### 3.2 Orchestrator

#### Router 分类

```java
enum Mode { REACT, PLAN_EXECUTE }

Mode classify(UserMessage msg) {
    // 启发式 + LLM 二次确认：
    // - 包含多动词且有时序词（"然后/接着/最后"）→ PLAN_EXECUTE
    // - 单一意图（"附近有什么"、"我的订单"）→ REACT
    // - 写操作链路（下单 + 支付 + 提醒）→ PLAN_EXECUTE
}
```

#### ReAct 模式

```text
loop (max 5 steps):
    thought = LLM(system_prompt + history + observations + tools_schema)
    if thought.is_final_answer:
        break
    tool_call = thought.tool_call
    observation = ToolGateway.invoke(tool_call)
    history.append(thought, observation)

防循环：相同 tool+args 重复 ≥ 2 次直接中止
防幻觉：observation 必须 schema-valid 才回填
预算：累计 token 超阈值（默认 8k）触发 summarize-then-continue
```

#### Plan-Execute-Replan 模式

```text
plan = LLM(plan_prompt + tools_schema)
        // 返回 JSON: [{ tool, args, expects_output_used_in: [step_idx, ...] }]

for step in plan:
    args = resolve_refs(step.args, prior_outputs)
    if step is high_risk (下单/支付/退款):
        critic_pass = CriticAgent.check(plan_so_far, step)
        if not critic_pass: replan()
    output = ToolGateway.invoke(step.tool, args)
    if output.error and output.retryable:
        replan()  // LLM 看到错误后重新出计划
    prior_outputs[i] = output

final = LLM(answer_prompt + prior_outputs)
```

### 3.3 Multi-Agent

| Agent | 触发 | 模式 | 输出 |
|---|---|---|---|
| Dialog Agent | 用户 HTTP 请求 | ReAct 或 Plan-Execute | SSE 流式 |
| Ops Agent | Redis Stream `agent.ops.tasks` 消息 / 定时器 | Plan-Execute（无人交互） | 落库 + 审计 |
| Critic Agent | Dialog Agent 在写操作前调用 | 单次 LLM 评审 | pass / reject + 原因 |

**Ops Agent 用例**：
- 定时跑批量坐标校准（夜间）
- 监控订单异常（长时间未支付等）并写入告警审计
- 生成商家日报（命中订单数、退款率、热门券）

### 3.4 MCP Server

#### 协议覆盖（基于 MCP 当前规范）

- `initialize` / `initialized`
- `tools/list`：返回 Tool Registry 全集
- `tools/call`：转发给 ToolGateway，复用同一限流 / 审计链路
- `prompts/list`（v0.2 可选）：返回项目预置 prompt 模板
- `resources/list`（v0.2 可选）：暴露店铺类目等只读资源

#### 传输

- **stdio**：默认，给 Claude Desktop / Cursor 用
- **HTTP / SSE**：给浏览器端或远程 MCP client 用

#### 安全

- stdio 模式默认本地、无认证
- HTTP 模式必须带 Bearer token；复用现有 token 拦截器；并以 token 对应用户的权限码做工具过滤（无权工具直接在 `tools/list` 时隐藏）

### 3.5 Eval Harness

#### 用例格式

```yaml
# backend/eval/src/main/resources/cases/seckill_flow.yaml
- name: nearby_hotpot_with_voucher_order
  input: "附近 50 块以内有券的火锅，帮我下单一个"
  context:
    user_id: 1001
    location: { x: 120.149, y: 30.282 }   # 杭州
  expect_mode: PLAN_EXECUTE
  expect_tools:
    - tool: search_nearby_shops
      args_constraints:
        type: contains("火锅")
        max_price: <= 50
        has_voucher: true
    - tool: list_vouchers_by_shop
    - tool: place_seckill_order
  expect_keywords: ["下单成功", "订单号"]
  expect_no_keywords: ["抱歉", "无法"]
```

#### 指标输出（每次跑后写入 `backend/eval/reports/YYYY-MM-DD-HHmm.md`）

| 指标 | 说明 |
|---|---|
| 工具调用准确率 | 期望工具被调用的占比 |
| 答案命中率 | 关键词命中 / 总用例 |
| 平均延迟 | 端到端 ms |
| 平均 token | input + output；当前离线 runner 使用启发式估算，真实 provider token 用量待接入 |
| 失败原因分布 | LLM 决策错误 / 工具错误 / 超时 / 参数错 |

#### 使用方式

```bash
mvn -pl backend/eval test                       # 跑全集
mvn -pl backend/eval test -Dcase=seckill_flow   # 跑单条
mvn -pl backend/eval test -DllmModel=qwen-plus  # 切换模型对比
```

## 4. 错误处理与降级

| 场景 | 处理 |
|---|---|
| LLM provider 不可达 | Resilience4j 熔断；前端 SSE 推送 `provider_down` 事件；提示用户稍后重试 |
| LLM 返回非法 JSON（工具调用解析失败） | 重试 1 次；仍失败则降级为纯回答模式（不调工具，直接告知做不到） |
| 工具调用超时 | Resilience4j 中断；observation 写入 `{ error: "timeout" }`；ReAct 模式下交给 LLM 决定下一步；Plan-Execute 模式下触发 replan |
| 工具调用业务报错 | 错误标准化为 `ToolError`；retryable=true 时 Resilience4j 重试；retryable=false 直接回传 LLM 让其规划替代路径 |
| 写操作权限不足 | 不进入工具调用层；直接告知用户 |
| Agent 步数超限 | 强制收束；返回"已达最大步数"+ 已收集到的部分信息 |
| 上下文 token 超预算 | 触发 summarize-then-continue：用 LLM 把已有 history 压缩为摘要后继续 |

## 5. 安全

### 5.1 Prompt Injection

- 用户输入与系统提示词在结构上分离（用 Spring AI 的消息角色机制）
- 工具描述与工具结果**不**直接信任用户文本注入（结果按 schema 校验）
- Critic Agent 在高风险操作前做二次校验，可拒绝
- 不允许 LLM 自由生成 SQL；所有写操作必须经 ToolGateway 受控接口

### 5.2 权限

- 工具级权限码（复用现有 `PermissionCodes`）；用户无权工具在 `tools/list` 时不可见
- MCP HTTP 模式必须带 token
- Eval 用例运行使用专用测试账号，禁止误操作生产数据（eval 模块默认连测试库）

### 5.3 审计

- 所有工具调用落 `tb_operation_log`（现有审计表）
- Trace 落 Redis（短期保留，默认 7 天）
- 高风险操作（下单 / 退款 / 批量任务）额外标记 `risk_level`

## 6. 时间线与 MVP 切片

### 6.1 三个版本里程碑

| 版本 | 时间 | 可投性 | 可演示性 |
|---|---|---|---|
| v0.1 MVP | 第 2-3 周末 | 可投 | 可演示基础链路 |
| v0.2 完整版 | 第 4-5 周末 | 全亮点 | 可演示完整链路 |
| v0.3 打磨 | 持续（面试间隙） | 数字优化 | 看板美化 + 压测图 |

### 6.2 周计划

**第 1 周**：基础重构 + 改名
- Maven 拆 4 模块
- 仓库改名 `hmdp` → `city-copilot`
- 引入 Spring AI Alibaba，跑通 `/agent/ping`
- 旧 `AiAssistantService` 已下线，前端统一走 `/agent/chat`
- 新 README

**第 2 周**：Tool Registry + ReAct + SSE（= v0.1 MVP）
- `@AgentTool` 注解 + 扫描器 + Schema 生成
- ReAct loop 实现 + 单元测试
- SSE 流式响应（Spring WebFlux SseEmitter）
- 5 个工具：search_nearby_shops / get_shop_detail / list_vouchers_by_shop / get_my_orders / find_user_location
- 前端对话页 SSE 接入 + tool call 卡片渲染
- 基础 traces 落 Redis

**第 3 周**：Plan-Execute + MCP server + 工具补全
- Plan-Execute-Replan + Router
- 工具数量补到 15-20 个
- MCP server（stdio + HTTP）
- Resilience4j 接入 ToolGateway
- Claude Desktop 配置 demo 文档

**第 4 周**：Ops Agent + Eval + 可观测性（= v0.2）
- Redis Stream `agent.ops.tasks` + Ops Agent worker
- Eval Harness CLI + 30 条 yaml 用例
- Micrometer + Prometheus 端点 + Grafana 看板 JSON
- Traces 看板前端页面
- Critic Agent（可选）

**第 5-6 周**（面试已开始）：打磨
- 秒杀压测对比图
- Eval 数字优化
- demo 视频
- 1-2 篇技术博客

### 6.3 紧急可投版本（如果第 3 周末必须投）

砍：Plan-Execute / Multi-Agent / Critic / Grafana
留：Tool Registry + ReAct + SSE + 5-8 工具 + 简版 MCP + Eval 10 用例

## 7. 简历产出物

### 7.1 项目描述（v0.2 后版本）

```markdown
**CityCopilot** — Acting AI Agent for Local Life Services
基于 Spring AI Alibaba 的"动作型" AI Agent 本地生活平台（Java 17, Spring Boot 3.2）

- 模块化单体架构（4 个 Maven 模块），自研 Tool Registry 通过 @AgentTool 注解
  将 20+ 业务接口同时暴露为 LLM Function Calling 工具与 MCP server tools，
  一份注解两处生效
- Orchestrator 支持 ReAct + Plan-Execute-Replan 双模式，按意图复杂度自动路由；
  SSE 流式响应；防循环 / 防幻觉 / 上下文 token 预算
- 多 Agent 协作：Dialog Agent（同步对话）+ Ops Agent（Redis Stream
  驱动的异步运营任务）
- 实现 MCP server（stdio + HTTP），业务能力可被 Claude / Cursor
  等任意 MCP 客户端直连调用
- Eval Harness：30 条离线用例，模式准确率 100%，工具调用准确率 100%，关键词命中率 100%，平均 eval 延迟 1 ms，平均估算 token 27；真实 provider 指标待接入
- 底层：Redis Lua 秒杀（QPS A→B）、Redisson 分布式锁、Redis GEO、
  Resilience4j 限流熔断
- 可观测：Micrometer + Prometheus + Grafana 看板覆盖业务 / Agent / 工具维度
```

### 7.2 配套物料

- GitHub README（含架构图、demo 视频链接、Eval 报告示例）
- 2-3 分钟 demo 视频（mp4 挂仓库）
- 1-2 篇技术博客（"实现 MCP server 的工程实践"、"ReAct vs Plan-Execute 在本地生活场景的选择"）
- Grafana 看板截图
- 压测对比图（Resilience4j 接入前后秒杀 QPS）

## 8. 风险与开放问题

| 风险 | 概率 | 缓解 |
|---|---|---|
| Spring AI Alibaba 文档不完善导致开发卡顿 | 中 | 已有标杆项目 yu-ai-agent 可参考；准备好 Spring AI（非 Alibaba 分支）作备选 |
| Spring AI Alibaba 与现有 Spring Boot 3.2.2 / MyBatis-Plus / Redisson / Hutool 依赖冲突 | 中 | 第 1 周首日先做依赖兼容性 POC（mvn dependency:tree），冲突时优先升级 Spring AI Alibaba 而非降级业务依赖；版本固化到 pom 后再继续 |
| MCP 规范快速演化导致实现过时 | 中 | 跟随 modelcontextprotocol.io 官方 spec；保留版本号 |
| LLM provider 选型（DashScope / 百炼 / OpenAI 兼容） | 低 | 通过配置切换；Eval 模块支持模型对比 |
| Plan-Execute 实现复杂度被低估 | 中 | MVP 模式可降级为只 ReAct；Plan-Execute 在第 3 周末完成最低可用版本 |
| Eval 用例编写工作量大 | 中 | 用例从 10 条起步（v0.1.5），逐步扩到 30；先有数字再优化数字 |
| 旧 `AiAssistantService` 规则代码价值如何处理 | 已决策 | 已移除旧服务和 `/ai/chat`，以 `/agent/chat`、Tool Registry、ReAct/Plan-Execute 作为唯一对话链路 |

## 9. 待用户决定的开放项

| 项 | 默认 | 备选 |
|---|---|---|
| 最终项目名 | CityCopilot | LocaLife AI / 城知 / 街知 |
| LLM Provider | DashScope（阿里云百炼，国内稳定） | 百度文心 / DeepSeek / OpenAI 兼容 |
| MCP HTTP 端口 | 8090 | 由 config 决定 |
| Eval 测试数据库 | 独立 schema `citycopilot_eval` | 用同一 DB + 测试账号 |
| Critic Agent | v0.2 可选 | v0.1 可砍 |
