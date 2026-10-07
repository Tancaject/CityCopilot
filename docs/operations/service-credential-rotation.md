# 服务间凭据轮换

Core 的命名凭据配置见 [application.yaml](../../backend/core/src/main/resources/application.yaml)，Commerce 的接收凭据见 [application.yaml](../../backend/commerce-service/src/main/resources/application.yaml)。每个 actor 使用独立随机凭据。客户端只发送当前令牌，接收端在轮换窗口内同时接受当前令牌和 `previous` 令牌；`previous` 只是额外接收槽，也可在准备阶段暂放下一代令牌。

| 调用方向 | 接收端 | 当前令牌环境变量 | 额外接收令牌环境变量 |
| --- | --- | --- | --- |
| Core ↔ Commerce | Core / `commerce-service`，Commerce | `CITYCOPILOT_INTERNAL_COMMERCE_TOKEN` | `CITYCOPILOT_INTERNAL_COMMERCE_PREVIOUS_TOKEN` |

Core 根据匹配的令牌确定 actor，不信任客户端身份提示头。两个 actor 在当前或额外凭据中使用同一个令牌时，该令牌认证失败；同一 actor 的两个槽相同不会产生第二个身份。清空 actor 的当前令牌会同时停用其额外令牌。仅配置额外令牌不能启用 actor，也不会退回旧版共享令牌认证。用户会话、权限和业务归属校验仍按原调用链执行，服务认证不替代用户授权。

## HTTP 入口边界

- [Core Filter 注册](../../backend/core/src/main/java/com/citycopilot/core/config/MvcConfig.java) 在处理器匹配前认证 `/internal/**`，并限定用户线程上下文的生命周期。不存在对应 MVC 路由的内部路径也须先通过服务认证。服务 actor 只在已认证的分派链内存在；请求头不能直接指定身份。
- [Commerce Filter 注册](../../backend/commerce-service/src/main/java/com/citycopilot/commerce/CommerceWebConfig.java) 在处理器匹配前认证 `/commerce/**`。`/health` 和 `/actuator/**` 仍在该业务命名空间之外，保持原监控入口规则；它们的网络访问控制仍由部署承担。
- 服务 Filter 注册 REQUEST、FORWARD、INCLUDE、ASYNC、ERROR 全部分派类型，按分派目标、context path 及与 MVC 一致的路径段解码/矩阵参数规则匹配命名空间，同时覆盖 DispatcherServlet 前缀映射后的 MVC 路径。INCLUDE 使用其目标属性，不能借公开来源包含内部目标来跳过令牌检查。
- Core 的 `UserContextFilter` 在 Servlet 线程的 `finally` 中清理 `UserHolder`，包括会话恢复异常、控制器异常和异步交接。Filter 本身不读取 Redis；MVC 在原有请求并发预算准入之后运行 `RefreshTokenInterceptor`，额度耗尽时不执行会话读取。ASYNC/ERROR 重新进入 MVC 时重新建立线程内会话；嵌套同步分派不会提前清空外层身份。不将用户身份自动传播到自行创建的线程或异步业务任务；新增这类入口须显式定义身份快照、授权时点及清理规则。
- 用户登录与 `@RequirePermission` 校验仍由 MVC 拦截器按原路由范围执行，因其需要选定的控制器方法。公开白名单和方法授权范围以 [MvcConfig](../../backend/core/src/main/java/com/citycopilot/core/config/MvcConfig.java) 为唯一来源；服务认证的 Filter 化不会授予用户权限。新增非 MVC 的用户业务 Servlet 须单独声明授权边界，不能因为经过上下文清理 Filter 就视为已获授权。

## 准备与发布顺序

配置在进程启动时加载，修改密钥存储或 `.env` 不代表运行实例已更新。每一步都须确认所有相关接收实例已加载对应配置，再进入下一步。发布记录只记录密钥版本标识和实例范围，不输出令牌值或完整环境变量。

1. 清点调用者和接收者，生成仅属于该 actor 的新随机令牌。明确本次重叠窗口的截止时间、负责人和回滚边界。先完成接收端支持双凭据的代码发布，保持现有当前令牌与空额外令牌。
2. **轮换 Commerce 前，固定预约核销密钥。** [BookingPolicy](../../backend/commerce-service/src/main/java/com/citycopilot/commerce/booking/BookingPolicy.java) 在未独立配置时使用 Commerce 当前令牌生成核销签名。将 `CITYCOPILOT_BOOKING_CHECKIN_SECRET` 显式设为当前实际生效的签名密钥，并先更新所有 Commerce 实例；若原先未独立配置，其值就是轮换前的 Commerce 令牌。不要同时换成一个新签名密钥，否则已有预约核销凭据会失效。签名密钥自身的轮换需另行制定预约凭据过渡方案，不由此流程完成。
3. **准备双向重叠：**接收端保持 `current=旧`，设置 `previous=新` 并发布。Commerce 轮换必须同时覆盖全部 Core 和 Commerce 接收实例，因为两者还会反向调用；此时调用者仍发送旧令牌。用不写业务数据的受保护接口分别确认两代凭据可用。
4. **切换发送端：**逐批改为 `current=新、previous=旧`。单向调用的发送端只需切当前令牌；Core 与 Commerce 兼任发送端和接收端，需要保留旧令牌接收槽。等待旧实例与在途请求退出，观察受保护请求的认证响应，并用一个无效令牌确认拒绝仍生效。
5. **撤销旧令牌：**确认没有旧调用实例后，在所有接收端清空 `previous` 并发布。确认新令牌仍可用、旧令牌收到 401，随后从密钥存储删除旧服务令牌。若该值仍承担已固定的预约签名职责，只能撤销它的服务调用权限，不能同时删除签名用途的配置。

额外接收槽没有自动到期机制；截止时间必须由发布流程执行清空。不能把长期保留两个有效令牌当作轮换完成。怀疑泄露时应撤销泄露令牌，不应用重叠窗口继续接受它。此时允许认证失败暴露尚未更新的调用者，不能退回共享令牌或关闭鉴权。

## 失败处理与验证边界

- Core 未配置任何凭据时返回 503 / `INTERNAL_API_DISABLED`；令牌不匹配或 actor 歧义返回 401 / `SERVICE_AUTH_REQUIRED`。Commerce 业务接口未配置当前令牌或认证失败时返回 401 / `COMMERCE_SERVICE_AUTH_REQUIRED`，正文沿用 `success`、`code`、`message` 的失败结构。先检查请求到达的实例是否完成当前发布阶段，再核对密钥版本和 actor；不要记录请求中的密钥。认证拒绝不建议循环重试，需先修正调用方或接收实例的配置。
- 重叠阶段可以把发送端退回旧令牌，前提是所有接收端仍接受它。完成撤销后，回滚程序也须继续使用新凭据，不能依赖已撤销令牌。
- 仓库定向检查覆盖两代身份一致性、跨 actor 碰撞拒绝、撤销与缺失当前令牌时拒绝。它们不证明实际部署已完成轮换。上线验收须覆盖每个接收实例及 Core ↔ Commerce 两个方向，并保留不含密钥的发布记录。健康检查不经过同样的业务鉴权，不能用健康检查成功替代凭据验收。

## Commerce 错误与重试契约

[CommerceError](../../backend/contracts/src/main/java/com/citycopilot/contract/commerce/CommerceContracts.java) 保持现有失败正文；[CommerceExceptionAdvice](../../backend/commerce-service/src/main/java/com/citycopilot/commerce/CommerceExceptionAdvice.java) 保留业务层给出的状态、错误码与消息。Filter 的服务认证拒绝使用同样的正文结构，但不经过 MVC 异常处理器。

[Core CommercePortClient](../../backend/core/src/main/java/com/citycopilot/core/commerce/CommercePortClient.java) 对秒杀准入保留上游 HTTP 状态与业务码，并透传上游 `Retry-After`。只有 [既有准入重试策略](../../backend/contracts/src/main/java/com/citycopilot/contract/commerce/CommerceAdmissionOutcomePolicy.java) 明确允许的错误在缺失退避头时补充默认等待，并要求沿用同一个 `Idempotency-Key`。客户端不会自动发起第二次写请求，不能因为某个响应带有退避头或属于 5xx 就推断可以安全重试。

上游错误正文为空或不能按契约解码时，Core 保留已有的 `COMMERCE_UNAVAILABLE` 业务码；秒杀响应仍保留实际上游 HTTP 状态。错误消息给出上游状态与排查入口，写入结果须查询后再决定后续动作。其他已有 Core 兼容接口继续返回 `Result` 失败包装，其 HTTP 约定不在这次收敛中变更。错误协议若要统一改为非 2xx 状态，应另行按外部 API 版本迁移，不可仅替换客户端异常就改变现有调用方行为。
