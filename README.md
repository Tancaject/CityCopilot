# CityCopilot

CityCopilot 是一个本地生活交易后端，覆盖店铺发现、内容社区、优惠券秒杀和场馆/餐厅预约。重点在高并发下的交易一致性：秒杀不超卖、请求可幂等重试、消息不丢、异常可对账修复。

## 核心能力

- 秒杀准入：Redis + Lua 原子扣减库存与一人一单，MySQL 准入账本持久化每次准入，Redis Stream 异步落单
- 幂等与恢复：全局幂等键、准入结果不确定时可用同一键重试，进程重启后从账本与 Stream 恢复
- 可靠消息：事务 Outbox + RocketMQ，消费端 Inbox 去重，死信可查询与幂等重放
- 一致性对账：定时比对 MySQL 与 Redis 库存，发现偏差先封住新准入，再由管理员带证据哈希修复
- 订单与支付：到期关单、库存回补、取消后重买；持久化模拟支付、HMAC 回调验签、回调幂等和退款对账
- 流量与缓存：nginx IP 限流、用户令牌桶、售罄短缓存；Caffeine + Redis 店铺缓存由投影 Outbox 驱动失效
- 预约：报价、名额暂留、原子改期、到期自动释放、到店核销
- 店铺发现：Redis GEO 附近检索（版本化投影），GEO 不可用时回退 MySQL
- 可观测性：Prometheus 指标与告警规则、Grafana 看板

## 项目结构

| 模块 | 职责 |
|---|---|
| `backend/contracts` | 跨服务的业务 DTO、权限码与事件协议 |
| `backend/http-support` | 共享请求时限、HTTP 并发限制与可取消传输 |
| `backend/core` | 用户、社区、店铺、权限、审计和对外 API |
| `backend/commerce-service` | 优惠券、订单、秒杀、预约、报价、对账和事件 |
| `frontend` | Vue 3 用户端与管理端 |

Core 和 Commerce 以独立进程运行：Core 负责身份、权限和对外 API，Commerce 独占交易写入，Commerce 不可用时 Core 直接失败，不回退到本地写入。

默认部署各一个实例；双实例配置和复现命令见 [Compose 指南](docs/docker-compose.md)，实测数字统一见 [E6 证据](docs/evidence/2026-07-26-e6-performance-and-recovery.md)。

## 技术栈

Java、Spring Boot、MyBatis-Plus、MySQL、Redis、Lua、RocketMQ、Vue、TypeScript、Prometheus 和 Grafana。依赖版本以 [Maven 配置](pom.xml)、[前端锁文件](frontend/package-lock.json)和 [Compose 配置](compose.yaml)为准。

## 快速开始

准备 JDK 17、Node.js 22.12+ 和 Docker Desktop，然后在仓库根目录的 PowerShell 中启动基础设施：

```powershell
.\tools\citycopilot.ps1 start
```

该命令会创建本地 `.env`，启动项目所需的 MySQL、Redis 和 RocketMQ，并完成 Core 与 Commerce 两个数据库的迁移。已有数据库首次接管和可选样例数据导入见 [数据库迁移运行手册](docs/database-migrations.md)。迁移成功后从 IDE 依次启动 Commerce 和 Core；详细命令与端口见 [后端模块说明](backend/README.md)。

高德 Key 可以留空，首次启动不需要第三方凭据。服务启动后可检查健康状态：

```powershell
Invoke-RestMethod http://127.0.0.1:8081/actuator/health
Invoke-RestMethod http://127.0.0.1:8083/actuator/health
```

启动前端：

```powershell
cd frontend
npm ci
npm run dev
```

前端默认地址为 `http://localhost:5173`。基础设施状态与排障方式见 [Docker Compose 指南](docs/docker-compose.md)。

预约入口位于店铺详情，用户从 `/bookings` 查看安排，商家从 `/admin/bookings` 管理排期、价目与核销。两条完整操作路径见[预约用户指南](docs/architecture/booking-user-guide.md)，已验证范围和待验收项见[交付证据](docs/evidence/booking-delivery-evidence.md)。
