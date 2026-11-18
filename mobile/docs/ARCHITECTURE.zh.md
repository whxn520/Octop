# Octop Mobile Chat（AGenUI）架构

Android 优先的原生聊天客户端：登录、选 Agent、线程列表、WebSocket 对话、FCM 推送；生成式 UI 通过 AGenUI（A2UI）渲染。

## 与桌面端的差异

| | Desktop (`desktop/`) | Mobile (`mobile/`) |
|--|--|--|
| 进程 | Electron 拉起本机 Python sidecar | 只连远程已运行的 Octop |
| 鉴权 | 桌面 device token（loopback） | `POST /api/auth/login` + MFA |
| UI | Dashboard WebView | 原生壳 + AGenUI |

`infra/control/mobile`（ADB 手机操控）与本客户端无关。

## 目录

```
mobile/
  docs/ARCHITECTURE.zh.md
  android/                 # Kotlin App
    app/libs/              # 放入 AGenUI AAR（可选）
    app/google-services.json  # 可选；有则启用 FCM
```

## 服务器要求

1. `bind_host` 对手机可达（不要只绑 `127.0.0.1`）。
2. 建议 TLS；调试可用 HTTP（App 已允许 cleartext）。
3. 系统推送（可选）：在 `config.json` 配置

```json
{
  "push": {
    "fcm_credentials_json": "/path/to/firebase-service-account.json"
  }
}
```

或环境变量 `OCTOP_PUSH_FCM_CREDENTIALS_PATH` / `OCTOP_PUSH_FCM_CREDENTIALS_JSON`。未配置时只走 WebSocket 前台通知，不报错。

## 鉴权

1. `POST /api/auth/login` → `access_token`，或返回 `mfa_token` 后 `POST /api/auth/mfa/verify`（TOTP）。
2. 后续请求：`Authorization: Bearer <token>`。
3. 滑动续期：读响应头 `X-Octop-Access-Token` 并覆盖本地存储。
4. **不要**使用 `/api/auth/device/*`（仅桌面壳 + loopback）。

## 对话

| 步骤 | API |
|--|--|
| Agent 列表 | `GET /api/agents`（JSON 数组） |
| 线程列表 | `GET /api/agents/{id}/threads` |
| 新建线程 | `POST /api/agents/{id}/threads` |
| 历史 | `GET /api/agents/{id}/threads/{tid}/history` |
| 实时 | `WS /api/agents/{id}/chat/ws?token=<jwt>` |

入站帧示例：

```json
{"type":"user_turn","thread_id":"...","text":"你好"}
```

出站关注：`token` / `tool_result`（含 `octop_ui`）/ `hitl_required` / `done` / `error`。

前台也可连 `WS /api/notifications/ws?token=` 收 `dashboard_push`。

## 推送

1. App 取得 FCM registration token。
2. `POST /api/push/devices`：`{ "platform":"android", "device_token":"...", "app_id":"chat.octop.mobile" }`。
3. 注销：`DELETE /api/push/devices`（同 body）。
4. 服务端在 `notify_dashboard_push` 时旁路 FCM；payload 含 `agent_id` / `thread_id`。
5. 点击通知打开 `ChatActivity`；深链：`octop://chat?agent_id=…&thread_id=…`。

## AGenUI / A2UI

Octop 服务端**不**原生产出 A2UI。App 内 `HarnessA2uiAdapter`：

| 入站 | 映射 |
|--|--|
| 连续 `token` | A2UI Text `surfaceUpdate` 流 |
| `tool_result` + `octop_ui` | Card（未知 renderer 降级为 JSON 文本） |
| `reasoning` / 普通 tool | 仅原生轻量提示 |
| `hitl_required` | 提示去网页审批 |

`AgenUiBridge`：

- 若 `app/libs/` 有 AGenUI AAR：反射调用 `SurfaceManager.beginTextStream / receiveTextChunk / endTextStream`。
- 否则 stub 日志，聊天壳仍可运行。

构建 AAR 参见 [AGenUI QuickStart](https://github.com/AGenUI/AGenUI/blob/main/docs/QuickStart.md)。

## 本地构建

需要 **JDK 17+** 与 **Android SDK**（建议用 Android Studio 打开 `mobile/android`）。

```bash
cd mobile/android
./gradlew :app:assembleDebug   # Windows: gradlew.bat :app:assembleDebug
```

若本机尚无 SDK，用 Android Studio 同步工程即可生成/补齐 wrapper 与 local.properties。

FCM：将 Firebase 的 `google-services.json` 放到 `app/`。根 `settings.gradle.kts` 已含 Google 仓库；有 json 时 app 模块会 apply `com.google.gms.google-services`（需在根 `build.gradle.kts` 增加该插件 classpath，或改用 Android Studio 的 Google Services 插件向导）。

## 验收清单

1. 远程登录 + MFA（若开启）成功，JWT 续期生效。
2. 建线程、发消息、流式显示；含 `octop_ui` 时 AGenUI（或 stub）收到 A2UI chunk。
3. 配置 FCM 后，后台收到 `dashboard_push` 系统通知并可点回线程。
4. 服务端 `make all` / 相关 pytest 绿灯。
