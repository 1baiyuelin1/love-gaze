package app.champion

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable fun Brand(compact: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
        Image(painterResource(R.drawable.love_gaze), null, Modifier.size(if (compact) 30.dp else 38.dp).clip(RoundedCornerShape(10.dp)), contentScale = ContentScale.Crop)
        Text("love gaze", fontSize = if (compact) 22.sp else 26.sp, fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold, color = Berry)
    }
}

@Composable fun PersonAvatar(owner: String, diameter: Dp = 32.dp) {
    val bitmap = photo(LocalAppearance.current.avatar(owner))
    if (bitmap != null) {
        Image(bitmap, "$owner 的头像", Modifier.size(diameter).clip(CircleShape), contentScale = ContentScale.Crop)
        return
    }
    val ink = Color(0xFF322A36)
    val berry = Berry
    Canvas(Modifier.size(diameter).clip(CircleShape).semantics { contentDescription = "$owner 的头像" }) {
        val female = owner == "champion"
        drawCircle(if (female) Color(0xFFECC9D8) else Color(0xFFD5D0E7))
        val hair = if (female) Color(0xFF5A3C4B) else Color(0xFF474052)
        drawOval(hair, Offset(size.width * .16f, size.height * .13f), Size(size.width * .68f, size.height * (if (female) .9f else .65f)))
        drawOval(Color(0xFFF1D5BF), Offset(size.width * .27f, size.height * .3f), Size(size.width * .48f, size.height * .55f))
        drawArc(hair, 180f, 180f, true, Offset(size.width * .22f, size.height * .18f), Size(size.width * .57f, size.height * .5f))
        drawCircle(ink, size.width * .026f, Offset(size.width * .42f, size.height * .58f))
        drawCircle(ink, size.width * .026f, Offset(size.width * .63f, size.height * .58f))
        drawLine(berry, Offset(size.width * .47f, size.height * .73f), Offset(size.width * .58f, size.height * .73f), size.width * .026f)
    }
}

private fun time(at: Long, pattern: String = "HH:mm") = DateTimeFormatter.ofPattern(pattern, Locale.CHINA)
    .format(Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()))

@Composable fun ChatScreen(pairing: Pairing, store: EventStore, onMap: (Moment) -> Unit, onRecover: () -> Unit, onConnect: () -> Unit, onSettings: () -> Unit) {
    val revision by store.revision.collectAsState()
    val runtime by LiveState.value.collectAsState()
    var moments by remember { mutableStateOf<List<Moment>>(emptyList()) }
    var them by remember { mutableStateOf<Moment?>(null) }
    var me by remember { mutableStateOf<Moment?>(null) }
    var readError by remember { mutableStateOf("") }
    var limit by remember { mutableIntStateOf(200) }
    var selected by remember { mutableStateOf<Moment?>(null) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val list = rememberLazyListState()
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(15_000) } }
    LaunchedEffect(revision, limit) {
        try {
            val loaded = withContext(Dispatchers.IO) { Triple(store.history(limit), store.presence(pairing.partner), store.presence(pairing.role)) }
            val follow = list.firstVisibleItemIndex == 0 && list.firstVisibleItemScrollOffset < 60
            moments = loaded.first; them = loaded.second; me = loaded.third; readError = ""
            if (follow && moments.isNotEmpty()) list.scrollToItem(0)
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { readError = "本机记录读取失败，请检查手机存储空间" }
    }
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Brand(compact = true)
                Spacer(Modifier.weight(1f))
                Surface(color = if (runtime.running) Sage else Lilac, shape = CircleShape) {
                    Text(if (!runtime.running) "○ 待恢复" else if (pairing.connected) "● 持续共享" else "● 本机记录", color = if (runtime.running) Green else Muted,
                        fontSize = 10.sp, modifier = Modifier.padding(horizontal = 7.dp, vertical = 5.dp))
                }
                IconButton(onSettings, modifier = Modifier.semantics { contentDescription = "设置" }) { GazeIcon("settings", size = 20.dp) }
            }
            Surface(color = Card, shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, Berry.copy(alpha = .07f))) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 9.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Presence(pairing.partner, them, now, Modifier.weight(1f), own = false, running = runtime.running)
                    Box(Modifier.width(1.dp).height(24.dp).background(Muted.copy(alpha = .12f)))
                    Presence(pairing.role, me, now, Modifier.weight(1f), own = true, running = runtime.running)
                }
            }
        }
        val problem = listOf(readError, runtime.collectionError, runtime.syncError).filter { it.isNotBlank() }.joinToString("\n")
        if (!pairing.connected) Row(Modifier.fillMaxWidth().background(Lilac).padding(start = 14.dp, end = 5.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text("正在本机记录，连接后同步彼此", color = Muted, fontSize = 11.sp, modifier = Modifier.weight(1f))
            TextButton(onConnect) { Text("连接配置", fontSize = 11.sp) }
        }
        if (problem.isNotBlank()) Text(problem, color = Berry, fontSize = 11.sp, lineHeight = 17.sp,
            modifier = Modifier.fillMaxWidth().background(Lilac).padding(12.dp))
        if (!runtime.running) TextButton(onRecover, Modifier.align(Alignment.CenterHorizontally)) { Text("到设置中恢复记录") }
        ChatBackdrop(Modifier.weight(1f).fillMaxWidth()) {
        if (moments.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) { PersonAvatar("white", 46.dp); PersonAvatar("champion", 46.dp) }
                    Text("等一条真实的日常", modifier = Modifier.padding(top = 22.dp), color = Ink, fontSize = 18.sp)
                    Text("打开微信等重点 App，或移动到新位置，\n日常就会出现在这里。", modifier = Modifier.padding(top = 10.dp), color = Muted, fontSize = 13.sp, lineHeight = 24.sp)
                }
            }
        } else {
            LazyColumn(Modifier.fillMaxSize(), state = list, reverseLayout = true,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item("tail") { TimelineLabel("暂时就这些，待会儿接着播") }
                itemsIndexed(moments, key = { _, m -> m.id }) { index, m ->
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (index == moments.lastIndex || time(moments[index + 1].at, "yyyy-MM-dd") != time(m.at, "yyyy-MM-dd")) {
                            TimelineLabel(time(m.at, "M 月 d 日") + " · 自动记录")
                        }
                        MomentBubble(m, m.owner == pairing.role) { selected = m }
                    }
                }
                if (moments.size >= limit) item("older") { TextButton({ limit += 200 }, Modifier.fillMaxWidth()) { Text("加载更早的动态") } }
            }
        }
        }
        val footer = if (!pairing.connected) {
            if (pairing.legacy) "本机记录已保留 · 请配置国内连接码" else "本机自动记录 · 尚未连接对方"
        } else if (runtime.lastSync > 0) "最近同步 ${time(runtime.lastSync, "HH:mm:ss")} · 正常运行约 15–30 秒可见" else "正在连接两台手机"
        Text(footer, color = Muted, fontSize = 10.sp, modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 5.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
    selected?.let { m ->
        AlertDialog(onDismissRequest = { selected = null }, title = { Text(if (m.kind == Kind.LOCATION) "这次位置更新" else "动作记录") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(Apps.sentence(m), fontSize = 17.sp, lineHeight = 27.sp)
                    Text("${m.owner} · ${time(m.at, "yyyy-MM-dd HH:mm:ss")}", color = Muted, fontSize = 12.sp)
                    if (m.packageName.isNotBlank()) Text("应用：${m.appName}\n包名：${m.packageName}", color = Muted, fontSize = 12.sp, lineHeight = 21.sp)
                    if (m.kind == Kind.LEAVE || m.kind == Kind.USAGE) Text("前台时长：${Apps.duration(m.durationMs)}", color = Muted, fontSize = 12.sp)
                    if (m.kind == Kind.LOCATION) Text("坐标：${m.latitude}, ${m.longitude}\n定位误差约 ${m.accuracy?.toInt() ?: 0} 米", color = Muted, fontSize = 12.sp, lineHeight = 21.sp)
                    Text(when (m.kind) {
                        Kind.LOCATION -> "来源：Android 系统定位。地址按系统地理编码结果显示。"
                        Kind.GAP -> "来源：采集服务恢复。中断期间的动态不作推断。"
                        else -> "来源：Android 应用使用事件。前台记录不代表软件内的具体操作。"
                    }, color = Muted, fontSize = 11.sp, lineHeight = 19.sp)
                }
            }, confirmButton = { TextButton({ selected = null }) { Text("知道了") } },
            dismissButton = { if (m.kind == Kind.LOCATION) TextButton({ onMap(m) }) { Text("在手机地图中打开") } }, containerColor = Paper)
    }
}

@Composable private fun TimelineLabel(label: String) {
    Box(Modifier.fillMaxWidth().padding(vertical = 4.dp), contentAlignment = Alignment.Center) {
        Surface(color = Paper.copy(alpha = .9f), shape = CircleShape) {
            Text(label, color = Muted, fontSize = 10.sp, modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp))
        }
    }
}

@Composable private fun Presence(owner: String, m: Moment?, now: Long, modifier: Modifier, own: Boolean, running: Boolean) {
    val label = PresenceLabels.display(m, own, running, now)
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically) {
        PersonAvatar(owner, 27.dp)
        Column(Modifier.weight(1f)) {
            Text("$owner · ${label.hint}", fontSize = 10.sp, color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(label.text, color = if (label.fresh) Green else Ink, fontSize = 12.sp, fontWeight = FontWeight.Medium,
                maxLines = 2, lineHeight = 17.sp, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable private fun MomentBubble(m: Moment, own: Boolean, onClick: () -> Unit) {
    val preset = Apps.preset(m.packageName)
    val accent = preset?.let { Color(it.color) } ?: Green
    val dark = LocalAppearance.current.theme.palette.dark
    val bubble = lerp(Card, accent, if (dark) .17f else if (own) .15f else .07f)
    val shape = if (own) RoundedCornerShape(15.dp, 3.dp, 15.dp, 15.dp) else RoundedCornerShape(3.dp, 15.dp, 15.dp, 15.dp)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (own) Arrangement.End else Arrangement.Start, verticalAlignment = Alignment.Top) {
        if (!own) { PersonAvatar(m.owner, 28.dp); Spacer(Modifier.width(6.dp)) }
        Surface(color = bubble, shape = shape, border = BorderStroke(1.dp, accent.copy(alpha = .2f)),
            modifier = Modifier.widthIn(max = 290.dp).weight(1f, fill = false)
                .clickable(onClickLabel = "查看 ${m.owner} 的动作详情", onClick = onClick)) {
            Column(Modifier.padding(horizontal = 11.dp, vertical = 8.dp).heightIn(min = 40.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    val badge = if (m.kind == Kind.LOCATION) "⌖" else preset?.mark ?: if (m.kind == Kind.LOCK) "◌" else "·"
                    Box(Modifier.size(20.dp).background(accent, RoundedCornerShape(6.dp)), contentAlignment = Alignment.Center) {
                        Text(badge, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }
                    Text(when (m.kind) {
                        Kind.OPEN, Kind.LEAVE, Kind.USAGE -> preset?.name ?: m.appName
                        Kind.LOCATION -> "位置更新"
                        Kind.LOCK -> "手机锁屏"
                        else -> "记录状态"
                    }, color = Ink, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.width(6.dp))
                    Text(time(if (m.kind == Kind.LEAVE || m.kind == Kind.USAGE) m.at - m.durationMs else m.at), color = Muted, fontSize = 10.sp)
                }
                Text(if (preset != null && m.kind in listOf(Kind.OPEN, Kind.USAGE, Kind.LEAVE))
                    (if (m.kind == Kind.OPEN) "已打开 · " else "") + preset.open
                    else if (m.kind == Kind.LOCATION) Places.display(m.place, m.latitude, m.longitude) else Apps.sentence(m),
                    color = Ink, fontSize = 14.sp, lineHeight = 20.sp)
                if (m.kind == Kind.LEAVE || m.kind == Kind.USAGE) {
                    Text("${Apps.duration(m.durationMs)} · ${if (m.kind == Kind.LEAVE) "${time(m.at)} 结束" else "截至 ${time(m.at)}"}", color = Muted, fontSize = 11.sp, lineHeight = 16.sp)
                }
                if (m.kind == Kind.LOCATION) Text("误差约 ${m.accuracy?.toInt() ?: 0} 米 · 点开查看", color = Muted, fontSize = 11.sp, lineHeight = 16.sp)
            }
        }
        if (own) { Spacer(Modifier.width(6.dp)); PersonAvatar(m.owner, 28.dp) }
        else Spacer(Modifier.width(20.dp))
    }
}
