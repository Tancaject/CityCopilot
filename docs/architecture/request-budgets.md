# 跨服务请求预算

`backend/http-support` 是 Core、Commerce 共用的 HTTP 截止时间与取消实现。业务契约模块 `contracts` 不依赖 Spring。它提供既有 `RestClient` 使用的 `ClientHttpRequestFactory`，不改变业务状态码、幂等键生成或写入重试策略。

## 截止时间

内部请求使用 `X-CityCopilot-Deadline-Epoch-Ms`，值为整个逻辑操作的绝对截止时间，单位为 Unix 毫秒。服务实例应同步系统时钟；收到头部后转换为本机单调时钟计算剩余时间。每跳只取剩余时间与该跳响应超时的较小值，不为第二次调用重新分配完整预算。头部仍携带逻辑操作的截止时间；本跳响应超时可以先于它到期。

- Core 的普通外部请求自行建立本地预算，不采纳来访截止时间头；`/internal/**` 先完成服务令牌认证，再接收并夹紧来访截止时间。
- Commerce 在服务令牌认证后接收截止时间，并夹紧到本地上限。

Core、Commerce 的业务 HTTP 响应返回实际采用的截止时间头。`RequestDeadline` 作用域离开时清理线程上下文；同一个对象嵌套绑定只在最后一个作用域结束时取消，关闭作用域可重复调用。SDK 或工作线程需要显式传递上下文，不使用可继承的 ThreadLocal。

## 容量与配置

入口和出站容量使用即时 `tryAcquire`，满额即拒绝，没有额外应用等待队列；这不替代 Web 容器自身的连接与请求队列设置。出站许可覆盖收到响应头后的 body 读取，响应关闭或传输失败后释放。

| 服务 | 配置键 | 作用与权威默认值位置 |
|---|---|---|
| Core | `citycopilot.http.request-timeout`、`max-concurrent`、`authority-max-concurrent` | [MvcConfig](../../backend/core/src/main/java/com/citycopilot/core/config/MvcConfig.java)；Commerce 回查 `/internal/v1/place/**` 使用独立容量，避免和原始等待请求互相占满 |
| Core → Commerce | `citycopilot.http.commerce.connect-timeout`、`response-timeout`、`booking-response-timeout`、`max-concurrent` | [CrossServiceHttpConfig](../../backend/core/src/main/java/com/citycopilot/core/config/CrossServiceHttpConfig.java)；券订单与预约客户端共享一个目标容量 |
| Commerce | `citycopilot.http.request-timeout`、`max-concurrent` | [CommerceWebConfig](../../backend/commerce-service/src/main/java/com/citycopilot/commerce/CommerceWebConfig.java) |
| Commerce → Core | `citycopilot.http.place.connect-timeout`、`response-timeout`、`max-concurrent` | [PlaceAuthorityClient](../../backend/commerce-service/src/main/java/com/citycopilot/commerce/PlaceAuthorityClient.java) |

这些值是可覆盖的运行配置，不是经过吞吐量验收的容量承诺。调整入口容量时应同时核对容器工作线程数、下游数据库连接池和回调预留容量。

## 取消与写入结果

共享传输层使用 JDK 异步 exchange、完整响应定时器，以及到期时取消 future、关闭已获得的响应 InputStream。因此“响应头已经返回但 body 停止发送”仍会终止本地读取。它不只依赖 Spring `JdkClientHttpRequestFactory.setReadTimeout`，后者在本项目原版本中等待的是 `ofInputStream` future，不能单独约束后续 body 读取。

取消表示本地停止等待并尝试中止 HTTP exchange，不能证明远端处理停止、JDBC 被取消或事务回滚。预算没有为全部业务代码建立可抢占的 CPU/JDBC 执行时限。尤其写请求已经发出后，仍按既有 `COMMERCE_ADMISSION_OUTCOME_UNKNOWN` / `BOOKING_OUTCOME_UNKNOWN` 等语义处理，先查询状态，再使用原幂等键恢复；不能生成新键重做，也不因超时回退到旧 writer。

## 定向验证

`DeadlineHttpRequestFactoryTest` 使用真实 loopback HTTP 服务覆盖迟到响应头、头部后停滞的 body、显式取消、并发拒绝及许可回收、多次请求共享预算。`DeadlineHandlerInterceptorTest` 覆盖认证后的夹紧、外部头隔离、拒绝与作用域清理。Core 的两个端口测试和 Commerce 的 `PlaceAuthorityDeadlineTest` 检查真实慢 body 的既有业务错误映射。无需启动数据库或完整应用。

```powershell
mvn -pl backend/http-support,backend/core,backend/commerce-service -am `
  '-Dtest=DeadlineHttpRequestFactoryTest,DeadlineHandlerInterceptorTest,CommercePortClientTest,BookingPortClientTest,PlaceAuthorityDeadlineTest' `
  '-Dsurefire.failIfNoSpecifiedTests=false' test
```

JDK 取消能力的边界见 [HttpClient.sendAsync](https://docs.oracle.com/en/java/javase/17/docs/api/java.net.http/java/net/http/HttpClient.html#sendAsync(java.net.http.HttpRequest,java.net.http.HttpResponse.BodyHandler))；`ofInputStream` 的响应完成时机见 [BodyHandlers](https://docs.oracle.com/en/java/javase/17/docs/api/java.net.http/java/net/http/HttpResponse.BodyHandlers.html#ofInputStream())。
