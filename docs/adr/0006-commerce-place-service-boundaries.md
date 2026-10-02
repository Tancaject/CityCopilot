# ADR-0006：Commerce 与 Place 边界及数据所有权

状态：接受  
日期：2026-07-26

## 背景

为了展示真实微服务能力，需要抽出具有独立事务、一致性和扩缩容特征的领域，而不是按 Controller 数量拆服务。

## 决策

- `commerce-service` 拥有 coupon、seckill、reservation、order、outbox、inbox 数据以及业务 Redis key。
- `place-service` 仅在指标触发后抽出，拥有 shop、shop_type、GEO 和地理批任务。
- Place 公开读取由既有服务映射为 [`ShopViewDTO`](../../backend/core/src/main/java/com/citycopilot/core/dto/ShopViewDTO.java) 与 [`ShopTypeDTO`](../../backend/core/src/main/java/com/citycopilot/core/dto/ShopTypeDTO.java)：详情和旧版附近接口保留原店铺字段；无坐标分类查询保留原分页载体与元数据，名称和附近查询仍返回列表；统一发现继续使用独立的 `ShopDiscoveryItemDTO`。分类字典的缓存仍使用既有存储格式，公开视图不带原本隐藏的审计时间。Shop 写入请求和 GEO 更新写接口不因读取视图调整而改变。
- Place 的 `IShopService`、`IShopTypeService` 不继承 MyBatis `IService`，控制器通过权威店铺视图、当前用户可管理预约店铺、分类读取和统计方法访问数据；不向调用方暴露 Query/Wrapper/Page builder。内部发现与批量 GEO 实现直接使用 Place 的既有 Mapper，坐标写入继续经过店铺服务的权限、事务、缓存和投影链。Shop 写请求暂保留既有实体字段及稀疏更新语义；这一步不声称完全消除所有持久化类型，也不改变工具版本。
- `core-service` 保留 Identity、Community 和外部兼容 API；订单、优惠券、一致性及死信能力仅通过 Commerce facade 调用，不保留运行时本地 writer 回退。
- Identity 公开响应使用独立视图：基础用户与会话沿用 `UserDTO`，扩展资料由现有资料服务映射为 [`UserProfileDTO`](../../backend/core/src/main/java/com/citycopilot/core/dto/UserProfileDTO.java)。资料不存在仍返回空数据，审计时间不外传；实体新增字段不会自动加入该公开响应。Identity 的业务接口不继承 MyBatis `IService`：单用户和 Community 批量作者读取返回 `UserDTO`；后台分页和角色变更通过明确的业务方法执行。角色校验、更新、会话撤销和审计保留原有顺序与失败语义，不因下沉到服务层增加事务。
- 服务使用独立 schema 账号；禁止跨服务私表访问、跨库 Join 和数据库外键。
- 同步查询/命令使用版本化 HTTP；已发生事实使用 RocketMQ 事件。
- 共享交易边界使用 [`CommerceContracts`](../../backend/contracts/src/main/java/com/citycopilot/contract/commerce/CommerceContracts.java) 的请求和结果类型，不共享持久化实体。准入、订单概览与详情、订单动作、一致性修复和错误响应由同一契约定义；Core 只做外部 API 适配。类型化不得改变现有 HTTP 状态、业务码、幂等身份和证据哈希；订单 ID 在传输层保持字符串，金额使用整数分，日期保留现有 HTTP 编码。订单详情的 `inventoryRecovery` 仅在返库存待恢复或需人工修复时有值；客户端兼容可选字段缺失或为 null，以及响应新增字段。
- Voucher 查询/写结果、运营计数、死信查询/重放和准入修复重排同样使用共享结果类型。Voucher ID 保持数值，订单 ID 保持字符串；Commerce 响应中普通券的秒杀字段和未重放死信的时间仍为 null，Core 外部响应沿用既有空字段省略规则。运营计数缺失保持不可用，不按零补齐。Voucher 写请求继续使用稀疏字段 Map：字段缺省、显式 null 和数值零沿用既有校验语义；一致性快照与证据哈希的输入 Map、历史死信载荷 JSON 保留其动态结构。
- 订单列表直接返回订单保存的券说明、规则与金额快照，不读取优惠券当前价格，也不让客户端逐单补查详情。列表、详情和动作响应的 `state`、`statusText` 统一由 Commerce 按已落库的 `status` 生成；异步准入的 `processing` 与已落库订单状态分开表达。Core 通过共享契约透传这些字段，前端不维护另一套状态或价格事实。
- 死信重放的 `already_replayed` 只含状态与记录 ID，`processing` 带原幂等键，`completed` 带订单版本；响应 `status` 始终表示重放状态。工具结果 Schema 随具体返回类型公开；既有审批快照与新 Schema 不一致时须重新预览审批，不自动补字段、重算审批哈希或改变历史授权。
- 当前 E4 采用维护窗口全量回填和单一 writer 切换；在 mutation change feed 覆盖完整之前不宣称通用在线 CDC，也不使用无事务应用双写。

## 结果与回滚

拆分增加网络失败成本，但形成可独立发布的数据所有权。Core 已硬路由到 Commerce，Commerce 不可用时失败关闭；旧 Core writer、Redis Stream worker、Redisson 锁、旧 Outbox、旧表初始化和迁移回退工具均已物理删除。Commerce 只会为“订单、Reservation、recovery 历史均为空且该券没有 in-flight admission”的全新秒杀券自动初始化 Redis 库存；存在历史数据但 Redis 库存缺失时保持准入关闭。库存或 buyer 成员不一致时，先用 Redis fence 暂停该券准入，确认该券 in-flight 集合为零，再通过带证据 hash 的一致性修复流程原子重建库存和 buyer set；共享 Stream 的 pending/lag 只作为全局健康诊断，不阻断无关券的修复。数据库的一人一券唯一约束始终作为 buyer 镜像异常时的最终防线。通用券更新接口禁止直接改变已有秒杀库存，避免 MySQL 与 Lua 准入库存分叉。故障处置只能修复或恢复当前 Commerce；若要恢复到历史实现，必须部署历史制品并单独迁移数据，不存在 profile 或环境变量回退入口。
