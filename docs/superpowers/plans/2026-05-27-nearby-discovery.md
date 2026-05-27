# 附近混合发现实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 完成“本地已入驻店铺 + 高德周边 POI”的统一发现能力。

**Architecture:** 后端新增发现层，分别读取本地店铺和高德周边 POI，再由聚合服务统一去重、排序、分页。前端店铺列表只调用统一发现接口，并按来源决定是否允许进入店铺详情。

**Tech Stack:** Spring Boot 3、MyBatis Plus、Redis、RestTemplate、高德 WebService、Vue 3、TypeScript。

---

### Task 1: 后端发现模型

**Files:**
- Create: `backend/core/src/main/java/com/citycopilot/core/dto/ShopDiscoveryItemDTO.java`
- Create: `backend/core/src/main/java/com/citycopilot/core/service/discovery/ShopDiscoveryQuery.java`
- Create: `backend/core/src/main/java/com/citycopilot/core/service/discovery/ShopDiscoveryProvider.java`

- [x] 定义统一返回项，支持本地店铺和高德 POI。
- [x] 定义发现查询对象，统一分页、类型、关键词、坐标参数。
- [x] 定义 Provider 接口，隔离不同数据源。

### Task 2: 聚合排序与去重

**Files:**
- Create: `backend/core/src/main/java/com/citycopilot/core/service/discovery/ShopDiscoveryService.java`
- Test: `backend/core/src/test/java/com/citycopilot/core/service/discovery/ShopDiscoveryServiceTest.java`

- [x] 先写失败测试，覆盖本地优先、距离排序、去重、分页。
- [x] 实现聚合服务。
- [x] 运行单测并确认通过。

### Task 3: 本地店铺 Provider

**Files:**
- Create: `backend/core/src/main/java/com/citycopilot/core/service/discovery/LocalShopDiscoveryProvider.java`

- [x] 根据类型、关键词、坐标查询 `tb_shop`。
- [x] 有坐标时计算距离并按半径过滤。
- [x] 无坐标时保留普通分页查询。

### Task 4: 高德周边 Provider

**Files:**
- Modify: `backend/core/src/main/java/com/citycopilot/core/config/AmapProperties.java`
- Modify: `backend/core/src/main/resources/application.yaml`
- Modify: `backend/core/src/main/java/com/citycopilot/core/dto/AmapPoiDTO.java`
- Modify: `backend/core/src/main/java/com/citycopilot/core/service/AmapGeoService.java`
- Create: `backend/core/src/main/java/com/citycopilot/core/service/discovery/AmapPoiDiscoveryProvider.java`

- [x] 增加高德周边搜索 URL 配置。
- [x] 增加高德周边搜索方法。
- [x] 把高德 POI 转成统一发现返回项。
- [x] 高德不可用时返回空列表，不中断本地结果。

### Task 5: API 接入

**Files:**
- Modify: `backend/core/src/main/java/com/citycopilot/core/controller/ShopController.java`

- [x] 新增 `/shop/discovery`。
- [x] 保留旧接口。
- [x] 非法坐标返回明确错误。

### Task 6: 前端接入

**Files:**
- Modify: `frontend/src/types.ts`
- Modify: `frontend/src/api/citycopilot.ts`
- Modify: `frontend/src/views/ShopListView.vue`

- [x] 新增发现结果类型。
- [x] 新增 `fetchShopDiscovery`。
- [x] 店铺列表改用统一接口。
- [x] 高德 POI 显示为“附近地点”，不跳转店铺详情。

### Task 7: 验证

**Commands:**
- `.\.tools\apache-maven-3.9.9\bin\mvn.cmd -q -pl backend/core test`
- `npm run build`

- [x] 后端单测通过。
- [x] 前端构建通过。
- [x] 检查关键文件 diff，确认没有把高德 POI 自动入库。
