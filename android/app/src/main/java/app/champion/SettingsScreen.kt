package app.champion

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

data class PermissionActions(
    val usage: () -> Unit, val location: () -> Unit, val background: () -> Unit,
    val notifications: () -> Unit, val systemLocation: () -> Unit, val app: () -> Unit,
    val refreshLocation: () -> Unit, val battery: () -> Unit, val autostart: () -> Unit,
    val partnerAlerts: () -> Unit,
)

@Composable fun SettingsScreen(
    pairing: Pairing, access: Access, background: BackgroundStatus, appearanceStore: AppearanceStore,
    store: EventStore, actions: PermissionActions, started: Boolean, startError: String,
    onStart: () -> Unit, onBack: () -> Unit, onConnect: () -> Unit,
) {
    val appearance = LocalAppearance.current
    val runtime by LiveState.value.collectAsState()
    val context = LocalContext.current
    var showBackgroundLog by remember { mutableStateOf(false) }
    var page by rememberSaveable { mutableStateOf(if (started) "home" else "permissions") }
    var imageTarget by rememberSaveable { mutableStateOf("white") }
    var editingAvatar by rememberSaveable { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var notice by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val scroll = rememberScrollState()
    LaunchedEffect(page) { scroll.scrollTo(0) }
    fun save(action: () -> Unit) {
        try { action(); error = ""; notice = "已保存" }
        catch (_: Exception) { error = "设置未能保存，请检查手机存储空间" }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            val target = imageTarget
            busy = true; error = ""; notice = "正在保存图片…"
            scope.launch {
                try { appearanceStore.image(target, uri); notice = if (target == "background") "背景已保存到这台手机" else "头像已保存，将随下一次同步发给对方" }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { error = "图片未能保存，请换一张照片或检查存储空间"; notice = "" }
                finally { busy = false }
            }
        }
    }
    fun choose(target: String) {
        imageTarget = target
        try { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
        catch (_: android.content.ActivityNotFoundException) { error = "手机没有可用的照片选择器" }
    }
    fun back() { if (page == "home") onBack() else { page = "home"; notice = ""; error = "" } }
    BackHandler { back() }
    val title = when (page) {
        "appearance" -> "聊天装扮"
        "background" -> "后台运行"
        "permissions" -> "权限与定位"
        "history" -> "历史记录"
        "alerts" -> "重点动态通知"
        else -> "设置"
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton({ back() }, contentPadding = PaddingValues(horizontal = 8.dp)) { GazeIcon("back", size = 20.dp); Text("返回", fontSize = 13.sp) }
            Text(title, color = Ink, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            PersonAvatar(pairing.role, 27.dp)
            Spacer(Modifier.width(10.dp))
        }
        Column(Modifier.weight(1f).verticalScroll(scroll).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            when (page) {
                "home" -> {
                    Surface(color = Lilac, shape = RoundedCornerShape(26.dp)) {
                        Column(Modifier.fillMaxWidth().padding(22.dp), verticalArrangement = Arrangement.spacedBy(13.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                PersonAvatar("white", 52.dp); Spacer(Modifier.width(8.dp)); GazeIcon("connection", size = 24.dp)
                                Spacer(Modifier.width(8.dp)); PersonAvatar("champion", 52.dp)
                            }
                            Text("white & champion", fontFamily = FontFamily.Serif, fontSize = 24.sp, color = Ink)
                            Text("两个人的日常，按喜欢的样子来。", color = Muted, fontSize = 12.sp)
                        }
                    }
                    MenuEntry("appearance", "聊天装扮", "${appearance.theme.label}主题 · 头像与照片背景") { page = "appearance" }
                    MenuEntry("background", "后台运行", if (runtime.running) "记录运行中 · 查看省电与自启动" else "恢复自动记录与同步") { page = "background" }
                    MenuEntry("connection", "重点动态通知", "微信 · 小红书 · 淘宝 · 抖音") { page = "alerts" }
                    MenuEntry("location", "权限与定位", if (access.precise) "精确位置已开启 · 查看实际定位" else "检查权限与定位状态") { page = "permissions" }
                    MenuEntry("history", "历史记录", "手动清理旧记录，继续自动记录") { page = "history" }
                    MenuEntry("connection", "两台手机的连接", if (pairing.connected) "${pairing.role} ↔ ${pairing.partner} · 已配置" else "连接另一台手机") { onConnect() }
                    Text("love gaze  ·  ${BuildConfig.VERSION_NAME}", color = Muted, fontSize = 11.sp, modifier = Modifier.align(Alignment.CenterHorizontally).padding(12.dp))
                }
                "appearance" -> {
                    PageIntro("看着顺眼，用着舒服。", "头像同步给对方，主题和背景随自己喜欢。")
                    var fade by remember(appearance.backgroundFade) { mutableFloatStateOf(appearance.backgroundFade) }
                    CompositionLocalProvider(LocalAppearance provides appearance.copy(backgroundFade = fade)) {
                        ChatPreview(pairing)
                    }
                    SectionTitle("主题", "点选即可预览并保存")
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        GazeTheme.entries.forEach { theme ->
                            ThemeTile(theme, appearance.theme == theme) { save { appearanceStore.theme(theme) } }
                        }
                    }
                    SectionTitle("我们的头像", if (!pairing.connected) "连接后，你的头像会同步给对方" else if (appearance.avatarPending) "你的新头像等待同步给对方" else "你的头像随动态同步；对方收到后自动更新")
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        for (owner in listOf("white", "champion")) {
                            Surface(onClick = { editingAvatar = owner }, enabled = !busy, color = Card, shape = RoundedCornerShape(20.dp), modifier = Modifier.weight(1f)) {
                                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    PersonAvatar(owner, 39.dp)
                                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Text(owner, color = Ink, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                                        Text(if (owner == pairing.role) "更换我的头像" else "对方的头像", color = Berry, fontSize = 10.sp)
                                    }
                                }
                            }
                        }
                    }
                    SectionTitle("聊天背景", if (appearance.background.isBlank()) "正在使用主题背景" else "正在使用自选照片")
                    SettingCard {
                        Button({ choose("background") }, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                            Text(if (appearance.background.isBlank()) "从相册选一张" else "更换背景照片")
                        }
                        if (appearance.background.isNotBlank()) {
                            Text("背景柔化  ${(fade * 100).toInt()}%", fontSize = 12.sp, color = Muted, modifier = Modifier.padding(top = 10.dp))
                            Slider(fade, { fade = it }, valueRange = 0f.. .8f, onValueChangeFinished = { save { appearanceStore.fade(fade) } })
                            TextButton({ save { appearanceStore.clear("background") } }, enabled = !busy) { Text("恢复主题背景") }
                        }
                    }
                }
                "background" -> {
                    PageIntro("关掉页面，日常继续。", "${Build.MANUFACTURER} ${Build.MODEL} · 后台运行设置")
                    SettingCard(color = Sage) {
                        Text(if (runtime.running) "自动记录正在运行" else "自动记录等待恢复", color = Green, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                        if (runtime.lastCollection > 0) Text("最近读取 App 动态：${clockTime(runtime.lastCollection)}", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 10.dp))
                        Text(if (runtime.lastSync > 0) "最近同步：${clockTime(runtime.lastSync)}" else "等待下一次同步", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
                        Text("持续服务运行时，双方动态通常约 15–30 秒可见。服务被结束后，系统任务在获准执行时补记与收发，等待仍可能超过 15 分钟。", color = Muted, fontSize = 11.sp, lineHeight = 19.sp, modifier = Modifier.padding(top = 10.dp))
                    }
                    SettingCard {
                        PermissionRow("省电限制", if (background.batteryUnrestricted) "系统已允许忽略电池优化" else "允许后台持续记录与网络同步", background.batteryUnrestricted && !background.systemRestricted, actions.battery, last = true)
                    }
                    MenuEntry("background", "自启动与后台耗电", "打开手机设置，允许 love gaze 后台运行") { actions.autostart() }
                    SettingCard {
                        Text(if (Build.MANUFACTURER.lowercase() in listOf("vivo", "iqoo")) "vivo 后台设置" else if (Build.MANUFACTURER.lowercase() in listOf("xiaomi", "redmi")) "小米后台设置" else "手机后台设置", color = Ink, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                        val steps = if (Build.MANUFACTURER.lowercase() in listOf("vivo", "iqoo"))
                            "开启自启动；在电池设置的后台耗电管理中允许后台高耗电。\n在最近任务里锁定 love gaze，可避免一键清理误关。"
                        else "开启后台自启动；将 love gaze 的省电策略设为“无限制”。\n在最近任务里锁定 love gaze，可避免一键清理误关。"
                        Text(steps, color = Muted, fontSize = 12.sp, lineHeight = 23.sp, modifier = Modifier.padding(top = 10.dp))
                        Text("自启动与后台锁定的开关需在系统中确认；这里不会把未检测到的状态标成已完成。", color = Muted, fontSize = 11.sp, lineHeight = 19.sp, modifier = Modifier.padding(top = 10.dp))
                    }
                    Text(if (background.recoveryScheduled) "已安排系统补记与收发；即使持续服务暂时无法重启，任务获准运行时仍会直接同步。" else "开始记录后会安排系统补记与收发。", color = Muted, fontSize = 12.sp, lineHeight = 21.sp)
                    if (runtime.backgroundMessage.isNotBlank()) Text(runtime.backgroundMessage, color = Berry, fontSize = 12.sp)
                    SettingCard {
                        Text("后台运行记录", color = Ink, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                        Text("保留最近的移除、进程结束、系统任务与收发结果，重新打开后仍可查看。", color = Muted, fontSize = 12.sp, lineHeight = 21.sp, modifier = Modifier.padding(top = 8.dp))
                        if (showBackgroundLog) Text(runtime.backgroundLog.ifBlank { "暂时没有后台运行记录" }, color = Muted, fontSize = 11.sp, lineHeight = 21.sp, modifier = Modifier.padding(top = 10.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            TextButton({ showBackgroundLog = !showBackgroundLog }, modifier = Modifier.heightIn(min = 48.dp)) { Text(if (showBackgroundLog) "收起记录" else "查看记录") }
                            TextButton({
                                val report = "love gaze ${BuildConfig.VERSION_NAME} · ${pairing.role}\n${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE}\n" +
                                    "使用记录权限=${access.usage}，通知权限=${access.notifications}，后台位置=${access.locationReady}\n" +
                                    "忽略电池优化=${background.batteryUnrestricted}，系统后台限制=${background.systemRestricted}，任务已安排=${background.recoveryScheduled}\n" +
                                    "待机分类=${background.standbyLabel}（${background.standbyBucket}），同步服务=阿里云\n" +
                                    "最近读取=${runtime.lastCollection}，最近同步=${runtime.lastSync}\n${runtime.backgroundLog}"
                                context.getSystemService(android.content.ClipboardManager::class.java)
                                    ?.setPrimaryClip(android.content.ClipData.newPlainText("love gaze 后台运行记录", report))
                                notice = "后台运行记录已复制"
                            }, modifier = Modifier.heightIn(min = 48.dp)) { Text("复制记录") }
                        }
                    }
                    Text("系统“强行停止”会阻止应用继续运行，需要重新打开。位置中断期间无法补回移动路线。", color = Muted, fontSize = 11.sp, lineHeight = 19.sp)
                    if (!runtime.running) Button(onStart, enabled = access.ready, modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp)) { Text("恢复自动记录") }
                    TextButton({ page = "permissions" }) { Text("检查应用权限") }
                }
                "permissions" -> {
                    PageIntro("权限放在一起，更好找。", "位置暂不可用时，App 记录与同步仍会继续。")
                    SettingCard {
                        PermissionRow("应用使用情况", "记录重点 App 的前台使用", access.usage, actions.usage)
                        PermissionRow("精确位置", if (access.precise) "已允许获取精确坐标" else if (access.location) "目前仅允许大致位置" else "未开启位置权限", access.precise, actions.location)
                        PermissionRow("后台位置", "位置权限中选择“始终允许”", access.background, actions.background)
                        PermissionRow("运行通知", "显示持续记录与同步状态", access.notifications, actions.notifications)
                        PermissionRow("手机定位服务", "手机的定位总开关", access.locationEnabled, actions.systemLocation, last = true)
                    }
                    SettingCard(color = Sage) {
                        Text("本机定位", color = Green, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                        Text(runtime.locationPlace, color = Ink, fontSize = 15.sp, lineHeight = 24.sp, modifier = Modifier.padding(top = 12.dp))
                        Text(runtime.locationAccuracy?.let { "实际误差约 ${it.toInt()} 米" } ?: "等待新坐标", color = Green, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
                        if (runtime.locationAt > 0) Text("采集于 ${clockTime(runtime.locationAt)}", color = Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp))
                        Text(runtime.locationMessage.ifBlank { "精确权限、定位误差和详细地址是不同状态。" }, color = Muted, fontSize = 12.sp, lineHeight = 20.sp, modifier = Modifier.padding(top = 10.dp))
                        TextButton(actions.refreshLocation, enabled = started && access.locationReady && access.ready) { Text("重新定位") }
                        Text("相对上次位置记录移动满 500 米才新增气泡。首次定位只建立基准；重新打开或刷新定位不会重复发消息。",
                            color = Muted, fontSize = 11.sp, lineHeight = 19.sp)
                    }
                    TextButton(actions.app) { Text("查看全部系统权限") }
                    if (!started || !runtime.running) Button(onStart, enabled = access.ready, modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp)) { Text(if (started) "恢复自动记录" else "开始自动记录") }
                    Text("重点动态：${Apps.presets.joinToString("、") { it.name }}。桌面和快速切换会被过滤。", color = Muted, fontSize = 11.sp, lineHeight = 20.sp)
                }
                "history" -> HistorySettings(store)
                "alerts" -> {
                    PageIntro("有新动态，提醒一下。", "${pairing.partner} 打开重点 App，收到同步后通知你。")
                    SettingCard {
                        PermissionRow("重点动态通知", if (access.notifications && access.partnerAlerts) "已开启 · 弹出方式由手机系统控制" else "通知已关闭，点此到系统中开启",
                            access.notifications && access.partnerAlerts, if (access.notifications) actions.partnerAlerts else actions.notifications, last = true)
                    }
                    SettingCard {
                        Text("微信 · 小红书 · 淘宝 · 抖音", color = Ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        Text("双方都有提醒。通知标明打开 App 的时间，点一下就回到日常。正在查看 love gaze 时不重复弹出。", color = Muted, fontSize = 12.sp, lineHeight = 22.sp, modifier = Modifier.padding(top = 10.dp))
                    }
                    Text("持续服务运行时通常约 15–30 秒收到。一次收到多款 App 的动态会合并提醒；超过 30 分钟的补记只进入聊天，不补弹旧通知。", color = Muted, fontSize = 12.sp, lineHeight = 22.sp)
                    Text("在系统通知设置中允许悬浮通知或横幅，并检查勿扰模式。应用被系统强行停止时，需重新打开后才能继续接收。", color = Muted, fontSize = 12.sp, lineHeight = 22.sp)
                    TextButton({ page = "background" }) { Text("检查后台运行设置") }
                }
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Berry)
            if (notice.isNotBlank()) Text(notice, color = Green, fontSize = 12.sp)
            if (error.isNotBlank()) Text(error, color = Berry, fontSize = 12.sp)
            if (startError.isNotBlank()) Text(startError, color = Berry, fontSize = 12.sp)
            Spacer(Modifier.height(14.dp))
        }
    }
    editingAvatar?.let { owner ->
        AlertDialog(onDismissRequest = { editingAvatar = null }, title = { Text("$owner 的头像") },
            text = { Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                PersonAvatar(owner, 96.dp)
                Text(if (owner == pairing.role) "更换或恢复默认后，会同步给对方" else "由 $owner 在自己的手机上设置并同步", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 16.dp))
            } },
            confirmButton = { TextButton({ editingAvatar = null; if (owner == pairing.role) choose(owner) }) { Text(if (owner == pairing.role) "从相册选择" else "知道了") } },
            dismissButton = { if (owner == pairing.role) TextButton({ save { appearanceStore.clear(owner) }; editingAvatar = null }) { Text("恢复默认") } })
    }
}

@Composable private fun PageIntro(title: String, subtitle: String) {
    Column(Modifier.padding(top = 12.dp, bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
        Text(title, fontSize = 24.sp, lineHeight = 34.sp, fontWeight = FontWeight.SemiBold, color = Ink)
        Text(subtitle, color = Muted, fontSize = 12.sp, lineHeight = 21.sp)
    }
}

@Composable private fun SectionTitle(title: String, note: String) {
    Column(verticalArrangement = Arrangement.spacedBy(5.dp), modifier = Modifier.padding(top = 6.dp)) {
        Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Ink)
        Text(note, fontSize = 11.sp, color = Muted)
    }
}

@Composable private fun MenuEntry(icon: String, title: String, detail: String, action: () -> Unit) {
    Surface(onClick = action, color = Card, shape = RoundedCornerShape(20.dp)) {
        Row(Modifier.fillMaxWidth().padding(17.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(13.dp)) {
            Box(Modifier.size(42.dp).background(Lilac.copy(alpha = .6f), RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) { GazeIcon(icon) }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(title, color = Ink, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                Text(detail, color = Muted, fontSize = 11.sp, lineHeight = 18.sp)
            }
            GazeIcon("next", Muted, 17.dp)
        }
    }
}

@Composable private fun SettingCard(color: androidx.compose.ui.graphics.Color = Card, content: @Composable ColumnScope.() -> Unit) {
    Surface(color = color, shape = RoundedCornerShape(22.dp)) { Column(Modifier.fillMaxWidth().padding(18.dp), content = content) }
}

@Composable private fun PermissionRow(title: String, detail: String, granted: Boolean, onClick: () -> Unit, last: Boolean = false) {
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(title, fontSize = 14.sp, color = Ink)
            Text(detail, fontSize = 11.sp, color = Muted, lineHeight = 18.sp)
            Text(if (granted) "已开启" else "待设置", color = if (granted) Green else Berry, fontSize = 10.sp)
        }
        TextButton(onClick) { Text(if (granted) "管理" else "设置", fontSize = 12.sp) }
    }
    if (!last) HorizontalDivider(color = Muted.copy(alpha = .12f))
}

@Composable private fun ChatPreview(pairing: Pairing) {
    Surface(shape = RoundedCornerShape(24.dp), border = BorderStroke(1.dp, Berry.copy(alpha = .12f))) {
        ChatBackdrop(Modifier.fillMaxWidth().heightIn(min = 246.dp)) {
            Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(15.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("love gaze", fontFamily = FontFamily.Serif, fontSize = 19.sp, fontWeight = FontWeight.Bold, color = Berry)
                    Surface(color = Card, shape = CircleShape) { Text("装扮预览", Modifier.padding(horizontal = 8.dp, vertical = 5.dp), fontSize = 10.sp, color = Muted) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PersonAvatar(pairing.partner, 30.dp)
                    Surface(color = androidx.compose.ui.graphics.lerp(Card, androidx.compose.ui.graphics.Color(0xFF38A76B), .10f), shape = RoundedCornerShape(4.dp, 17.dp, 17.dp, 17.dp)) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Text("微信", color = Green, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                            Text("微信选手上线。", color = Ink, fontSize = 13.sp)
                        }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    Surface(color = Lilac, shape = RoundedCornerShape(17.dp, 4.dp, 17.dp, 17.dp)) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Text("美团", color = Berry, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                            Text("今天吃啥，又是个难题。", color = Ink, fontSize = 13.sp)
                        }
                    }
                    Spacer(Modifier.width(8.dp)); PersonAvatar(pairing.role, 30.dp)
                }
            }
        }
    }
}

@Composable private fun ThemeTile(theme: GazeTheme, selected: Boolean, onClick: () -> Unit) {
    val p = theme.palette
    Surface(shape = RoundedCornerShape(20.dp), color = p.paper,
        border = BorderStroke(if (selected) 2.dp else 1.dp, if (selected) p.accent else p.muted.copy(alpha = .18f)),
        modifier = Modifier.width(126.dp).selectable(selected, role = Role.RadioButton, onClick = onClick)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(Modifier.fillMaxWidth().height(65.dp).background(Brush.verticalGradient(listOf(p.paper, p.chat)), RoundedCornerShape(12.dp)).padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.width(46.dp).height(16.dp).background(p.card, RoundedCornerShape(7.dp)))
                Box(Modifier.width(55.dp).height(16.dp).align(Alignment.End).background(p.self, RoundedCornerShape(7.dp)))
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(theme.label, color = p.ink, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                if (selected) GazeIcon("check", p.accent, 17.dp)
            }
            Text(theme.note, color = p.muted, fontSize = 10.sp)
        }
    }
}

@Composable private fun HistorySettings(store: EventStore) {
    var days by rememberSaveable { mutableIntStateOf(7) }
    val cutoff = remember(days) { if (days == 0) System.currentTimeMillis() else System.currentTimeMillis() - days * 86_400_000L }
    val revision by store.revision.collectAsState()
    var count by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    LaunchedEffect(cutoff, revision) {
        try { loading = true; count = withContext(Dispatchers.IO) { store.historyCount(cutoff) }; error = "" }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { error = "暂时无法读取历史记录数量" }
        finally { loading = false }
    }
    PageIntro("旧记录，想清再清。", "清理时自动记录和同步继续运行。")
    SettingCard {
        Text("选择清理范围", color = Ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        for ((value, label) in listOf(7 to "7 天前", 30 to "30 天前", 0 to "当前全部已同步记录")) {
            Row(Modifier.fillMaxWidth().heightIn(min = 54.dp).selectable(days == value, enabled = !busy, role = Role.RadioButton, onClick = { days = value }), verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = days == value, onClick = null)
                Spacer(Modifier.width(10.dp)); Text(label, color = Ink, fontSize = 14.sp)
            }
        }
    }
    SettingCard(color = Lilac) {
        Text(if (loading) "正在统计…" else "$count 条可清理记录", color = Ink, fontSize = 21.sp, fontWeight = FontWeight.SemiBold)
        Text("仅清理这台手机的已同步历史。待发送的动态、头像、背景和连接设置会保留，对方手机和云端记录不受影响。", color = Muted, fontSize = 12.sp, lineHeight = 22.sp, modifier = Modifier.padding(top = 12.dp))
    }
    Button({ confirm = true }, enabled = !busy && !loading && count > 0 && error.isBlank(), modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp)) { Text(if (busy) "正在清理…" else "清理旧记录") }
    if (notice.isNotBlank()) Text(notice, color = Green, fontSize = 12.sp, lineHeight = 21.sp)
    if (error.isNotBlank()) Text(error, color = Berry, fontSize = 12.sp)
    if (confirm) AlertDialog(onDismissRequest = { confirm = false }, title = { Text("清理这台手机的旧记录？") },
        text = { Text("将清理${if (days == 0) "当前全部" else "${days} 天前"}已同步记录（目前 $count 条）。清理后无法在本机恢复，自动记录会继续。") },
        confirmButton = { TextButton({
            confirm = false; busy = true
            scope.launch {
                try {
                    val result = withContext(Dispatchers.IO) { store.clearHistory(cutoff) }
                    notice = "已清理 ${result.count} 条记录。" + if (result.compacted) "存储空间已整理，自动记录继续。" else "空闲空间可供后续记录复用。"
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { error = "清理未能完成，请稍后重试" }
                finally { busy = false }
            }
        }) { Text("确认清理") } }, dismissButton = { TextButton({ confirm = false }) { Text("取消") } })
}

private fun clockTime(at: Long) = DateTimeFormatter.ofPattern("M/d HH:mm:ss").format(Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()))
