# CityCopilot Frontend

这是 CityCopilot 的 Vue 3 + TypeScript 前端。开发时通过 Vite 代理把 `/api` 转发到主后端 `backend/agent` 的 `http://127.0.0.1:8081`，把 `/uploads` 转发到同一个后端。

主要页面包括首页、混合发现店铺列表、店铺详情、订单、关注流、内容发布、AI 面板、地理校准台、订单/券/店铺/用户/内容/审计管理页和 Agent Trace 看板。店铺列表现在调用 `/shop/discovery`，会跟随共享消费位置展示本地已入驻店铺和高德周边 POI；高德 POI 只展示，不进入交易详情。

## 启动

```powershell
cd frontend
npm install
npm run dev
```

`npm run dev` 使用 `scripts/dev-server.mjs`，默认监听 `0.0.0.0` 并输出本机和局域网访问地址。本机默认访问地址：

```text
http://localhost:5173
```

## 构建

```powershell
cd frontend
npm run build
```

生产构建产物输出到 `frontend/dist`。Nginx 示例配置在 `deploy/nginx/citycopilot-frontend.conf`。

## 前端环境变量

| 变量 | 说明 |
|---|---|
| `VITE_API_BASE_URL` | API base URL，默认 `/api` |
| `VITE_AMAP_JS_KEY` | 高德 JS API Key，用于地图组件、地址搜索和选点 |
| `VITE_AMAP_SECURITY_CODE` | 高德 JS 安全密钥 |
| `VITE_DEFAULT_CITY` | 地址搜索、地理校准和首页默认城市，默认 `杭州` |
| `PORT` / `VITE_PORT` | 开发服务器端口，默认 `5173` |

示例：

```powershell
$env:VITE_AMAP_JS_KEY="你的高德 JS API Key"
$env:VITE_AMAP_SECURITY_CODE="你的高德安全密钥"
$env:VITE_DEFAULT_CITY="杭州"
npm run dev
```

## 后端配置

后端推荐使用 `CITYCOPILOT_*` 环境变量：

```powershell
$env:CITYCOPILOT_DB_URL="jdbc:mysql://127.0.0.1:3306/citycopilot?useSSL=false&serverTimezone=UTC&allowPublicKeyRetrieval=true"
$env:CITYCOPILOT_DB_USERNAME="root"
$env:CITYCOPILOT_DB_PASSWORD="你的数据库密码"
$env:CITYCOPILOT_REDIS_HOST="127.0.0.1"
$env:CITYCOPILOT_REDIS_PORT="6379"
```

地图、AI、上传和权限相关后端配置使用 `citycopilot.*` 前缀，例如 `citycopilot.geo.default-city`、`citycopilot.ai.model`、`citycopilot.upload.root-dir`。
