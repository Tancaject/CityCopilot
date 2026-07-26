# ADR-0006：Commerce 与 Place 边界及数据所有权

状态：接受  
日期：2026-07-26

## 背景

为了展示真实微服务能力，需要抽出具有独立事务、一致性和扩缩容特征的领域，而不是按 Controller 数量拆服务。

## 决策

- `commerce-service` 拥有 coupon、seckill、reservation、order、outbox、inbox 数据以及业务 Redis key。
- `place-service` 仅在指标触发后抽出，拥有 shop、shop_type、GEO 和地理批任务。
- `core-service` 保留 Identity、Community、外部兼容 API 和过渡期 facade。
- 服务使用独立 schema 账号；禁止跨服务私表访问、跨库 Join 和数据库外键。
- 同步查询/命令使用版本化 HTTP；已发生事实使用 RocketMQ 事件。
- 当前 E4 采用维护窗口全量回填和单一 writer 切换；在 mutation change feed 覆盖完整之前不宣称通用在线 CDC，也不使用无事务应用双写。

## 结果与回滚

拆分增加网络失败与数据迁移成本，但形成可独立发布的数据所有权。`CITYCOPILOT_COMMERCE_ENABLED` 是路由开关；一旦 Commerce 已产生新写入，禁止仅改开关直接切回旧 writer。回滚必须关闭交易准入、排空队列、反向同步增量并通过行数、主键范围、金额、库存和 hash 门禁。Commerce 数据只读保留，不删除审计记录。
