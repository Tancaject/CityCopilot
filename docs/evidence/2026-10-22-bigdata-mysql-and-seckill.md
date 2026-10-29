# 百万级数据 MySQL 优化与秒杀压测（2026-10-17 至 10-22）

## 环境与方法

- 硬件：单台笔记本 Intel i9-14900HX（24 核 32 线程），Windows 11 + WSL2 Docker Desktop，Docker 虚拟机 15.5 GB 内存。WSL 下 Docker 卷的 fsync 约 5 ms（宿主机 NTFS 约 2 ms），刷盘明显慢于服务器 NVMe，绝对值只用于同机改前改后对比。
- 部署：独立 compose 项目 `citycopilot-bigdata`（[覆盖文件](../../tools/loadtest/bigdata-compose.yaml)），单 Core、单 Commerce 容器，Core 与 Commerce 各一个 MySQL 8.4，Commerce Redis 一主一从；Kafka 关闭。
- 数据：[`bigdata_seed.py`](../../tools/loadtest/bigdata_seed.py) 按 1/10 规模生成，约 3.4 GB：用户 10 万、店铺 1 万、笔记 5 万；订单、预留、准入账本各约 100 万行，库存恢复记录 127 万行，Outbox 50 万行（压测后约 110 万）；店铺与用户热度按幂律分布。
- 压测：k6 2.3.0 容器在同一 Docker 网络内直连 Core，恒定到达率（开环）执行器；压测端上限先用 Nginx 直接返回的路径验证，10000 rps 时 p99 0.4 ms。脚本见 [`tools/loadtest/`](../../tools/loadtest)，每级同时采集 MySQL 语句摘要、锁等待和连接池指标（[`mysql_report.py`](../../tools/loadtest/mysql_report.py)）。原始结果 JSON 在 `tools/loadtest/results/`，不纳入版本库。

## 秒杀

| 场景 | 改前（默认配置） | 改后 |
|---|---|---|
| 售罄场景：库存 2000，5000 rps 持续 30 s | 5xx 36%，p50 23 ms | 两次：p50 2.4 / 3.6 ms，p95 13.7 / 8.7 ms，p99 278 / 146 ms，5xx 1.9% / 1.1% |
| 库存充足，每个请求都持久化准入：1000 rps | 每秒受理约 332 单，5xx 67% | 60 s 受理 59950 单（约 1000 单/s），5xx 0.1%，p50 172 ms，p99 455 ms |

定位与改动：

- 锁等待只占采样的 6%–10%；对照实验关闭 binlog 与 redo 刷盘后准入写入由 48.5 ms 降到 6.0 ms，瓶颈是每次提交的刷盘。准入账本两次写改为应用层组提交（`CommerceAdmissionLedgerWriter`），语句数降到约 1/4，饱和吞吐提升约 13%。
- 每个准入请求阻塞约 200 ms 等所在批次落盘，Core→Commerce 并发名额 64 与 Tomcat 200 线程把落库上限压在约每秒数百单；调整为名额 512、入口上限 768、Tomcat 800 线程、刷写线程 8。
- 容器内存不足 2 GB 时 JVM 自动选用 Serial GC，压测中 Core 累计停顿约 25 s；Dockerfile 显式使用 G1，Core 与 Commerce 内存上限调为 1.5 GB。
- 售罄本地标记每秒过期后，过期窗口内的请求会写入并随即拒绝准入账本；改为标记失效时先读 Redis 库存。
- 剩余瓶颈：Stream 建单约 460 单/s，持续高于该速率受理时，已受理未建单积压超过 5 万条会触发准入背压。

## MySQL

| 项目 | 改前 | 改后 |
|---|---|---|
| Commerce 缓冲池 128 MB → 1 GB、redo 100 MB → 1 GB，秒杀 600 rps 每秒受理 | 191 单 | 352 单（2 GB 为 365 单） |
| Outbox 投递取数，积压 108 万行 | 16.2 s（全表扫描加排序） | 10 ms（按状态拆三路走索引，取出的 50 条一致） |
| 库存对账买家比对 / 未结预留查询 | 1175–1723 ms / 1255–1802 ms，扫描约 120 万行 | 47–69 ms / 29–37 ms（V15 索引） |
| 后台运营汇总接口，100 rps | p99 1294 ms | p99 5.5 ms（30 s 缓存，200 rps 时 p99 5.3 ms） |
| 订单超时关单 | 被维护 fence 锁住的券排在队头时全部停摆；修复后约 11 单/s | 按券成批约 435 单/s（V16、V18 索引） |

浏览接口 6000 rps 时 p99 19 ms，订单查询 3000 rps 时 p99 7.5 ms，均未到拐点（只测到这两级）；预约 400 次/s 时 5xx 由 20.6% 降到 1.5%。
