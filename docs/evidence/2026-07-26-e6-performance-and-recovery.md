# E6 性能、故障恢复与交付证据

日期：2026-07-26  
范围：本地单机 Compose，不宣称多机 HA。

## Commerce 秒杀

场景：300 个不同登录用户，50 并发，通过 Core 兼容入口调用 Commerce voucher 200。

- 请求：300；
- 成功：300，错误：0；
- 吞吐：421.8 requests/s；
- P50/P95/P99：31.31 / 340.08 / 546.28 ms；
- DB order / distinct user：300 / 300；
- DB stock / Redis stock：0 / 0；
- oversold：false；
- duplicate business effects：0；
- unpublished Outbox：0。

原始结果见 `2026-07-26-e6-commerce-load.json`。这些数字只适用于本机、当前容器资源与 300 请求短压，不能外推为生产容量。

## Commerce 停机隔离

首次演练发现 Core 的 Commerce Port 缺少读超时，请求会挂起。修复为 connect timeout 2 秒、read timeout 3 秒后重跑：

- Core health：`UP`；
- Place read：成功；
- Commerce order：`success=false`；
- code：`COMMERCE_UNAVAILABLE`；
- 观测耗时：1545 ms；
- Commerce 随后恢复 healthy。

## RocketMQ Proxy 中断

停止 Proxy 后制造 stock discrepancy：

- 对应 Outbox 首次为 `FAILED`，attempts=1；
- Proxy 恢复后 producer 自动重连；
- 同一 eventId 最终 `PUBLISHED`，attempts=8；
- 事件被 Agent 消费并形成 Case；
- 新鲜证据显示异常已消失后，Case 由运营取消，未执行不必要写操作。

## Agent 重启恢复

重启 Agent 容器后读取既有 Case：

- Case `2a21c405-deb2-44bf-836a-ce72530740e9` 保持 `SUCCEEDED`；
- execution count = 1；
- post-repair verification count = 1；
- `/agent/ready` 返回 `ok`。

另一次计划创建使用了过期 evidence，服务以 “A plan requires unexpired evidence” 拒绝，没有生成审批或执行记录，证明过期证据 fail closed。

## 可观测性

- Commerce Outbox：unpublished、oldest-ready-age、publish duration、publish outcome；
- Core MQ：publish duration/error、ready/dead/oldest age、consumer duplicate/quarantine；
- Agent：event consume outcome、Case outcome；
- 标识符进入结构化记录，不作为 metrics tag。
- 部署后已从 Commerce `/actuator/prometheus` 读取并确认上述 4 类 Outbox 指标存在。

## 最终验证

- Maven `clean verify`：95 个测试，0 failure，0 error；其中 4 个 Testcontainers 并发集成测试实际连接 Docker 29.5.3 并全部通过，3 个既有上下文测试按预期跳过；
- 前端 `npm run build`：通过；
- 离线 Agent eval：30/30，mode/tool/keyword accuracy 均为 100%；
- Compose：Core、Commerce、Agent、MCP、Frontend、3 个 MySQL、Redis、RocketMQ NameServer/Broker/Proxy 全部 healthy；
- 黄金演示：迁移行数/主键范围/金额/CRC 对账通过，cutover preflight 为 `safe=true`。

## 交付

- CI：全 Maven verify、前端 build、Tool/Event contract、离线 eval、Compose smoke 分开运行；
- `tools/golden-demo.ps1` 提供只读门禁检查和可选库存偏差注入；
- migration/cutover、mutation matrix、ADR、E4/E5/E6 evidence 已同步。
