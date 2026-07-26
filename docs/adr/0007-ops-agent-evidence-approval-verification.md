# ADR-0007：Ops Agent 的证据、审批与复核

状态：接受  
日期：2026-07-26  
补充：ADR-0003

## 背景

仅按固定模板调用运维 Tool 的 Worker 不能证明 Agent 能诊断和闭环；让模型直接执行写操作又不可接受。

## 决策

Ops Agent 固定采用以下状态机：

`DETECTED → EVIDENCE_COLLECTED → DIAGNOSED → PLANNED → WAITING_APPROVAL → EXECUTING → VERIFYING → RESOLVED/FAILED/MANUAL`

- 证据、Case、计划、审批、执行和复核持久化到 Agent 私有数据库。
- LLM 只生成符合 schema 的诊断和候选计划；策略引擎决定工具 audience、scope、risk 和 approval。
- 审批绑定 user、planVersion、完整 planHash、参数、当前步骤、证据和过期时间。
- 批准后恢复同一计划；计划或参数变化必须重新审批。
- Tool 必须幂等且有界；验证失败不得将 Case 标记为 RESOLVED。
- Consumer Agent、Ops Agent 和 MCP 使用不同 allowlist 与服务凭据。

## 结果与回滚

Agent 的价值来自可恢复闭环而非“用了 LLM”。`CITYCOPILOT_OPS_AGENT_ENABLED=false` 停止创建新 Case；已有 Case 保留并可人工接管。`CITYCOPILOT_OPS_AUTO_EXECUTION_ENABLED=false` 保留诊断与计划但禁止执行。

