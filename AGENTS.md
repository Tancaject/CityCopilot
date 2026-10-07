# CityCopilot Agent Notes

## Git Workflow

- This repository uses `main` as the only working branch.
- Do not create personal, feature, or `codex/*` branches unless the user explicitly asks for a branch.
- Commit and push project changes directly on `main` when the user asks to commit or push.
- Before any commit or push, check `git status` and avoid overwriting unrelated user changes.

## 设计约束

- 先找已有实现复用。新增抽象（接口、基类、策略、注册表、Builder、通用工具类）必须已经有两个以上真实调用方；只有一个实现的接口不要建。
- 未经用户同意，不得新增：Maven 模块、服务/进程、数据库表、MQ topic/消费组、Redis 子系统、定时任务、配置项、功能开关。需要时先用三五行说明理由，等确认。
- 配置项只为"不同环境确实取值不同"而存在，不为"也许要调"把常量做成配置。
- 不写兼容层或迁移开关来照顾从未发布过的代码。只有已持久化的数据（数据库行、Outbox 消息体、报价快照 JSON）需要保持可读，遵守 ADR 0011。
- 不实现"仅供将来使用"的参数、字段、回退分支或空实现，也不保留无调用方的公共方法。删除功能时把它的调用链、配置、文档、测试一起删干净。
- 一个需求的改动如果预计超过约 300 行，或者要碰 Commerce 交易路径，先给方案（拆解、改动点、测试点），不贴完整代码，等用户确认再动手。

## 防御性编程

- 只在信任边界校验：前端/HTTP 入参、商家只能操作自己的店铺、MQ 消息（至少一次投递）、第三方高德响应、跨服务 HTTP 响应。内部方法之间按类型和契约互信，不重复判空、不重复校验。
- 不为不可能发生的情况写分支：私有方法的参数、刚查出来的必有记录、枚举穷尽之后的 default。真不可能就让它抛异常。
- 禁止吞异常：不写 catch 后只打日志然后继续，不写包一层再原样抛出的 try/catch，不用默认值掩盖失败。Commerce 不可用时 Core 必须失败关闭。
- 不加重试、熔断、限流、超时包装，除非该调用确实跨网络且现有 `http-support` 预算没有覆盖。
- 日志只记能帮助排障的事件，不给每个分支加 debug 日志。

## 测试

- 测试证明行为，不证明代码存在。每个行为改动通常新增 1–3 个测试，覆盖主路径和最关键的那个失败路径即可，不追求分支全覆盖。
- 不写这些测试：getter/setter、配置绑定、纯委托转发、Spring 能否装配、对日志内容或私有实现细节的断言、对 mock 调用次数的逐一断言。
- 红区（Commerce 库存、准入、订单、Outbox、DLQ、对账、预约暂留/改期/释放）的正确性靠真实 MySQL+Redis 集成测试（`SeckillLifecycleIT`、`BookingLifecycleIT` 这类）证明；在已有 IT 里加场景，不要另起一套测试基础设施。
- 开发过程中只跑与改动相关的模块和测试（`mvn -pl <module> -am -Dtest=...`），全量测试在交付前跑一次即可，不要每改一处就全量回归。
- 测试失败先判断是代码问题还是环境问题（时区、Redis ACL、端口占用），不要为了让测试通过去改断言或加 sleep。
- 不生成压测报告、验收 JSON、evidence 文档，除非用户明确要求做验收。

## 文档与可观测性

- 不新建 Markdown 文档、ADR、运行手册、设计计划，除非用户要求。行为变了只改受影响的那几行现有文档。
- 文档写现状，不写长段免责声明。"尚未生产验证"这类说明全仓库已经有了，不要每段重复。
- 不新增 Prometheus 指标、告警规则、Grafana 面板，除非用户要求或这次改动删掉了旧指标需要替换。

## 交付标准

- 完成 = 相关模块编译通过 + 相关测试通过 + 前端改动跑过 `npm run build` 并实际看过页面。
- 交付说明里列出改了什么、怎么验证的、哪些没验证；没跑的东西不要写成已验证。
