# CityCopilot 地图能力接入计划

## 决策

地图能力按“数据底座优先，地图 UI 渐进增强”接入。店铺仍以自有数据库为主数据源，高德地图作为地址解析、POI 校准和地图展示能力提供方。

## 当前交付

- 后端启动时把 `shop.x`、`shop.y` 同步到 Redis GEO；只有 `geo_status = 'verified'` 的店铺可进入索引。
- Redis GEO 同时维护全量索引 `shop:geo:all` 和类型索引 `shop:geo:{typeId}`。
- 当前消费主链路统一使用 `/shop/discovery`：Redis GEO 先返回附近本地店铺 ID，MySQL 回表确认状态并补齐详情，再与高德 POI 合并。
- `/shop/nearby` 和 `/shop/of/type` 的坐标查询仅作为兼容入口，内部委托同一个本地发现 Provider，不再维护独立 GEO 查询实现。
- 本地发现支持 `mysql`、`shadow`、`redis` 三种模式；默认使用 Redis，索引未就绪或 Redis 异常时按经纬度包围盒查询 MySQL 并用 Haversine 精确过滤。
- Redis GEO 全量或增量更新期间移除 generation 标记；成功后发布新 generation，查询前后代次不一致时丢弃结果并走 MySQL 降级。店铺写入在 MySQL 事务提交后更新索引，失败会把本进程标记为 stale，最长 60 秒自动尝试全量修复。
- 当前 Compose 拓扑只运行一个 Core 写实例，因此使用 JVM 内同步即可；扩展为多 Core 写实例前，必须增加分布式互斥或版本化索引 Key 原子切换，不能直接横向扩容。
- 新增 `/geo/geocode`，用于通过高德 Web 服务把地址解析为经纬度。
- 新增 `POST /geo/shops/{id}/geocode`，用于按店铺地址调用高德 geocode 并写回 `x/y/adcode/geo_source/geo_status`。
- 新增 `GET /geo/shops/{id}/pois`，用于按店铺名搜索高德 POI 候选。
- 新增 `POST /geo/shops/{id}/poi`，用于确认某个 POI 并写回 `poi_id/x/y/adcode/geo_source/geo_status`。
- 前端店铺列表新增“附近优先”入口，店铺详情新增位置面板。
- 前端新增 `/admin/geo-shops` 店铺地理校准台，支持店铺搜索、状态筛选、POI 候选确认、地址解析、手动写回坐标和 Redis GEO 重建。
- 地理校准台和 `/geo/**`、`/shop/{id}/geo`、`POST/PUT /shop`、`POST /voucher`、`POST /voucher/seckill` 等写接口已接入权限码校验，当前支持 `admin`、`geo_operator`、`auditor`、`merchant` 等角色映射。
- 角色权限映射已升级为配置驱动，默认由 `citycopilot.security.role-permissions` 提供；`/user/me` 会返回当前用户权限，前端导航和路由守卫只做体验层判断。
- 新增 `POST /geo/shops/batch-geocode`、`GET /geo/batch-jobs`、`GET /geo/batch-jobs/{jobId}`、`GET /geo/batch-jobs/{jobId}/items`、`POST /geo/batch-jobs/{jobId}/cancel`、`POST /geo/batch-jobs/{jobId}/retry`，用于批量解析缺坐标店铺、查询任务进度、取消任务和重试失败明细。
- 批量任务落库到 `tb_geo_batch_job` 和 `tb_geo_batch_job_item`，服务重启后仍能查询任务和单店处理明细。
- 新增 `GET /admin/audit-logs` 和 `tb_operation_log`，用于追踪店铺修改、坐标写回、GEO 索引重建、批量任务创建与完成。
- 店铺表新增 `poi_id`、`adcode`、`geo_source`、`geo_status`、`geo_updated_at`，并提供 `PUT /shop/{id}/geo` 写回校准结果。
- 用户表新增 `role` 字段，用于区分普通用户和管理员。
- 新增 `POST /geo/shop-index/sync`，用于字段修正或批量校准后手动重建 GEO 索引。
- 附近搜索半径通过 `citycopilot.geo.nearby-radius-meters` 配置，默认 5000 米。
- 店铺分页大小、最大分页大小、默认城市和 POI 城市限制通过 `citycopilot.geo.shop-page-size`、`citycopilot.geo.max-shop-page-size`、`citycopilot.geo.default-city`、`citycopilot.geo.city-limit` 配置。
- 高德 Web 服务调用增加 Redis 缓存和分钟级限流，配置项为 `citycopilot.amap.cache-ttl-minutes` 与 `citycopilot.amap.rate-limit-per-minute`。
- 高德 Web 服务调用增加熔断保护和 Redis 指标计数，可通过 `GET /geo/amap/status` 查看熔断状态、分钟调用量、成功/失败、缓存命中和限流次数。
- 高德异常会记录 `infocode/info`，前端收到更明确的失败原因。
- 审计日志支持 `GET /admin/audit-logs/export` 导出最近 1000 条匹配记录。
- 审计日志支持 `GET /admin/audit-logs/status` 查看留存策略，并通过 `POST /admin/audit-logs/cleanup` 清理过期记录。
- 运营侧新增 `GET /admin/ops/summary`、`GET /admin/api/endpoints`、`GET /admin/api/errors`、`GET /admin/api/permissions`，用于运营概览、API 契约和权限配置自检。
- 运营侧新增 `/admin/vouchers` 优惠券配置台，支持普通券/秒杀券创建、编辑、上下架和秒杀库存/时间配置，并记录优惠券操作审计。

## 边界

- 前端不直接调用高德 Web 服务，避免暴露 Web 服务 Key。
- 后端高德 Web 服务 Key 通过 `CITYCOPILOT_AMAP_WEB_SERVICE_KEY` 配置。
- 前端高德 JS Key 用于地图渲染、地点搜索、逆地理编码；配置 Key 时优先使用高德 `convertFrom` 把浏览器 GPS/WGS84 定位转换为系统统一的 GCJ-02 坐标。
- 未配置 Key 或高德 JS SDK 暂时不可用时，浏览器定位使用本地 WGS84→GCJ-02 转换兜底，不会把中国大陆内的原始 WGS84 坐标直接发送给附近发现接口。
- MySQL 是店铺事实源；Redis GEO 只保存店铺 ID 与坐标，高德外部 POI 不写入本地 GEO 索引。
- 高德服务不可用时，列表仍可按普通分页展示，详情页仍展示坐标位置面板。

## 调用示例

```bash
curl -X POST "http://127.0.0.1:8081/geo/shops/1/geocode?city=杭州&address=杭州市西湖区文三路" -H "authorization: $TOKEN"
curl -X POST "http://127.0.0.1:8081/geo/shops/1/geocode?city=杭州&verified=true" -H "authorization: $TOKEN"
curl "http://127.0.0.1:8081/geo/shops/1/pois?city=杭州&keyword=星巴克" -H "authorization: $TOKEN"
curl -X POST "http://127.0.0.1:8081/geo/shops/1/poi?poiId=B0FFG7XXXXX&city=杭州&keyword=星巴克&verified=true" -H "authorization: $TOKEN"
curl -X POST "http://127.0.0.1:8081/geo/shops/batch-geocode?city=杭州&onlyMissing=true&limit=20" -H "authorization: $TOKEN"
curl "http://127.0.0.1:8081/geo/batch-jobs/$JOB_ID" -H "authorization: $TOKEN"
curl "http://127.0.0.1:8081/geo/amap/status" -H "authorization: $TOKEN"
curl "http://127.0.0.1:8081/admin/audit-logs/export?targetType=shop&targetId=1" -H "authorization: $TOKEN"
curl -X POST "http://127.0.0.1:8081/geo/shop-index/sync" -H "authorization: $TOKEN"
```

## 后续

1. 如果权限需要在页面上运营，再把 `citycopilot.security.role-permissions` 升级为数据库或配置中心驱动。
2. 地图数据可以继续增加城市、商圈、坐标可信度评分和人工复核队列。
3. Core 横向扩容前，把当前单写实例的本地重建锁升级为版本化 GEO Key 与 active 指针切换。
