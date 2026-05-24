# CityCopilot

CityCopilot 是基于原 hmdp 本地生活业务改造的动作型 AI Agent 平台。项目目标是把店铺、优惠券、订单、地理校准、审计等已有业务能力沉淀为可被 LLM Function Calling 和 MCP client 复用的工具层。

## Architecture

当前后端已拆为模块化单体：

| Module | Role |
|---|---|
| `core` | 原 hmdp 业务主体，保留 MySQL、Redis、Lua 秒杀、GEO、权限、审计等能力 |
| `agent` | CityCopilot Agent 层，包含 Tool Registry、ToolGateway、Router、`/agent/ping` 入口 |
| `mcp-server` | MCP 映射层，复用 Agent Tool Registry 生成 `tools/list` 描述 |
| `eval` | 评估系统预留模块，后续承载 YAML 用例和 Markdown 报告 |

依赖方向保持为 `mcp-server -> agent -> core`，`eval -> agent`。为了避免反向依赖，`@AgentTool` 注解放在 `core` 模块，`agent` 负责扫描和调用。

## Current Agent Surface

- `@AgentTool` 标注第一批核心业务工具：
  - `get_shop_detail`
  - `search_shops_by_type`
  - `search_nearby_shops`
  - `list_vouchers_by_shop`
  - `place_seckill_order`
  - `get_my_orders`
  - `list_shop_types`
- `AgentToolRegistry` 支持直接方法注解和 service 接口注解。
- `ToolGateway` 统一做权限检查、反射调用和 `ToolError` 标准化。
- `AgentRouter` 以启发式规则选择 `REACT` 或 `PLAN_EXECUTE`。
- `mcp-server` 已能把工具元数据映射为 MCP `tools/list` 风格结构。
- `agent` 模块提供 `CityCopilotAgentApplication` 和 `GET /agent/ping`。

## Run

本机需要安装 Maven 后运行：

```powershell
mvn -pl agent spring-boot:run
```

启动后检查：

```text
GET http://localhost:8081/agent/ping
```

旧的 `AiAssistantService` 已标记为 deprecated，暂时保留为兜底路径。

## Local Configuration

敏感配置只从环境变量读取，提交文件不再带真实默认密钥：

```powershell
$env:HMDP_DB_URL="jdbc:mysql://127.0.0.1:3306/hmdp?useSSL=false&serverTimezone=UTC&allowPublicKeyRetrieval=true"
$env:HMDP_DB_USERNAME="root"
$env:HMDP_DB_PASSWORD="..."
$env:HMDP_REDIS_HOST="127.0.0.1"
$env:HMDP_AMAP_WEB_SERVICE_KEY="..."
$env:DASHSCOPE_API_KEY="..."
```

## Verification

当前机器没有 `mvn` 命令，因此后端聚合构建尚未在本机完成。已用 JDK 17 对无外部依赖的 Agent/MCP 核心代码执行轻量自测：

```powershell
javac -encoding UTF-8 -parameters -d target\selftest ...
java -cp target\selftest com.citycopilot.agent.tool.ToolRegistrySelfTest
java -cp target\selftest com.citycopilot.agent.gateway.ToolGatewaySelfTest
java -cp target\selftest com.citycopilot.agent.router.AgentRouterSelfTest
java -cp target\selftest com.citycopilot.agent.schema.JsonSchemaGeneratorSelfTest
java -cp target\selftest com.citycopilot.agent.http.AgentPingHandlerSelfTest
java -cp target\selftest com.citycopilot.mcp.McpToolMapperSelfTest
```

Full Maven verification should be run after Maven is installed:

```powershell
mvn clean verify
```
