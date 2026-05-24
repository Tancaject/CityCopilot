# HMDP Frontend

独立的 Vue 3 + TypeScript + Vite 前端工程。

## 本地开发

```bash
npm install
npm run dev
```

默认开发地址为 `http://localhost:5173`，开发代理会把 `/api/*` 转发到 `http://127.0.0.1:8081/*`。
上传资源 `/uploads/*` 也会代理到后端，便于本地预览已上传图片。
后端提供公开 `/health` 探活接口，会检查数据库和 Redis 连通性。

当前已迁移路由：

- `/` 首页：分类、热门探店、点赞入口
- `/login` 手机验证码登录
- `/shops` 店铺列表：分类查询和名称搜索
- `/shops/:id` 店铺详情、优惠券列表和秒杀入口
- `/blogs/:id` 探店笔记详情
- `/blogs/new` 发布探店笔记
- `/feed` 关注流
- `/orders` 我的券订单，支持状态查询、取消、模拟支付和退款
- `/admin` 运营概览，需要具备 `admin:access`
- `/admin/geo-shops` 店铺地理校准台，需要管理员登录后使用
- `/admin/shops` 店铺资料管理，需要具备 `shop:write`
- `/admin/vouchers` 优惠券配置，需要具备 `voucher:write`
- `/admin/orders` 运营券核销台，需要具备 `order:write`
- `/admin/users` 用户角色管理，需要具备 `user:write`
- `/admin/blogs` 探店笔记治理，需要具备 `content:write`
- `/admin/audit` 操作审计查询、导出和清理，需要具备 `audit:read`
- `/admin/api` API 契约、错误码和权限矩阵，需要具备 `audit:read`
- `/admin/api/endpoints`、`/admin/api/errors`、`/admin/api/permissions` 后端 API 契约自检

## 构建

```bash
npm run build
```

构建产物输出到 `frontend/dist`，生产环境建议由独立 Nginx 或 CDN 托管。示例配置在 `deploy/nginx/hmdp-frontend.conf`，包含 SPA fallback、`/api` 反向代理、`/health` 探活代理、`/uploads` 静态挂载、缓存和基础安全响应头。

## 后端运行配置

数据库和 Redis 连接可以通过 `HMDP_DB_URL`、`HMDP_DB_USERNAME`、`HMDP_DB_PASSWORD`、`HMDP_REDIS_HOST`、`HMDP_REDIS_PORT`、`HMDP_REDIS_PASSWORD`、`HMDP_REDIS_DATABASE` 覆盖。未配置时沿用本地开发默认值。

## 上传图片

后端 `/upload/blog` 会进行图片大小、扩展名、Content-Type 和文件头校验，并返回 `/uploads/blogs/...` 公共路径。默认文件落在后端 `hmdp.upload.root-dir`，本地开发由 Vite 代理 `/uploads/*` 到后端，生产环境可以把同一目录挂载给 Nginx/CDN，或替换后端 `FileStorageService` 为对象存储实现。

## 地图能力

店铺列表支持浏览器定位后的“附近优先”，会把经纬度传给后端 `/shop/of/type` 或 `/shop/nearby`，由 Redis GEO 做距离排序。店铺详情页会在有坐标时展示位置面板；配置 `VITE_AMAP_JS_KEY` 后会加载高德 JS API 2.0 展示交互地图。

前端顶部城市文案由 `VITE_DEFAULT_CITY` 控制，后端地址解析默认城市由 `hmdp.geo.default-city` 控制。

运营侧可进入 `/admin/geo-shops` 搜索店铺，调用高德 Web 服务查询 POI 候选、地址解析坐标、手动修正经纬度，并在批量校准后重建 Redis GEO 索引。批量任务会落库保存任务状态和单店处理明细。

后端高德 Web 服务已接入 Redis 缓存、分钟级限流、熔断保护和指标计数，可通过 `hmdp.amap.cache-ttl-minutes`、`hmdp.amap.rate-limit-per-minute`、`hmdp.amap.circuit-failure-threshold`、`hmdp.amap.circuit-open-seconds` 调整；附近搜索半径、默认城市、分页大小和 POI 城市限制通过 `hmdp.geo.*` 调整。

地理校准入口依赖 `tb_user.role` 和 `hmdp.security.role-permissions`。给用户表补充 `role` 字段并把手机号 `13686869696` 设为具备 `geo:read`/`geo:write` 的角色后，已有登录态需要退出再重新登录。校准台会展示最近操作审计，后端对应 `/admin/audit-logs`，并支持导出、留存状态查看和过期清理。

## API 契约

后端统一 `Result` 已带 `code` 字段，前端 API client 会抛出保留 `code` 的 `ApiError`。管理员可通过 `/admin/api/endpoints` 查看当前 Spring MVC 路由和权限注解，通过 `/admin/api/errors` 查看错误码说明。

登录验证码发送已接入按手机号的 Redis 分钟级限流，默认 `hmdp.security.login-code-rate-limit-per-phone=3`。

## 智能 AI 助手

全局右下角提供智能 AI 助手入口，对应后端 `/ai/chat`。当前版本优先基于真实店铺、优惠券、距离、批量任务和审计数据生成结构化建议；未配置模型 Key 时也能使用。助手会携带当前路由、店铺详情页上下文和最近对话，支持“这家店怎么样”“附近还有类似的吗”这类追问。

在管理员场景下，助手会读取缺坐标、待确认、批量失败任务和高德服务状态，并给出需要点击确认的运营动作，例如创建缺坐标批量任务、重试最近失败任务、重建 Redis GEO 索引。

后端默认按阿里云百炼 / DashScope OpenAI-compatible 接口调用模型：`hmdp.ai.enabled`、`hmdp.ai.api-key`、`hmdp.ai.api-url`、`hmdp.ai.model`。本地默认模型为 `qwen-plus`，Key 可通过 `HMDP_AI_API_KEY` 或 `DASHSCOPE_API_KEY` 覆盖。生产侧还可以通过 `hmdp.ai.max-message-length`、`hmdp.ai.public-rate-limit-per-minute`、`hmdp.ai.user-rate-limit-per-minute`、`hmdp.ai.admin-rate-limit-per-minute` 控制输入长度和分钟级访问频率。
