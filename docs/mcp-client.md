# CityCopilot MCP 客户端验收

`backend/mcp-server` 是无状态远程适配器，从 Core 的版本化内部 Tool API 获取目录并执行调用，暴露两类入口：

- HTTP 兼容工具接口：`GET /mcp/tools/list`、`POST /mcp/tools/call`
- Spring AI MCP SSE 传输：`GET /mcp/sse`、`POST /mcp/messages`

当前仓库启用的是 HTTP/SSE 形态；`spring.ai.mcp.server.stdio=false`，Claude Desktop/Cursor 的 stdio 形态还没有完成验收。

## 启动 MCP Server

```powershell
.\.tools\apache-maven-3.9.9\bin\mvn.cmd -pl backend/mcp-server -am spring-boot:run
```

模块默认端口是 `8090`。MCP 自身不连接 MySQL 或业务 Redis；启动前需先运行 Core，并为两者配置相同的 `CITYCOPILOT_INTERNAL_SERVICE_TOKEN`。可用 `CITYCOPILOT_CORE_BASE_URL` 覆盖 Core 地址。

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

HTTP `tools/list` 和 `tools/call` 继续受 Core 登录态和权限模型保护。MCP 只转发 `Authorization`、请求 ID 和幂等键，不信任请求体里的 `userId` 或 `permissions`；最终权限判定和权威审计均在 Core 完成。
