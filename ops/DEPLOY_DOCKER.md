# Docker 部署指南

本项目服务组成：

- `gateway`（Nginx，对外入口端口 `80`）
- `backend`（NestJS，容器内端口 `3030`）
- `web`（Vue 静态服务，容器内端口 `5173`）

源站可使用 HTTP，由 Cloudflare 代理后对外提供 HTTPS。

## 1. 准备配置文件

在仓库根目录执行：

```bash
cp backend/config.yaml.example backend/config.yaml
cp .env.docker.example .env.docker
```

说明：

- `backend/config.yaml`：后端运行配置（本地文件，已加入 git 忽略）
- `.env.docker`：Docker Compose 环境变量覆盖（本地文件，已加入 git 忽略）

## 2. 配置后端参数

编辑 `backend/config.yaml`，重点确认：

- `web.allowedOrigin`：Web 访问来源
- `security.*`：JWT 密钥、刷新密钥、截图密码、注册码
- `realtime.namespace`：Socket 命名空间（默认 `/api/v1/ws`）
- `realtime.publicWsUrl`：返回给 Android 设备的公网 WS 地址

## 3. 配置 Docker 环境变量

编辑 `.env.docker`：

- `DOMAIN`
- `WEB_ALLOWED_ORIGIN`
- `WEB_VITE_API_BASE_URL`
- `WEB_VITE_WS_BASE_URL`
- `BACKEND_SERVER_HOST`
- `BACKEND_SERVER_PORT`
- `WS_NAMESPACE`
- `PUBLIC_WS_URL`
- `TZ`

## 4. 构建并启动

```bash
docker compose --env-file .env.docker up -d --build
```

## 5. 验证部署

```bash
docker compose --env-file .env.docker ps
curl -I http://127.0.0.1/
curl http://127.0.0.1/api/v1/dashboard/devices
curl "http://127.0.0.1/socket.io/?EIO=4&transport=polling"
```

预期结果：

- `/` 可访问
- `/api/v1/*` 返回后端响应
- `/socket.io/*` 返回 Socket.IO 握手数据

## 6. Cloudflare 配置建议

1. 将 `doing.seimo.cn` 的 DNS 记录设为 `Proxied`（橙云）。
2. SSL/TLS 模式建议：
   源站仅 HTTP：`Flexible`
   源站有有效 HTTPS：`Full (strict)`
3. 确认 Cloudflare 已启用 WebSocket 代理。

## 7. Android APP 接口 URL 代码位置

如果要手动调整 Android 客户端接口地址，请修改以下位置：

1. API 基础地址定义：
   `android/app/build.gradle.kts`
   `buildConfigField("String", "SERVER_BASE_URL", ".../api/v1")`
2. API 调用入口：
   `android/app/src/main/java/com/whatisseimo/doing/network/BackendClient.kt`
   通过 `BuildConfig.SERVER_BASE_URL` 拼接 register/heartbeat/events/screenshot 等接口。
3. HTTP 明文域名白名单：
   `android/app/src/main/res/xml/network_security_config.xml`
   使用新 HTTP 域名或 IP 时需要更新 `<domain>`。
4. 界面展示文案（可选）：
   `android/app/src/main/java/com/whatisseimo/doing/ui/MainScreen.kt`
   `text = "Server: ..."`

说明：

- 设备端 WebSocket 地址来自后端注册接口返回的 `wsUrl`，
  由 `PUBLIC_WS_URL` 或 `realtime.publicWsUrl` 控制。

## 8. 停止服务

```bash
docker compose --env-file .env.docker down
```
