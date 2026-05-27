# CityCopilot MCP 客户端验收

`backend/mcp-server` 从同一套 `AgentToolRegistry` 和 `ToolGateway` 暴露两类入口：

- HTTP 兼容工具接口：`GET /mcp/tools/list`、`POST /mcp/tools/call`
- Spring AI MCP SSE 传输：`GET /mcp/sse`、`POST /mcp/messages`

## 启动 MCP Server

```powershell
.\.tools\apache-maven-3.9.9\bin\mvn.cmd -pl backend/mcp-server spring-boot:run
```

模块默认端口是 `8090`。由于工具会执行真实业务服务，启动前仍然需要配置 MySQL 和 Redis。

## HTTP smoke

先确认工具列表可访问：

```powershell
curl http://localhost:8090/mcp/tools/list
```

推荐直接使用仓库脚本做一次列表 + 只读工具调用：

```powershell
.\tools\smoke_mcp_http.ps1
```

默认调用 `list_shop_types`。如果要换成其它只读工具：

```powershell
.\tools\smoke_mcp_http.ps1 -ToolName get_shop_detail -ArgumentsJson '{"shopId":1}'
```

如果只想验证工具列表：

```powershell
.\tools\smoke_mcp_http.ps1 -SkipToolCall
```

## MCP SSE smoke

先打开 SSE 通道：

```powershell
curl -N http://localhost:8090/mcp/sse
```

SSE 响应会返回本次会话对应的 message endpoint。真实 MCP 客户端需要使用这个 session-specific endpoint 发送 JSON-RPC 请求，例如 `tools/list`。

支持 HTTP/SSE JSON 配置的 MCP 客户端可以从这个样例开始：

```text
deploy/mcp/http-sse-client.json.sample
```

## 验收记录

真实验收时需要记录：

- 客户端名称和版本。
- 连接 URL：`http://localhost:8090/mcp/sse`。
- `tools/list` 是否能看到 CityCopilot 工具。
- 至少一个只读工具调用是否成功。
- 相关截图或日志。

HTTP `tools/list` 和 `tools/call` 必须继续受应用登录态和权限模型保护。匿名用户只能看到无权限要求的工具；已登录用户只能看到自己有权调用的工具。不要在客户端配置里传入伪造权限；服务端会从当前认证用户上下文推导权限。
