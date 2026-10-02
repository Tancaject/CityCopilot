# 预约实施基线

本文件记录执行时实际观察到的证据；业务规则和任务范围以[实施计划](../architecture/booking-pricing-implementation-plan.md)为准。

2026-09-10 的实现前基线：工作分支为 `main`，工作树有 349 个变更路径（包括本次实施计划，其他变更已存在）。本轮不提交、不重置这些变更。原始路径列表保存于本机临时目录 `citycopilot-booking-baseline-status.txt`。

| 检查 | 实际结果 |
|---|---|
| Java / Maven | Java 17.0.12 / 仓库 `.tools/apache-maven-3.9.9` |
| 后端 | 根目录 `mvn.cmd -q clean verify` 退出码 0；Surefire/Failsafe XML 合计 377 项，失败 0、错误 0、跳过 4 |
| 前端 | `frontend` 目录 `npm.cmd run build` 退出码 0，包含 TypeScript 检查及 Vite 构建 |
| Docker | 此次检查时 Docker daemon 未运行；不把跳过的容器检查记作通过 |
| 新业务数据库验证 | 新建隔离 MySQL 8.0.44 实例，数据目录 `.tools/booking-mysql/data`，仅监听 `127.0.0.1:33319`，数据库 `citycopilot_booking_test`；与系统已有 MySQL80 服务分开 |

原始构建日志在本机临时目录 `citycopilot-booking-baseline-maven.log` 和 `citycopilot-booking-baseline-frontend.log`，逐套计数在 `citycopilot-booking-baseline-suites.json`。这些是本地验证证据，不代表生产部署、真实模型调用或上线容量。

后续并发验收使用真实 JDBC 事务与两个独立连接池；商家权限 HTTP 依赖可以在领域集成检查中替换为固定权限回答，但数据库、事务、锁和 SQL 不能被 Mock 替代。验证脚本必须拒绝对非测试数据库执行清理。
