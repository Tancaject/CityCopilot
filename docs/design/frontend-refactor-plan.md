# CityCopilot 前端重构计划

## 目标

把当前嵌在 Spring Boot resources 里的静态 HTML 前端，逐步迁移为独立的 Vue 3 + TypeScript + Vite 应用。后端保持 API 服务职责，Nginx 只作为部署层存在，负责静态资源、SPA fallback、API 反向代理和缓存策略。

同步备注（2026-05-25）：项目已完成对外身份迁移为 CityCopilot，主前端已接入 Agent 流式对话和 Trace 看板；旧静态页面仍作为历史资源保留在 `core/src/main/resources/nginx-1.18.0/html/citycopilot/`。

## 当前判断

当前项目的业务练习价值很高，但前端形态偏教程项目：

- 页面、CSS、JS、图片和 Nginx 二进制都放在 `src/main/resources/nginx-1.18.0`。
- 前端以多 HTML 页面 + 全局 Vue 2 + Axios 为主，缺少工程化构建、类型约束、路由和状态管理。
- 部署配置与源码资源混在一起，难以做 CI/CD、CDN 缓存、灰度和独立回滚。
- 旧页面存在编码显示问题，继续在原结构里堆功能会让维护成本越来越高。

## 目标架构

```text
CityCopilot/
  core/                   Spring Boot 核心业务 API
  agent/                  Agent 主服务和 SSE 对话
  mcp-server/             HTTP MCP-style 工具层
  eval/                   YAML 评测与 Markdown 报告
  frontend/               Vue 3 + TypeScript + Vite
  deploy/nginx/           生产部署层配置
  docs/design/            重构计划和设计记录
```

生产请求流：

```text
Browser
  -> Nginx static dist/
  -> /api reverse proxy
  -> Spring Boot backend
  -> MySQL / Redis
```

## 分阶段执行

### 阶段 1：前端独立骨架与首页纵切片

状态：已完成。

交付物：

- 新增 `frontend/` 工程。
- 新增统一 API client，继续兼容后端当前 `Result` 响应格式。
- 迁移首页核心能力：商户类型列表、热门探店笔记、点赞入口。
- 新增独立 Nginx 配置，不再依赖 resources 里的 Nginx。

验收：

- `frontend` 能通过 `/api` 访问现有后端。
- 首页不依赖全局 `vue.js`、`axios.min.js`、`element.js`。
- 旧页面仍保留，不影响当前项目运行。

### 阶段 2：登录态和路由

状态：已完成。

交付物：

- Vue Router。
- 登录页、验证码发送、登录、退出。
- token 存储、401 处理、路由守卫。
- 基础空状态、错误状态、加载状态。

验收：

- 未登录访问受保护操作会进入登录流。
- 登录后刷新页面仍能读取用户态。

实现说明：

- `App.vue` 已收敛为应用壳层。
- `HomeView.vue` 承接首页纵切片。
- `LoginView.vue` 承接手机验证码登录。
- `composables/auth.ts` 管理 token、当前用户、初始化和退出。
- `/api/user/code`、`/api/user/login`、`/api/user/me`、`/api/user/logout` 已纳入 API client。

### 阶段 3：店铺浏览链路

状态：已完成。

交付物：

- 店铺分类页、搜索页、详情页。
- 地理位置查询参数封装。
- 详情页缓存数据展示和错误兜底。

验收：

- 用户可以从首页进入分类，再进入店铺详情。
- API 错误不会造成白屏。

实现说明：

- `/shops` 支持分类查询和名称搜索。
- `/shops/:id` 支持店铺详情展示。
- 顶部搜索框已接入店铺搜索路由。
- 店铺列表和详情统一处理评分、价格、距离和图片地址。

### 阶段 4：内容社区链路

状态：前端迁移已完成，上传后端已补本地存储适配层和基础安全校验；对象存储实现仍可作为生产替换项。

交付物：

- 探店笔记详情、发布、上传图片。
- 点赞、关注、共同关注、关注流。
- 上传改为存储服务适配层，后端不再在控制器里直接写本地静态目录。

验收：

- 发布笔记后关注者 feed 能看到。
- 图片上传有类型、大小和失败提示。

实现说明：

- `/blogs/:id` 支持笔记详情、点赞、关注作者和关联店铺跳转。
- `/blogs/new` 支持标题、正文、关联店铺 ID 和图片上传。
- `/feed` 支持关注流滚动分页入口。
- 发布页和关注流已接入登录保护。
- 当前后端默认使用 `FileStorageService` 的本地实现，上传根目录和公开前缀由 `citycopilot.upload.*` 配置；后续可新增对象存储实现替换。

### 阶段 5：秒杀和订单体验

状态：前端下单入口、订单状态查询、我的订单入口、未支付订单取消、站内模拟支付、退款、运营核销、优惠券配置后台和秒杀券可售性校验已完成；真实支付渠道仍待业务化。

交付物：

- 优惠券列表、秒杀按钮、下单结果。
- 订单状态查询或轮询。
- 我的订单列表和订单详情。
- 支付、取消、退款、核销的最小业务状态机。
- 普通券/秒杀券创建、编辑、上下架和秒杀库存/时间配置。
- 前端幂等提交、防重复点击、活动倒计时。

验收：

- 秒杀接口异步返回订单号后，用户能看到明确的处理状态。
- 后端失败或排队时，前端有可理解的提示。

实现说明：

- 店铺详情页已接入 `/voucher/list/{shopId}`。
- 秒杀券已接入 `/voucher-order/seckill/{id}`。
- 订单查询已接入 `/voucher-order/{id}` 和 `/voucher-order/of/me`。
- 未支付订单取消已接入 `/voucher-order/{id}/cancel`，并同步回补秒杀库存和用户下单标记。
- 站内模拟支付已接入 `/voucher-order/{id}/pay`，退款已接入 `/voucher-order/{id}/refund`，运营核销已接入 `/voucher-order/{id}/use`。
- 运营核销台已接入 `/admin/orders` 和 `/admin/orders/{id}`，由 `order:write` 权限保护。
- 优惠券配置台已接入 `/admin/vouchers`，由 `voucher:write` 权限保护；旧的 `POST /voucher` 和 `POST /voucher/seckill` 也纳入运营权限拦截。
- 秒杀下单前会校验券是否存在、是否上架、是否为秒杀券、是否在活动时间内，并在 Redis 库存缺失时按数据库库存做一次安全初始化。
- 未登录点击秒杀会跳转登录并保留 redirect。
- 下单过程中按钮会进入 loading 状态，成功后展示订单号。
- 秒杀成功后前端会轮询订单状态，异步队列尚未落库时展示处理中。

### 阶段 6：运营后台拆分

状态：运营概览、店铺资料管理、优惠券配置、用户角色管理、内容治理、地理校准台、券核销台、审计、运营摘要、API 契约自检和 Agent Trace 看板已在主前端内完成；独立后台应用仍作为后续拆分方向。

交付物：

- 商户、券、内容审核、用户管理等后台能力。
- 建议作为 `frontend-admin/` 或同 repo 下单独 Vite app。
- 当前已交付 `/admin`、`/admin/shops`、`/admin/vouchers`、`/admin/users`、`/admin/blogs`、`/admin/geo-shops`、`/admin/orders`、`/admin/audit`、`/admin/api`、`/admin/ops/summary`、`/admin/traces`、审计日志留存/导出/清理和 API 契约自检入口。

验收：

- C 端用户界面和运营后台权限、路由、部署可独立演进。

## 关键设计决策

1. 先做生产级单体，不急着微服务化。
2. 前端先采用 Vue 3 + TypeScript + Vite，贴近旧项目已有 Vue 思路，迁移成本低。
3. 初期采用 strangler 模式，新旧前端并存，逐页迁移。
4. Nginx 继续使用，但只放在 `deploy/nginx/`，不再作为应用源码资源。
5. API contract 继续兼容现有 `Result`，已增加 `code` 字段、前端 `ApiError` 和 `/admin/api/endpoints`、`/admin/api/errors` 契约自检入口。
6. 运行探活使用公开 `/health`，覆盖数据库和 Redis 连通性，便于 Nginx、容器和监控系统接入。

## 第一条 walking skeleton

首页从新前端发起请求：

```text
Home view
  -> GET /api/shop-type/list
  -> GET /api/blog/hot?current=1
  -> PUT /api/blog/like/{id}
  -> 401 handled by API client
```

这条链路能验证：构建工具、API 代理、token 注入、接口响应解包、加载/错误状态、静态图片路径。
