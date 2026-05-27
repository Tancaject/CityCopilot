# CityCopilot Frontend

这是 CityCopilot 的 Vue 3 + TypeScript 前端。开发时通过 Vite 代理把 `/api` 转发到主后端 `backend/agent` 的 `http://127.0.0.1:8081`，把 `/uploads` 转发到同一个后端。

## 启动

```powershell
cd frontend
npm install
npm run dev
```

默认访问地址：

```text
http://localhost:5173
```

## 构建

```powershell
cd frontend
npm run build
```

生产构建产物输出到 `frontend/dist`。Nginx 示例配置在 `deploy/nginx/citycopilot-frontend.conf`。

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
