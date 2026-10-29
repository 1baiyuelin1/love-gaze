package app.champion

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private var permissionState by mutableStateOf<Access?>(null)
    private var startError by mutableStateOf("")
    private var backgroundState by mutableStateOf<BackgroundStatus?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val appearanceStore = (application as ChampionApplication).appearance
            val appearance by appearanceStore.state.collectAsState()
            SideEffect {
                androidx.core.view.WindowCompat.getInsetsController(window, window.decorView).apply {
                    isAppearanceLightStatusBars = !appearance.theme.palette.dark
                    isAppearanceLightNavigationBars = !appearance.theme.palette.dark
                }
            }
            ChampionTheme(appearance) {
                val vault = remember { PairingVault(this) }
                var vaultError by remember { mutableStateOf("") }
                var pairing by remember { mutableStateOf(try { vault.read() ?: Pairing.local() } catch (_: Exception) {
                    vaultError = "设备连接无法解锁，请检查系统锁屏设置。重装或清除数据后需要重新连接。"; null
                }) }
                var configuring by remember { mutableStateOf(false) }
                var started by remember { mutableStateOf(getSharedPreferences("onboarding", MODE_PRIVATE).getBoolean("started", false)) }
                var settings by rememberSaveable { mutableStateOf(!started || intent.getBooleanExtra("open_settings", false)) }
                val permissions = permissionState ?: access(this)
                val current = pairing
                val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { refreshAccess(); maybeStart() }
                val appSettings = { openSystem(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) }
                val actions = PermissionActions(
                    usage = { openSystem(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS, Uri.parse("package:$packageName"))) },
                    location = {
                        if (permissions.precise) appSettings()
                        else request.launch(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION))
                    },
                    background = {
                        if (Build.VERSION.SDK_INT == 29 && !permissions.background) request.launch(arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION))
                        else appSettings()
                    },
                    notifications = {
                        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
                            request.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
                        else openSystem(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
                    },
                    systemLocation = { openSystem(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) },
                    app = appSettings,
                    refreshLocation = { maybeStart(refreshLocation = true) },
                    battery = { openSystem(BackgroundSupport.batteryIntent(this)) },
                    autostart = { openAvailable(BackgroundSupport.autostartIntents(this)) },
                    partnerAlerts = {
                        PartnerNotifications.createChannel(this)
                        openSystem(Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                            .putExtra(Settings.EXTRA_APP_PACKAGE, packageName).putExtra(Settings.EXTRA_CHANNEL_ID, PartnerNotifications.CHANNEL))
                    },
                )
                Surface(Modifier.fillMaxSize(), color = Paper) {
                    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                        if (vaultError.isNotBlank()) {
                            Text(vaultError, Modifier.padding(24.dp), color = Berry)
                        } else if (configuring) {
                            BackHandler { configuring = false }
                            PairingScreen(vault, (application as ChampionApplication).store, onBack = { configuring = false }) {
                                pairing = it; configuring = false; maybeStart(reconfigure = true)
                            }
                        } else if (current == null) {
                            Text("连接配置不可用", Modifier.padding(24.dp))
                        } else if (settings) {
                            BackHandler { settings = false }
                            SettingsScreen(current, permissions, backgroundState ?: BackgroundSupport.status(this@MainActivity),
                                appearanceStore, (application as ChampionApplication).store, actions, started, startError,
                                onBack = { settings = false }, onConnect = { configuring = true }, onStart = {
                                    if (getSharedPreferences("onboarding", MODE_PRIVATE).edit().putBoolean("started", true).commit()) {
                                        started = true; maybeStart(); settings = false
                                    } else startError = "无法保存启动设置，请检查手机存储空间"
                                })
                        } else {
                            ChatScreen(current, (application as ChampionApplication).store, onConnect = { configuring = true },
                                onMap = { m ->
                                    val lat = m.latitude; val lon = m.longitude
                                    if (lat != null && lon != null) openSystem(Intent(Intent.ACTION_VIEW,
                                        Uri.parse("geo:$lat,$lon?q=$lat,$lon")))
                                }, onRecover = { refreshAccess(); settings = true }, onSettings = { refreshAccess(); settings = true })
                            if (startError.isNotBlank()) Text(startError, Modifier.padding(12.dp), color = Berry, fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume(); LiveState.foreground = true
        getSystemService(android.app.NotificationManager::class.java).cancel(PartnerNotifications.ID)
        refreshAccess(); maybeStart()
    }
    override fun onPause() { LiveState.foreground = false; super.onPause() }

    private fun refreshAccess() { permissionState = access(this); backgroundState = BackgroundSupport.status(this) }

    private fun maybeStart(reconfigure: Boolean = false, refreshLocation: Boolean = false) {
        if (!getSharedPreferences("onboarding", MODE_PRIVATE).getBoolean("started", false) || !access(this).ready) return
        try {
            startForegroundService(Intent(this, SharingService::class.java).apply {
                if (reconfigure) action = "app.champion.RECONFIGURE"
                else if (refreshLocation) action = "app.champion.REFRESH_LOCATION"
            })
            startError = ""
        } catch (_: Exception) { startError = "后台服务未启动，请检查系统权限后重新打开应用" }
    }

    private fun openSystem(intent: Intent) {
        try { startActivity(intent); startError = "" }
        catch (_: android.content.ActivityNotFoundException) { startError = "手机没有可处理此操作的应用，请前往系统设置查看" }
        catch (_: SecurityException) { startError = "系统限制了此设置入口，请在手机设置中手动查看" }
    }

    private fun openAvailable(intents: List<Intent>) {
        for (intent in intents) {
            try { startActivity(intent); startError = ""; return }
            catch (_: android.content.ActivityNotFoundException) { }
            catch (_: SecurityException) { }
        }
        startError = "请在手机设置中搜索“自启动”或“后台运行”"
    }
}

@Composable
private fun PairingScreen(vault: PairingVault, store: EventStore, onBack: () -> Unit, onPaired: (Pairing) -> Unit) {
    var code by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val preview = remember(code) { try { Pairing.parse(code) } catch (_: Exception) { null } }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(26.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Brand()
        Spacer(Modifier.height(36.dp))
        Row { PersonAvatar("white", 56.dp); Spacer(Modifier.width(12.dp)); PersonAvatar("champion", 56.dp) }
        Text("连接彼此的日常。", fontSize = 29.sp, lineHeight = 42.sp, color = Ink)
        Text("这台手机固定属于 ${BuildConfig.DEVICE_ROLE}。把对应的连接码粘贴到这里，已有的本机记录也会一起同步。", color = Muted, lineHeight = 24.sp)
        OutlinedTextField(code, { code = it; error = "" }, Modifier.fillMaxWidth(),
            label = { Text("私密连接码") }, singleLine = true, enabled = !busy,
            visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
        if (code.isNotBlank() && preview == null) Text("连接码格式或身份不匹配，本机固定为 ${BuildConfig.DEVICE_ROLE}。", color = Berry, fontSize = 12.sp)
        if (preview != null) {
            Surface(color = Lilac, shape = androidx.compose.foundation.shape.RoundedCornerShape(18.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("这台手机属于 ${preview.role}", color = Ink)
                    Text("将与 ${preview.partner} 双向连接", color = Muted, fontSize = 13.sp)
                    Text("上海服务器 · 两人专用", color = Muted, fontSize = 12.sp)
                }
            }
        }
        Text("连接后，双方各自在手机上完成系统设置，持续共享应用名称、前台使用时间和位置。运行时会显示常驻通知，可通过 Android 系统设置停止。", color = Muted, fontSize = 13.sp, lineHeight = 22.sp)
        if (error.isNotBlank()) Text(error, color = Berry, fontSize = 13.sp)
        Button(onClick = {
            val pair = preview ?: return@Button
            busy = true; error = ""
            scope.launch {
                try {
                    SyncClient(pair, vault.installation).claim()
                    store.prepareServer(pair.role)
                    vault.save(pair)
                    code = ""
                    onPaired(pair)
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) {
                    error = when (e) {
                        is java.net.UnknownHostException, is java.net.ConnectException -> "连接不上同步服务，请检查地址和网络"
                        is javax.net.ssl.SSLException -> "服务的 HTTPS 证书验证失败，请检查服务配置"
                        else -> e.message ?: "连接失败，请检查连接码和服务状态"
                    }
                } finally { busy = false }
            }
        }, enabled = preview != null && !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
            Text(if (busy) "正在连接…" else "连接并固定身份")
        }
        Text("无需注册、登录。持续服务运行时约每 15 秒收发，双方动态通常 15–30 秒可见。两台手机都需要允许持续后台运行。", color = Muted, fontSize = 12.sp, lineHeight = 20.sp)
        TextButton(onBack, enabled = !busy) { Text("返回聊天") }
    }
}

