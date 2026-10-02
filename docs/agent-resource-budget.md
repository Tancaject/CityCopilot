# Agent 请求资源预算

Agent 的消费者对话、确认续跑和 Ops 工作单元共享进程内运行容量。对话进入有界执行队列；确认只有实际取得容量后才读取并消费一次性审批。排队时间计入本轮截止时间，排队过期不会消费审批。Ops 保留读写线程池隔离，两个队列均有界，并与对话共享活动任务上限。

配置前缀为 `citycopilot.agent.budget`，字段、单位和默认值的权威来源是 [AgentBudgetProperties](../backend/agent/src/main/java/com/citycopilot/agent/budget/AgentBudgetProperties.java)。Spring 的配置绑定支持配置文件和环境变量覆盖，例如 `CITYCOPILOT_AGENT_BUDGET_DEADLINE`。Agent 模块原先未生效的 `citycopilot.ai.max-message-length` 已移除；当前消息按 `message-bytes` 的 UTF-8 字节数检查，HTTP 请求体还受 `request-bytes` 限制。

同一预算覆盖规划、Critic、重规划、总结、工具调用及其物理重试。`model-calls` 限制模型操作数；每次操作预留 `model-output-tokens` 并发送 `max_tokens`，所有预留累计受 `total-model-output-tokens` 限制，失败也不返还。模型输入采用完整请求 JSON 的 UTF-8 字节数作为保守本地 token 估算，受 `model-input-tokens` 约束；它不是供应商精确 token 使用量。真实 usage 仍由模型响应记录。独立的原始响应字节上限在 JSON 和 SSE 解析前生效，避免依赖供应商遵守输出参数。

计划在执行前检查单计划和累计步骤上限；工具的逻辑调用数与物理重试数分别计费。上下文、工具结果与总 SSE/确认响应另有字节上限。超限拒绝继续执行，不截断用于步骤引用或用户审批的权威参数和结果。

Core 工具目录是规划元数据，其完整 HTTP 正文受 `context-bytes` 限制，超限返回 `agent_tool_catalog_too_large`；单次工具调用的 HTTP 正文和解码结果仍受 `tool-result-bytes` 限制。目录超限应检查发布的工具 Schema 与元数据预算，缩小用户店铺查询范围无法减少目录大小。目录客户端复用原有连接工厂、截止时间与取消机制，不截断目录或放宽业务工具结果上限。

对话及确认的截止时间从 HTTP 过滤器开始，包含会话解析和排队；Ops 从提交工作单元开始。预算显式传播到工具保护器线程。超时或检测到客户端断开后，会中断本地任务、取消正在等待的 HTTP future、关闭正在读取的响应流，并禁止后续工具、重试、模型降级和计划步骤。单次模型/Core HTTP 的交换超时覆盖响应头及完整正文。内部调用通过 `X-CityCopilot-Deadline-Epoch-Ms` 传递绝对截止时间；外部模型不会收到该内部请求头。

取消不等于撤销业务事务。写请求已发出而未取得明确响应时，返回 `writeOutcomeUnknown=true`；空响应、传输失败和取消不能被当作业务未执行。已有幂等键原样传递；停止后不生成新键重做，也不尝试回滚 Core 已提交的操作。已收到明确结果的写操作保留其结果，后续预算耗尽也不会撤销。Ops 执行中耗尽预算转人工复核，不重放获批写步骤。数据库驱动和远端事务的执行能否立即中止由其自身控制，Agent 只保证停止本进程后续工作和关闭本地 HTTP 交换。

拒绝和终止使用稳定错误码（例如 `agent_overloaded`、`agent_deadline_exceeded`、`agent_model_calls_exceeded`），SSE 用固定大小的终止控制帧解释原因，不再发送启发式业务答案。指标 `citycopilot.agent.requests.active`、`citycopilot.agent.requests.queued` 和 `citycopilot.agent.budget.stopped` 用于观察活动量、排队量和终止原因。

定向测试位于 Agent 模块的 `budget` 包，以及 `ToolGuardBudgetTest`、`AgentControllerConfirmationTest`。它们使用本地 HTTP stub 和假模型验证完整正文超时、取消、原始响应上限、模型预算、工具重试预算与确认消费时机，不访问真实模型供应商。
