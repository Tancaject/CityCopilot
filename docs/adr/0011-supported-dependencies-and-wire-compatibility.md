# ADR 0011：依赖升级与持久化契约兼容

## 决策

Java 运行要求、Spring Boot 与公共依赖版本以[父 POM](../../pom.xml)为准。Spring AI BOM 仅由 [MCP 模块](../../backend/mcp-server/pom.xml)导入；Agent 使用独立的模型 HTTP 客户端及实际调用的 Resilience4j 核心模块。Core 的 ORM 和接口文档库由其 [POM](../../backend/core/pom.xml)显式声明，并选择支持当前 Boot 主版本的 starter。依赖从 Maven Central 获取，不引入未使用的厂商 BOM 或预发布仓库。

搜索模块使用官方 Elasticsearch Java Client 及对应客户端 starter，不引入未使用的 Spring Data Elasticsearch repository 层。

使用 Jackson 3，不安装已弃用的 Jackson 2 自动配置桥接。应用的 `spring.jackson.use-jackson2-defaults` 保留既有 JSON 行为；审批摘要等持久化契约还必须由固定样例验证，不能只依赖该兼容开关。

- Agent checkpoint 的 V1 canonical bytes 和 SHA-256 保持不变，升级不能自动废弃已批准计划。
- Ops 读取原有 `steps_json`，保留已存储的审批摘要。
- Core 预约确认继续校验原有载荷摘要及操作编号，篡改金额仍须拒绝。
- Outbox 重放使用已存储的原始消息体及事件编号，不能在升级时重新序列化历史消息。
- 价格使用整数分，时间保留既有 ISO 表达；可选字段和属性顺序涉及摘要时属于持久化格式的一部分。

MCP 显式保留 SSE 协议及现有端点。迁移到 Streamable HTTP 应单独验证客户端与会话行为，不能随框架升级隐式切换传输协议。

## 升级与回退边界

1. 在修改序列化库前固定受影响的旧持久化样例；升级后验证读取、摘要、幂等编号及拒绝篡改的行为。
2. 编译受影响模块，只执行本次改动关联的装配、序列化、HTTP 和 ORM 检查；不以无关基线测试代替契约验证。
3. 应用与依赖作为完整构建产物发布。数据库变更走[独立迁移链](../database-migrations.md)，不要混入应用启动，也不要因应用回退而自动回滚数据。
4. Elasticsearch 服务端镜像以 [Compose](../../compose.yaml)为准；新主版本使用新卷，从 MySQL 重建。切换及回退遵循[搜索投影运行说明](../design/elasticsearch-shop-search.md#从旧搜索卷升级)。

## 依据

- [Spring Boot 主版本迁移指南](https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-4.0-Migration-Guide)：模块化 starter、Jackson 迁移及兼容配置。
- [Spring AI 支持范围](https://docs.spring.io/spring-ai/reference/getting-started.html)：与 Boot 的版本配套和 BOM 用法。
- [MyBatis-Plus 安装说明](https://baomidou.com/getting-started/install/)：选择对应 Boot 主版本的 starter，分页另引入 SQL 解析模块。
