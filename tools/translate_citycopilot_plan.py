from pathlib import Path
import re

path = Path("docs/superpowers/plans/2026-05-24-citycopilot-implementation.md")
text = path.read_text(encoding="utf-8")

exact = {
    "# CityCopilot Implementation Plan": "# CityCopilot 实施计划",
    "> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.": "> **给 Agent 执行者：** 必须使用子技能：推荐 `superpowers:subagent-driven-development`，或使用 `superpowers:executing-plans`，按任务逐项执行本计划。步骤使用复选框（`- [ ]`）语法跟踪进度。",
    "**Goal:** Transform the hmdp tutorial-clone project into CityCopilot, a Spring AI Alibaba based \"acting\" AI Agent platform for local life services, suitable for 2026 校招 Java backend resume. v0.1 MVP shippable by end of week 3; v0.2 full version by week 5.": "**目标：** 将 hmdp 教程复刻项目改造成 CityCopilot：一个基于 Spring AI Alibaba、面向本地生活服务的“可行动”AI Agent 平台，适合写入 2026 校招 Java 后端简历。第 3 周末交付 v0.1 MVP；第 5 周交付 v0.2 完整版。",
    "**Architecture:** Modular monolith with 4 Maven modules (`core`, `agent`, `mcp-server`, `eval`). Tool Registry driven by `@AgentTool` annotation produces both LLM Function Calling tools and MCP server tools from a single source. Orchestrator supports ReAct (single-loop) and Plan-Execute-Replan (multi-step) modes, selected by router. Dialog Agent serves users via SSE; Ops Agent consumes Redis Stream for async work; Critic Agent guards high-risk operations.": "**架构：** 采用 4 个 Maven 模块（`core`、`agent`、`mcp-server`、`eval`）的模块化单体。由 `@AgentTool` 注解驱动的 Tool Registry 作为单一来源，同时生成 LLM Function Calling 工具和 MCP Server 工具。Orchestrator 支持 ReAct（单循环）和 Plan-Execute-Replan（多步骤）两种模式，由 Router 选择。Dialog Agent 通过 SSE 服务用户；Ops Agent 消费 Redis Stream 处理异步工作；Critic Agent 保护高风险操作。",
    "**Tech Stack:**": "**技术栈：**",
    "- Java 17, Spring Boot 3.2.2 (existing), MyBatis-Plus, Redisson, Hutool, MySQL, Redis": "- Java 17、Spring Boot 3.2.2（现有）、MyBatis-Plus、Redisson、Hutool、MySQL、Redis",
    "- Spring AI Alibaba (starter-dashscope) for LLM access": "- Spring AI Alibaba（starter-dashscope）用于 LLM 接入",
    "- Spring AI MCP server starter for MCP protocol": "- Spring AI MCP Server Starter 用于 MCP 协议",
    "- Resilience4j for tool-call resilience": "- Resilience4j 用于工具调用韧性治理",
    "- Micrometer + Prometheus + Grafana for observability": "- Micrometer + Prometheus + Grafana 用于可观测性",
    "- Vue 3 + TypeScript + Vite (existing frontend) extended with SSE client and traces dashboard": "- Vue 3 + TypeScript + Vite（现有前端），扩展 SSE 客户端和 Trace 看板",
    "**Reference spec:** `docs/superpowers/specs/2026-05-24-citycopilot-design.md`": "**参考规格：** `docs/superpowers/specs/2026-05-24-citycopilot-design.md`",
    "## Current Execution Status (updated 2026-05-24)": "## 当前执行状态（更新于 2026-05-24）",
    "This section is the source of truth for the current implementation state. The detailed phase checklist below is kept as the original execution plan and implementation trace.": "本节是当前实现状态的事实来源。下方详细阶段清单保留为原始执行计划和实现轨迹。",
    "### Priority Status": "### 优先级状态",
    "| Priority | Plan item | Status | Evidence / notes |": "| 优先级 | 计划项 | 状态 | 证据 / 备注 |",
    "### Security Review Fixes Completed": "### 已完成的安全评审修复",
    "### Still Open": "### 尚未完成",
    "### Latest Verification": "### 最新验证",
    "Additional jar probes passed:": "额外 Jar 探针已通过：",
    "## Phase Map": "## 阶段总览",
    "| Week | Phases | Cumulative state |": "| 周次 | 阶段 | 累积状态 |",
    "## Self-Review Checklist": "## 自审清单",
    "Before considering this plan executed, the reviewer should verify:": "在认为本计划已执行完成前，评审者应验证：",
    "## Execution Handoff": "## 执行交接",
    "Two execution options:": "两种执行选项：",
}

phase_titles = {
    "Pre-flight Compatibility POC": "预检兼容性 POC",
    "Git initialization": "Git 初始化",
    "Maven multi-module restructure": "Maven 多模块重构",
    "Rename hmdp → city-copilot": "将 hmdp 重命名为 city-copilot",
    "Spring AI Alibaba integration smoke test": "Spring AI Alibaba 集成冒烟测试",
    "`@AgentTool` annotation and Tool Registry skeleton": "`@AgentTool` 注解与 Tool Registry 骨架",
    "JSON Schema generator for tool parameters": "工具参数 JSON Schema 生成器",
    "ToolGateway — invocation with validation, permissions, errors, audit": "ToolGateway：带校验、权限、错误和审计的调用入口",
    "Annotate first 5 business methods as @AgentTool": "将首批 5 个业务方法标注为 @AgentTool",
    "LLM Client wrapper + Spring AI Function Calling integration": "LLM Client Wrapper + Spring AI Function Calling 集成",
    "ReAct Executor — TDD": "ReAct Executor：TDD",
    "Trace persistence to Redis": "Trace 持久化到 Redis",
    "SSE AgentController + frontend integration": "SSE AgentController + 前端集成",
    "Plan-Execute-Replan Executor + Router": "Plan-Execute-Replan Executor + Router",
    "Expand tool catalog to 15-20": "将工具目录扩展到 15-20 个",
    "MCP Server": "MCP Server",
    "Resilience4j integration": "Resilience4j 集成",
    "Ops Agent (Redis Stream consumer)": "Ops Agent（Redis Stream Consumer）",
    "Critic Agent (optional)": "Critic Agent（可选）",
    "Eval Harness": "Eval Harness",
    "Observability (Micrometer + Prometheus)": "可观测性（Micrometer + Prometheus）",
    "Traces Dashboard Frontend": "Traces 看板前端",
    "Documentation, demo video, resume description": "文档、演示视频、简历描述",
    "Cleanup and graduate as portfolio": "清理并打磨成作品集项目",
}

task_titles = {
    "Create scratch branch and POC pom": "创建临时分支和 POC pom",
    "Initialize repository": "初始化仓库",
    "Move existing source into a `core` module": "将现有源码移动到 `core` 模块",
    "Rename Java package `com.hmdp` → `com.citycopilot.core`": "重命名 Java 包 `com.hmdp` → `com.citycopilot.core`",
    "Rewrite README": "重写 README",
    "Wire Spring AI Alibaba in `agent` module": "在 `agent` 模块接入 Spring AI Alibaba",
    "Define the annotation and ToolMeta record": "定义注解和 ToolMeta record",
    "ToolRegistry — TDD": "ToolRegistry：TDD",
    "Spring-driven auto-scan": "Spring 驱动自动扫描",
    "Schema generation — TDD": "Schema 生成：TDD",
    "ToolError, ToolCallContext, ToolResult records": "ToolError、ToolCallContext、ToolResult records",
    "ToolGateway happy path — TDD": "ToolGateway 正常路径：TDD",
    "Add JSON Schema validation step": "添加 JSON Schema 校验步骤",
    "Identify and annotate": "识别并添加注解",
    "Spring AI FunctionCallback bridge": "Spring AI FunctionCallback 桥接",
    "Executor interface + ReAct implementation": "Executor 接口 + ReAct 实现",
    "Real LlmClient implementation using Spring AI": "使用 Spring AI 的真实 LlmClient 实现",
    "TraceWriter and TraceReader": "TraceWriter 和 TraceReader",
    "Backend SSE endpoint": "后端 SSE 端点",
    "Frontend SSE client": "前端 SSE 客户端",
    "Plan data model": "Plan 数据模型",
    "PlanExecuteExecutor — TDD": "PlanExecuteExecutor：TDD",
    "Annotate write-side and admin operations": "标注写侧和管理端操作",
    "Bring up the starter": "启动 Starter",
    "Wrap ToolGateway with Resilience4j decorators": "用 Resilience4j Decorator 包装 ToolGateway",
    "LLM provider circuit breaker": "LLM Provider 熔断器",
    "Stream consumer": "Stream Consumer",
    "CriticAgent + plan-time hook": "CriticAgent + 计划时 Hook",
    "Eval case model + runner": "Eval 用例模型 + Runner",
    "Wire Micrometer + Actuator": "接入 Micrometer + Actuator",
    "Backend traces API": "后端 Traces API",
    "Frontend page": "前端页面",
    "README upgrade with metrics": "用指标升级 README",
}

step_titles = {
    "Make a scratch directory": "创建临时目录",
    "Write a minimal POC pom that mirrors existing constraints + adds AI deps": "编写最小 POC pom，复刻现有约束并加入 AI 依赖",
    "Add a minimal Application class": "添加最小 Application 类",
    "Build to verify all deps resolve and there are no conflicts": "构建以验证所有依赖可解析且无冲突",
    "Record the working version pins": "记录可用版本锁定信息",
    "Tear down POC": "拆除 POC",
    "Verify not already a git repo": "确认当前还不是 Git 仓库",
    "Initialize": "初始化",
    "Augment .gitignore for IDE / build / OS artifacts": "补充 IDE / 构建 / 操作系统产物到 .gitignore",
    "Confirm secrets are not staged": "确认密钥没有被暂存",
    "First commit — record current state before restructure": "首次提交：记录重构前当前状态",
    "Move existing src into core/": "将现有 src 移入 core/",
    "Create `core/pom.xml` mirroring current single-module pom but as a child": "创建 `core/pom.xml`，作为子模块复刻当前单模块 pom",
    "Rewrite root `pom.xml` as aggregator parent": "将根 `pom.xml` 改写为聚合父 POM",
    "Create empty stub modules so aggregate build works": "创建空桩模块，确保聚合构建可运行",
    "Add stub package-info.java in each new module to keep them as valid Java sources": "在每个新模块添加 package-info.java 桩文件，保证是有效 Java 源目录",
    "Aggregate build": "聚合构建",
    "Run existing tests (if any) to confirm no regression": "运行现有测试（如有），确认无回归",
    "Bulk move directories": "批量移动目录",
    "Find-and-replace package declarations and imports": "查找并替换 package 声明和 import",
    "Update MyBatis mapper XML namespaces": "更新 MyBatis Mapper XML 命名空间",
    "Update `application.yml` MyBatis-Plus type-aliases-package and any explicit base-package references": "更新 `application.yml` 中 MyBatis-Plus type-aliases-package 以及所有显式 base-package 引用",
    "Rename main application class": "重命名主应用类",
    "Update any `@ComponentScan` / `@MapperScan` annotations": "更新所有 `@ComponentScan` / `@MapperScan` 注解",
    "Rebuild and run tests": "重新构建并运行测试",
    "Replace README with project pitch + architecture overview": "用项目 Pitch 和架构概览替换 README",
    "Add Spring AI Alibaba dep to `agent/pom.xml`": "向 `agent/pom.xml` 添加 Spring AI Alibaba 依赖",
    "Add agent-specific YAML config": "添加 agent 专用 YAML 配置",
    "Import agent profile into core's `application.yml`": "将 agent profile 导入 core 的 `application.yml`",
    "Add ChatClient bean": "添加 ChatClient Bean",
    "Add /agent/ping controller for smoke test": "添加 /agent/ping Controller 用于冒烟测试",
    "Run app and hit ping": "启动应用并访问 ping",
    "Write the annotation": "编写注解",
    "Write the metadata record": "编写元数据 record",
    "Write failing test": "编写失败测试",
    "Run the test, expect compile + test failure": "运行测试，预期编译和测试失败",
    "Implement ToolRegistry": "实现 ToolRegistry",
    "Run tests, expect pass": "运行测试，预期通过",
    "Make the registry initialize from all Spring beans on startup": "让 Registry 在启动时从所有 Spring Bean 初始化",
    "Run aggregate build, confirm no regression": "运行聚合构建，确认无回归",
    "Add Jackson JSON Schema dep": "添加 Jackson JSON Schema 依赖",
    "Run test, expect compile failure": "运行测试，预期编译失败",
    "Implement schema generator": "实现 Schema 生成器",
    "Wire schema generation into ToolRegistry.scan": "将 Schema 生成接入 ToolRegistry.scan",
    "Update earlier ToolRegistryTest to construct with the generator": "更新早期 ToolRegistryTest，构造时传入生成器",
    "Write the records": "编写 records",
    "Run, expect compile failure": "运行，预期编译失败",
    "Implement ToolGateway": "实现 ToolGateway",
    "Add validator dep to `agent/pom.xml`": "向 `agent/pom.xml` 添加校验器依赖",
    "Write failing test asserting schema rejection": "编写失败测试，断言 Schema 拒绝无效请求",
    "Add schema validation pre-step in `ToolGateway.invoke`": "在 `ToolGateway.invoke` 中添加 Schema 校验前置步骤",
    "Run tests": "运行测试",
    "Add `agent` dep visibility in `core` for the annotation": "为注解在 `core` 中添加对 `agent` 的依赖可见性",
    "Annotate `ShopServiceImpl.queryById`": "标注 `ShopServiceImpl.queryById`",
    "Annotate `ShopServiceImpl.queryShopByType` (and/or searchNearby if it exists)": "标注 `ShopServiceImpl.queryShopByType`（以及存在时的 searchNearby）",
    "Annotate `ShopTypeServiceImpl.queryTypeList`": "标注 `ShopTypeServiceImpl.queryTypeList`",
    "Annotate one read on `VoucherServiceImpl` and one read on `VoucherOrderServiceImpl`": "标注 `VoucherServiceImpl` 的一个读方法和 `VoucherOrderServiceImpl` 的一个读方法",
    "Run aggregate test": "运行聚合测试",
    "Start and verify": "启动并验证",
    "Build a provider that converts `ToolMeta` to `FunctionCallback`": "构建将 `ToolMeta` 转换为 `FunctionCallback` 的 Provider",
    "Wire AgentChatService that uses the provider": "接入使用该 Provider 的 AgentChatService",
    "Hook into a temporary smoke endpoint to verify the LLM chooses a tool": "接入临时冒烟端点，验证 LLM 会选择工具",
    "Run and verify": "运行并验证",
    "Define the data types": "定义数据类型",
    "Write the failing test using a fake LLM": "使用 Fake LLM 编写失败测试",
    "Implement ReactExecutor": "实现 ReactExecutor",
    "Implement": "实现",
    "Compile": "编译",
    "Define record": "定义 record",
    "TDD store": "TDD Store",
    "Wire ReactExecutor to write traces": "接入 ReactExecutor 写入 traces",
    "Define request and event types": "定义请求和事件类型",
    "Implement controller": "实现 Controller",
    "Wire executor selection (only ReAct for now)": "接入 Executor 选择（目前仅 ReAct）",
    "Manual smoke": "手工冒烟",
    "SSE composable using `fetch` + ReadableStream (EventSource cannot POST)": "使用 `fetch` + ReadableStream 的 SSE composable（EventSource 不能 POST）",
    "Minimal chat view": "最小聊天视图",
    "Add route": "添加路由",
    "Define": "定义",
    "Implement PlanExecuteExecutor": "实现 PlanExecuteExecutor",
    "Run test, expect pass": "运行测试，预期通过",
    "Add Router and OrchestratorRoot": "添加 Router 和 OrchestratorRoot",
    "Order-related": "订单相关",
    "Social": "社交",
    "Geo": "地理",
    "Audit": "审计",
    "Verify aggregate test still passes; restart agent app, confirm log shows ≥15 tools": "确认聚合测试仍通过；重启 Agent 应用，确认日志显示 ≥15 个工具",
    "Add MCP server starter to `mcp-server/pom.xml`": "向 `mcp-server/pom.xml` 添加 MCP Server Starter",
    "Bridge ToolRegistry → MCP tool definitions": "桥接 ToolRegistry → MCP 工具定义",
    "Configure MCP transport": "配置 MCP 传输",
    "Smoke test via `curl` against MCP SSE endpoint": "用 `curl` 对 MCP SSE 端点做冒烟测试",
    "Add Claude Desktop config sample to `deploy/`": "向 `deploy/` 添加 Claude Desktop 配置样例",
    "Add Resilience4j deps to `agent/pom.xml`": "向 `agent/pom.xml` 添加 Resilience4j 依赖",
    "Per-tool resilience configuration": "按工具配置韧性策略",
    "Wrap invocation in ToolGateway": "在 ToolGateway 中包装调用",
    "Smoke test by hammering one tool": "通过高频调用某个工具做冒烟测试",
    "Wrap `chat.prompt().call()` with a separate CircuitBreaker": "用独立 CircuitBreaker 包装 `chat.prompt().call()`",
    "OpsAgent — thin wrapper around PlanExecute": "OpsAgent：PlanExecute 的薄包装",
    "Hook into PlanExecuteExecutor": "接入 PlanExecuteExecutor",
    "YAML schema": "YAML Schema",
    "Data classes": "数据类",
    "Runner": "Runner",
    "Report writer": "报告写入器",
    "CLI entry": "CLI 入口",
    "Add 30 cases gradually": "逐步添加 30 条用例",
    "Run eval": "运行 eval",
    "Add deps": "添加依赖",
    "Tag metrics on tool calls": "为工具调用打指标标签",
    "Tag metrics on LLM calls": "为 LLM 调用打指标标签",
    "Prometheus config": "Prometheus 配置",
    "Grafana dashboard JSON": "Grafana Dashboard JSON",
    "Add endpoints": "添加端点",
    "Page": "页面",
    "Route": "路由",
    "Run eval to get real numbers": "运行 eval 获取真实数字",
    "Run a quick seckill load test (manual via `wrk` or `ab`)": "快速运行秒杀压测（手工使用 `wrk` 或 `ab`）",
    "Update README with `## Metrics` section": "在 README 中更新 `## Metrics` 指标章节",
    "Write `docs/RESUME.md` — copy-paste-ready resume bullet": "编写 `docs/RESUME.md`：可直接粘贴到简历的 bullet",
    "Record a demo (manual, no code)": "录制演示（手工，无代码）",
    "Remove the deprecated `AiAssistantService` (873 lines)": "移除已废弃的 `AiAssistantService`（873 行）",
    "Final aggregate test": "最终聚合测试",
    "Commit and tag": "提交并打 tag",
    "Commit": "提交",
}

paragraphs = {
    "**Goal:** Verify Spring AI Alibaba + Spring AI MCP server starter both compatible with existing Spring Boot 3.2.2 / MyBatis-Plus / Redisson / Hutool dependencies before committing to the rewrite. This is risk-mitigation per spec §8.": "**目标：** 在正式重构前，验证 Spring AI Alibaba 和 Spring AI MCP Server Starter 都与现有 Spring Boot 3.2.2 / MyBatis-Plus / Redisson / Hutool 依赖兼容。这是规格 §8 中的风险缓解措施。",
    "**Goal:** Put the project under version control before any restructure, so every later phase can commit small steps and roll back cleanly.": "**目标：** 在任何结构调整前先把项目纳入版本控制，确保后续每个阶段都能小步提交，并可干净回滚。",
    "**Goal:** Convert single-module hmdp into a multi-module project: `core` (existing business), `agent` (new), `mcp-server` (new), `eval` (new). All existing tests still pass after restructure.": "**目标：** 将单模块 hmdp 转换为多模块项目：`core`（现有业务）、`agent`（新增）、`mcp-server`（新增）、`eval`（新增）。重构后所有现有测试仍需通过。",
    "**Goal:** Change the externally visible identity from `hmdp` / 黑马点评 to `city-copilot` / CityCopilot. Package rename, main application class, README.": "**目标：** 将外部可见身份从 `hmdp` / 黑马点评 改为 `city-copilot` / CityCopilot。包括包名、主应用类和 README 重命名。",
    "**Goal:** End-to-end LLM call works. `/agent/ping` returns a response from DashScope.": "**目标：** 打通端到端 LLM 调用。`/agent/ping` 能返回 DashScope 响应。",
    "**Goal:** Register methods annotated `@AgentTool` into an in-memory registry at startup. TDD.": "**目标：** 启动时将标注 `@AgentTool` 的方法注册到内存 Registry。采用 TDD。",
    "**Goal:** Generate a JSON Schema for each `@AgentTool` method's parameters. Used by LLM Function Calling and MCP tools/list.": "**目标：** 为每个 `@AgentTool` 方法的参数生成 JSON Schema，供 LLM Function Calling 和 MCP tools/list 使用。",
    "**Goal:** Single entry point `ToolGateway.invoke(name, argsJson, ctx)` for all tool calls (from both Agent and MCP server). Handles arg parsing, JSON Schema validation, permission check (reusing core's `PermissionPolicy`), call dispatch, error normalization, and async trace write. TDD.": "**目标：** 为所有工具调用（来自 Agent 和 MCP Server）提供单一入口 `ToolGateway.invoke(name, argsJson, ctx)`。处理参数解析、JSON Schema 校验、权限检查（复用 core 的 `PermissionPolicy`）、调用派发、错误标准化和异步 trace 写入。采用 TDD。",
    "**Goal:** Without changing business logic, expose 5 read-only methods as agent tools for v0.1.": "**目标：** 不改变业务逻辑，先将 5 个只读方法暴露为 v0.1 的 Agent 工具。",
    "**Goal:** Build a thin wrapper that, given a user message and the current `ToolRegistry`, calls Spring AI `ChatClient` with all tools as `FunctionCallback`s. This lets the LLM choose tools natively.": "**目标：** 构建一个薄封装：输入用户消息和当前 `ToolRegistry`，用全部工具作为 `FunctionCallback` 调用 Spring AI `ChatClient`。这样 LLM 可以原生选择工具。",
    "**Goal:** Implement the ReAct loop explicitly (not relying on Spring AI's built-in tool-calling loop) so we control max-steps, anti-loop, token budget, observation injection.": "**目标：** 显式实现 ReAct 循环（不依赖 Spring AI 内置工具调用循环），以便控制最大步数、防循环、token 预算和 observation 注入。",
    "**Goal:** Record every Agent run's steps to Redis under `trace:{traceId}`, retain 7 days, expose `GET /agent/traces/{id}`.": "**目标：** 将每次 Agent 运行步骤记录到 Redis 的 `trace:{traceId}` 下，保留 7 天，并暴露 `GET /agent/traces/{id}`。",
    "**Goal:** `POST /agent/chat` accepts user message, returns SSE stream of step events. Frontend renders tool calls as cards.": "**目标：** `POST /agent/chat` 接收用户消息，并返回步骤事件的 SSE 流。前端将工具调用渲染为卡片。",
    "**Goal:** Add a second executor that produces a multi-step plan up-front, executes with reference resolution, replans on failure.": "**目标：** 添加第二个 Executor：先生成多步骤计划，执行时支持引用解析，失败时重新规划。",
    "**Goal:** Annotate the remaining business operations to give Plan-Execute substrate to work on. Most additions are pure annotation.": "**目标：** 标注剩余业务操作，为 Plan-Execute 提供可执行基底。多数新增只是添加注解。",
    "**Goal:** Expose the Tool Registry as MCP tools so Claude Desktop / Cursor can call them. Uses `spring-ai-starter-mcp-server`.": "**目标：** 将 Tool Registry 暴露为 MCP 工具，让 Claude Desktop / Cursor 可以调用。使用 `spring-ai-starter-mcp-server`。",
    "**Goal:** Per-tool circuit breaker, rate limiter, retry. LLM provider circuit breaker.": "**目标：** 提供按工具的熔断、限流、重试，以及 LLM Provider 熔断器。",
    "**Goal:** A background worker that consumes a Redis Stream and runs Plan-Execute jobs without UI feedback.": "**目标：** 实现一个消费 Redis Stream 的后台 Worker，在没有 UI 反馈的情况下运行 Plan-Execute 任务。",
    "**Goal:** Insert a single LLM check before high-risk plan steps (place_seckill_order, pay_order, refund_order, publish_blog, start_batch_geocode).": "**目标：** 在高风险计划步骤（place_seckill_order、pay_order、refund_order、publish_blog、start_batch_geocode）执行前插入一次 LLM 检查。",
    "**Goal:** YAML-driven case runner; outputs a Markdown report with tool-call accuracy, answer keyword hit, latency, token estimates.": "**目标：** 实现 YAML 驱动的用例 Runner；输出 Markdown 报告，包含工具调用准确率、答案关键词命中、延迟和 token 估算。",
    "**Goal:** Expose Prometheus metrics for tool calls, LLM calls, agent loop counts; ship a Grafana dashboard JSON.": "**目标：** 暴露工具调用、LLM 调用、Agent 循环次数的 Prometheus 指标；交付 Grafana Dashboard JSON。",
    "**Goal:** Frontend page that fetches a trace by id and renders a step-by-step timeline.": "**目标：** 实现前端页面，根据 trace id 拉取 trace，并渲染逐步时间线。",
    "**Goal:** All collateral needed for a great resume submission.": "**目标：** 准备一份优秀简历投递所需的所有配套材料。",
    "**Goal:** Remove deprecated paths, polish.": "**目标：** 移除废弃路径并整体打磨。",
    "**v0.1 MVP IS NOW COMPLETE.** The application can:": "**v0.1 MVP 至此完成。** 应用现在可以：",
    "**Commit checkpoint:**": "**提交检查点：**",
    "**Plan complete and saved to `docs/superpowers/plans/2026-05-24-citycopilot-implementation.md`.**": "**计划已完成并保存到 `docs/superpowers/plans/2026-05-24-citycopilot-implementation.md`。**",
}
exact.update(paragraphs)

sentence_map = {
    "Expected: BUILD SUCCESS.": "预期：BUILD SUCCESS。",
    "Expected: all green.": "预期：全部通过。",
    "Expected: all pass.": "预期：全部通过。",
    "Expected: green.": "预期：绿色通过。",
    "In another terminal:": "在另一个终端中：",
    "If clean, delete:": "如果确认干净，则删除：",
    "Append to README:": "追加到 README：",
    "Doc this in README.": "将这部分写入 README。",
    "Also create `FakeLlm` test helper:": "同时创建 `FakeLlm` 测试辅助类：",
    "And the LlmClient interface:": "以及 LlmClient 接口：",
}
exact.update(sentence_map)

phrase_replacements = [
    ("**Files (modify):**", "**文件（修改）：**"),
    ("**Files:**", "**文件：**"),
    ("**Goal:**", "**目标：**"),
    ("**Decision gate:**", "**决策门槛：**"),
    ("**Note on Spring AI version:**", "**关于 Spring AI 版本的说明：**"),
    ("**Note**:", "**说明：**"),
    ("**Note:**", "**说明：**"),
    ("Expected:", "预期："),
    ("Create ", "创建 "),
    ("Modify ", "修改 "),
    ("Move ", "移动 "),
    ("Read ", "读取 "),
    ("Edit ", "编辑 "),
    ("Add ", "添加 "),
    ("For each ", "对于每个 "),
    ("For ", "对于 "),
    ("In ", "在 "),
    ("Also: ", "另外："),
]

verb_prefixes = {
    "- Create:": "- 创建：",
    "- Modify:": "- 修改：",
    "- Move:": "- 移动：",
}


def replace_outside_backticks(line, replacements):
    parts = line.split("`")
    for i in range(0, len(parts), 2):
        for old, new in replacements:
            parts[i] = parts[i].replace(old, new)
    return "`".join(parts)


def translate_line(line):
    if line in exact:
        return exact[line]
    m = re.match(r"(##+) Phase (\d+): (.+)$", line)
    if m:
        return f"{m.group(1)} 阶段 {m.group(2)}：{phase_titles.get(m.group(3), m.group(3))}"
    m = re.match(r"(##+) Task ([0-9.]+): (.+)$", line)
    if m:
        return f"{m.group(1)} 任务 {m.group(2)}：{task_titles.get(m.group(3), m.group(3))}"
    m = re.match(r"(- \[ \] \*\*)Step (\d+): (.+)(\*\*)$", line)
    if m:
        return f"{m.group(1)}步骤 {m.group(2)}：{step_titles.get(m.group(3), m.group(3))}{m.group(4)}"
    for old, new in verb_prefixes.items():
        if line.startswith(old):
            return new + line[len(old):]
    return replace_outside_backticks(line, phrase_replacements)


out = []
in_code = False
for line in text.splitlines():
    if line.startswith("```"):
        in_code = not in_code
        out.append(line)
    elif in_code:
        out.append(line)
    else:
        out.append(translate_line(line))

path.write_text("\n".join(out) + "\n", encoding="utf-8")
