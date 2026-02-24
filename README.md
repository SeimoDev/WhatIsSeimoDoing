# WhatIsSeimoDoing V1

Android 客户端 + Web 前端 + NestJS 后端，实现多设备实时前台应用监控、5 分钟统计上报和远程截图。

## 目录结构

- `backend/`: NestJS + SQLite + Socket.IO
- `web/`: Vue3 + Vite 实时监控面板
- `android/`: Kotlin + Compose + Miuix Android 客户端
- `ops/`: 部署模板（含 Nginx 反代示例）
- `docker-compose.yml`: Docker 部署编排（gateway + backend + web）

## 核心能力

- Android 前台切换实时上报（AccessibilityService）
- 每 5 分钟上报当日统计（UsageStats + 通知数 + 解锁数）
- Web 面板实时展示：
  - 当前运行应用（图标/名称/包名/时长）
  - 今日每 APP 使用时长
  - 通知总数、解锁次数
  - 近 7 天趋势 + 明细
- 远程截图：
  - 网页本地会话保存截图密码（不跨设备同步）
  - 每次截图请求与截图结果拉取均由后端校验截图密码
  - 在线设备触发截图，客户端 Root `screencap` 上报
  - 图片仅内存缓存，不落盘
- 保活策略：
  - 前台常驻服务 + 通知
  - 开机自启
  - MIUI 自启动/电池策略引导入口
  - Root 守护脚本拉起服务

## 默认测试地址

- Backend: `http://192.168.2.247:3030`
- API Base: `http://192.168.2.247:3030/api/v1`
- Web: `http://192.168.2.247:5173`

## 后端配置

编辑 `backend/config.yaml`:

```bash
cp backend/config.yaml.example backend/config.yaml
```

```yaml
server:
  host: 192.168.2.247
  port: 3030
web:
  port: 5173
  allowedOrigin: http://192.168.2.247:5173
security:
  jwtSecret: change-this-super-secret-jwt-key
  refreshSecret: change-this-super-secret-refresh-key
  screenshotPassword: change_me_1234
  screenshotTokenTtlMinutes: 10
  registerCode: register_me
storage:
  sqlitePath: ./data/whatis.db
  retentionDays: 90
realtime:
  heartbeatTimeoutSec: 30
  screenshotTimeoutSec: 60
  namespace: /api/v1/ws
  publicWsUrl: ws://192.168.2.247/api/v1/ws
```

## 本地运行

### Backend

```bash
cd backend
npm install
npm run build
npm run start:dev
```

### Web

```bash
cd web
npm install
npm run dev
```

### Android

```bash
cd android
./gradlew.bat :app:compileDebugKotlin
```

说明：在当前 CI/容器资源下，`assembleDebug` 在 dex merge 阶段可能 OOM；`compileDebugKotlin` 已通过，建议在 Android Studio 本机环境打包。

## 图标缺失一次性修复流程

适用场景：发布包含 ROOT 包解析修复后的 Android 客户端后，需要把历史 `icon_hash` 但无 `icon_base64` 的记录回填完整。

1. 安装新客户端并启动 KeepAlive。
2. 在客户端主页面点击 `Sync Apps`（该入口会触发 `forceUploadIcons=true` 的手动同步）。
3. 等待弹窗日志出现 `Sync completed`。
4. 在后端查看 `APP_SYNC` 日志，确认未出现大规模 `icon resolve failed`。
5. 用 `ops/icon-sync-metrics.sql` 验收回填效果（覆盖率和空图标指标）。

## Docker 部署（推荐）

```bash
cp .env.docker.example .env.docker
docker compose --env-file .env.docker up -d --build
```

- 网关入口：`http://<server-ip-or-domain>/`
- 详细步骤见：[ops/DEPLOY_DOCKER.md](ops/DEPLOY_DOCKER.md)

## Docker 联调（前台模式）

```bash
docker compose --env-file .env.docker up --build
```

## 主要 API

- `POST /api/v1/devices/register`
- `POST /api/v1/devices/heartbeat` (device bearer)
- `POST /api/v1/events/foreground-switch` (device bearer)
- `POST /api/v1/stats/daily-snapshot` (device bearer)
- `POST /api/v1/screenshots/auth`
- `POST /api/v1/screenshots/request` (body: `deviceId`, `password`)
- `POST /api/v1/screenshots/result` (device bearer, multipart)
- `GET /api/v1/screenshots/result/:requestId` (header: `X-Screenshot-Password`)
- `GET /api/v1/dashboard/devices`
- `GET /api/v1/dashboard/devices/:deviceId/today`
- `GET /api/v1/dashboard/devices/:deviceId/history?days=7`

## WebSocket 事件（namespace 默认 `/api/v1/ws`，可配置）

- 推送到 web:
  - `device.online`
  - `device.offline`
  - `foreground.updated`
  - `stats.updated`
  - `screenshot.ready`
  - `screenshot.error`
- 下发到 device:
  - `screenshot.request`

## 安全注意

第一阶段按你的要求未实现网页登录，仅截图操作需要密码。若要公网部署，建议至少补齐：

1. HTTPS
2. Web 登录鉴权与角色控制
3. IP/频率限制与审计日志
