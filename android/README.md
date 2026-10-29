# love gaze Android

Kotlin + Jetpack Compose 工程，支持 Android 10（API 29）及以上。主界面为自动动态聊天流；设置中管理权限、后台运行、通知、头像、主题、历史记录与连接。

## 服务器配置

跨设备同步依赖自行部署的 [Python 后端](../server/README.md)。仓库不包含真实服务器地址或凭据。

在本目录创建不提交到 Git 的 `local.properties`：

```properties
sdk.dir=C:/Android/Sdk
sync.origin=https://sync.example.com
```

将 SDK 路径和示例域名换成自己的值。`sync.origin` 只填写 HTTPS 根地址，不带末尾斜杠或 `/v1/sync` 路径。构建时读取该文件，生成 `BuildConfig.SYNC_ORIGIN`；缺少配置会停止构建。

仅旧 CloudBase 迁移需要可选项 `sync.legacyCloudbaseEnvironment`；留空时关闭旧环境接入。实际环境编号只保留在本机配置中。现有手机迁移时需沿用原身份凭据、安装实例和签名。

手机连接码中的 `server` 必须与构建配置完全一致。当前协议使用 `provider: "aliyun"`，可连接部署相同后端的其他云厂商服务器。没有连接配置时只记录本机动态，不会自动接入他人的服务器。

## 构建与安装

需要 JDK 17、Android SDK Platform 36 和 Build Tools 36.0.0。Gradle Wrapper 为 8.13，Android Gradle Plugin 为 8.13.2，Kotlin 为 2.2.21。先创建上面的本机配置，再执行：

```powershell
.\gradlew.bat :app:assembleWhiteDebug :app:assembleChampionDebug
```

macOS / Linux 使用 `sh ./gradlew`。两个变体的名称均为 love gaze：

| 变体 | 包名 | 构建产物 |
| --- | --- | --- |
| `white` | `app.champion.white` | `app/build/outputs/apk/white/debug/app-white-debug.apk` |
| `champion` | `app.champion.champion` | `app/build/outputs/apk/champion/debug/app-champion-debug.apk` |

两台手机各安装对应身份的 APK，再导入各自连接码。身份由构建变体固定，不能通过界面切换。覆盖升级需保持包名和签名；卸载或清除数据会丢失本机历史与安装实例信息，而后端会保留原有绑定。

`build-debug.ps1` 是可选的 Windows 本机构建助手，依赖项目根目录 `.tools` 中的 JDK 17、Android SDK 等工具。公开仓库不携带这些工具；常规开发可使用 Gradle Wrapper 或 Android Studio。

## 权限与功能

- 使用情况访问权限用于记录系统允许获取的 App 使用事件。预设重点 App 的打开、停留和离开会合并为气泡，不读取软件私有内容。
- 位置与后台位置权限用于定位。首次定位建立基准，移动满 500 米才新增气泡；权限不足或无定位时不停止 App 记录。
- 通知和系统后台设置影响前台服务与动态提醒。重点 App 通知在接收到同步记录后产生，没有独立的厂商离线推送。
- 头像在两台设备之间同步；聊天背景和主题只保存在本机。
- 历史清理只移除本机已同步记录，保留待发送内容、连接和外观；不会删除另一台手机或服务器的数据。

默认约每 15 秒收发，历史补传或分页约每 2 秒续批，失败逐步退避。持续服务不可用时依赖 Android 系统任务，可能等待超过 15 分钟，厂商也可能阻止任务运行。未采集到的位置不能补造。

## 代码入口

| 文件 | 作用 |
| --- | --- |
| `MainActivity.kt` / `SettingsScreen.kt` | 启动、设置、权限与连接入口 |
| `ChatScreen.kt` / `Appearance.kt` | 聊天流、头像、背景和主题 |
| `Pairing.kt` / `SyncClient.kt` | 连接解析、本机凭据存储和后端通信 |
| `Store.kt` / `Model.kt` | 本机数据库、队列和事件模型 |
| `UsageCollector.kt` / `LocationCollector.kt` | 系统使用事件与定位 |
| `BackgroundEngine.kt` / `BackgroundSupport.kt` / `SharingService.kt` | 采集、同步和后台恢复 |
| `SyncSchedule.kt` / `BackgroundHealth.kt` | 同步时机、退避和诊断 |
| `MovementTracker.kt` / `DeviceStatus.kt` | 位置门槛与顶部设备状态 |
| `CloraDocument.kt` | 历史协议辅助代码；当前同步路径不使用 |

上述 Kotlin 文件位于 `app/src/main/java/app/champion/`。

现有测试位于 `app/src/test/`，可按需执行 `:app:testWhiteDebugUnitTest`。旧 CloudBase 迁移测试仅在填写旧环境编号时运行；新部署的连接往返与身份校验测试不依赖旧环境。真实设备效果仍需自行验证。
