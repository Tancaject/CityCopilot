# 预约 Agent 评测

预约用例位于 [booking-v1.yaml](../../backend/eval/src/main/resources/cases/booking-v1.yaml)，通过现有 `EvalRunner` 的 `--provider agent-http` 读取真实 Agent SSE 和 Core 预约事实。旧 `offline` 和 `openai-compatible` 路由预测器不产生这些业务证据，不能通过预约结构化断言。

使用隔离的 Core、Commerce、Agent、Redis 与专用测试用户。先在店铺 `90001` 发布场馆固定报价、在 `90002` 发布餐厅套餐，选定未来同一天的 18:00–20:00 排期；场馆价格不超过总额 200 元，餐厅四人总价不超过 400 元，均高于 0.01 元。Agent 必须启用实际模型提供方。

设置以下环境变量，凭据不放入 YAML 或命令行：

- `CITYCOPILOT_BOOKING_EVAL_TOKEN`：专用测试用户的 Core 登录会话。
- `CITYCOPILOT_BOOKING_EVAL_DATE`：排期日期，`YYYY-MM-DD`。
- `CITYCOPILOT_BOOKING_EVAL_COURT_NAME`、`CITYCOPILOT_BOOKING_EVAL_DINING_NAME`：两家测试店铺可检索的名称。
- `CITYCOPILOT_BOOKING_EVAL_COURT_RESOURCE_ID`、`CITYCOPILOT_BOOKING_EVAL_DINING_RESOURCE_ID`：专供评测批准的资源编号，避免占用同时进行人工验收的资源。
- `CITYCOPILOT_BOOKING_EVAL_ALLOW_APPROVALS=true`：允许 YAML 中明确列出的用户批准步骤；仅支持 loopback Core 地址。

按项目现有 Maven 构建安装依赖后，在仓库根目录运行：

```powershell
mvn -pl backend/eval exec:java '-Dexec.args=cases/booking-v1.yaml --provider agent-http --agent-url http://127.0.0.1:8082 --core-url http://127.0.0.1:8081 --evidence-dir reports/booking-evidence --output reports/booking-eval.md --fail-on-gate'
```

前六例不批准业务操作，比较前后预约编号、版本和状态。后两例由评测脚本模拟用户选择方案，分别批准 HOLD 和 CONFIRM，再查询预约事实；读取最终状态后通过独立取消确认释放测试容量。遇到结果未知只查询原操作，证据中保留原编号，不自动换键重做。

每例 JSON 保存真实工具参数、服务端方案、批准结果、最终状态、清理结果，以及 Agent `usage` 事件。`successfulCalls` 与 `fallbackCalls` 区分实际模型调用和降级；`usageComplete` 表示是否所有调用均返回完整统计。部分响应缺 usage 时 token 数只是已收到统计的合计；完全没有 usage 时为 null，不能解读为零消耗。Markdown 的旧 token 列仍是缺 usage 时的估算，精确统计以 JSON 为准。任何提供方降级、人数/时段/预算错误、非预期写入、最终状态不符或清理失败均使门禁失败。

服务端确认的 Redis 集成验收复用 `BookingConfirmationServiceIT`，通过 `CITYCOPILOT_BOOKING_TEST_REDIS_HOST` / `CITYCOPILOT_BOOKING_TEST_REDIS_PORT` 指向专用 Redis；测试只清理自身生成的键。
