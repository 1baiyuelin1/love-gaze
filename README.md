# love gaze

面向固定两台 Android 手机的日常动态共享应用，使用 Kotlin + Jetpack Compose 开发。双方在各自设备授权后，可共享系统能够确认的 App 使用动态、位置变化和设备状态，以聊天流形式查看。

## 项目初衷

有些时候，情侣之间可能也需要一点隐私。

这是我将 love gaze 公开的初衷。分享日常应当建立在双方知情、自愿和信任的基础上，也应尊重彼此保留个人空间的需要。希望这份源码能帮助使用者了解动态共享如何运作，并自行决定这种方式是否适合自己的关系。

**本项目需要自行部署和配置后端服务器。公开仓库只提供源码和通用接入说明，不提供可直接使用的服务器、账号、连接码或托管服务。仅下载源码或打开网页原型，不能实现两台真实手机之间的同步。**

## 功能与边界

- 两个固定身份：`white` 与 `champion`，分别构建对应的安装包；没有注册、成员管理或手动聊天输入框。
- 记录预设 App 的打开、前台停留和离开，并合并展示；不读取聊天正文，也不根据打开软件推断发消息、下单等内部行为。
- 位置相对上次有效记录移动满 500 米时新增气泡；顶部展示设备状态和更新时间。
- 支持重点 App 动态通知、头像同步、本机主题背景、断线补传和手动清理本机历史。
- 持续服务与网络正常时，默认约每 15 秒收发；Android 后台限制、网络和厂商省电策略会影响实际延迟，不能保证始终在线。

当前代码版本为 0.6.0，支持 Android 10（API 29）及以上。两台设备均需由使用者自行开启相关系统权限。

## 运行结构

```text
white Android 客户端 ── HTTPS ──┐
                               Python API + SQLite
champion Android 客户端 ─ HTTPS ┘
```

后端提供 `GET /health` 与 `POST /v1/sync`，处理两份固定身份凭据、安装实例绑定、活动收发与头像同步。源码中的 `aliyun` 是当前连接协议的标识，不限制服务器供应商。

## 必须自行配置的内容

1. 准备能够被两台手机访问的服务器，部署 [Python 后端](server/README.md)，配置有效的 HTTPS 入口和持久化数据库。
2. 为 `white` 和 `champion` 分别生成独立凭据。服务器保存凭据的 SHA-256 摘要，明文凭据放入各自手机的连接码。
3. 在本机创建 `android/local.properties`，填写 SDK 路径与服务器根地址。例如：

   ```properties
   sdk.dir=C:/Android/Sdk
   sync.origin=https://sync.example.com
   ```

   `sync.origin` 必须是 HTTPS 根地址，不带路径、末尾斜杠、用户名或查询参数。示例域名只是占位符，需替换为自己的服务地址。缺少该配置时，Gradle 会提示配置错误。
4. 构建两个身份的 APK，在两台手机分别安装，并在“设置 → 两台手机的连接”中导入各自连接码。连接码中的服务器地址必须与构建配置完全一致。

仅从旧 CloudBase 实例迁移时，可在同一本机文件中填写 `sync.legacyCloudbaseEnvironment=<自己的旧环境编号>`。新部署无需填写；留空时不接受旧 CloudBase 连接码。迁移者还需在新后端配置原身份凭据的摘要，不能直接更换凭据后期待旧连接继续有效。

配置文件格式、连接码生成和后端启动步骤见 [服务器说明](server/README.md)。Android 构建与权限说明见 [Android 说明](android/README.md)。

## 构建 Android

需要 JDK 17、Android SDK Platform 36 和 Build Tools 36.0.0。先完成上述本机配置，再在 `android/` 目录执行：

```powershell
.\gradlew.bat :app:assembleWhiteDebug :app:assembleChampionDebug
```

macOS / Linux 使用 `sh ./gradlew` 执行相同任务。产物分别位于：

- `android/app/build/outputs/apk/white/debug/app-white-debug.apk`
- `android/app/build/outputs/apk/champion/debug/app-champion-debug.apk`

仓库不包含现成 APK 或历史安装 ZIP。构建产物会包含你配置的服务器地址，分发安装包前需自行确认接入目标。覆盖安装既有应用还需要保持包名及签名一致。

## 网页原型

根目录 HTML/CSS/JavaScript 是早期交互原型，使用浏览器本地存储和模拟事件，不连接真实后端、不采集手机动态。

在项目根目录运行：

```powershell
python -m http.server 4173 --bind 127.0.0.1
```

打开 `http://127.0.0.1:4173/` 和 `http://127.0.0.1:4173/champion.html`，可在同一浏览器中体验双页同步。原型中的软件内部动作属于演示内容，不代表 Android 已实现对应采集能力。

## 仓库内容

| 目录或文件 | 用途 |
| --- | --- |
| `android/` | Android 客户端、Gradle 工程和现有单元测试 |
| `server/sync_app.py` | 当前 Python WSGI 同步后端 |
| `server/test_sync_app.py` | 使用临时数据的后端协议测试 |
| `server/requirements.txt` | 后端运行依赖 |
| `index.html` / `champion.html` | 两个固定身份的网页原型 |
| `app.js` / `app-actions.js` / CSS | 原型逻辑、模拟动作和界面样式 |
| `previews/` | 网页原型截图 |

真实服务器地址与部署文件、私人服务器交接文档、历史云服务部署记录、密钥、设备连接码、数据库、运行日志、SDK、构建缓存和安装包均不随公开仓库提交。相关本机路径由 `.gitignore` 排除；`android/local.properties` 也不会提交。

## 验证范围

仓库保留现有 Android 和后端测试。测试使用模拟或临时数据，不代表两台真实手机的后台常驻、跨网络连接和定位效果已经验证。自行部署后，应在双方明确授权的设备上验证配对、收发、后台恢复与位置更新。

