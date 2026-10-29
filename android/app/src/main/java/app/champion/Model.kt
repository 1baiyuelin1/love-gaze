package app.champion

import org.json.JSONObject
import java.util.UUID

enum class Kind { OPEN, USAGE, LEAVE, LOCK, LOCATION, GAP, PRESENCE }

data class Moment(
    val id: String = UUID.randomUUID().toString(),
    val owner: String,
    val at: Long = System.currentTimeMillis(),
    val kind: Kind,
    val packageName: String = "",
    val appName: String = "",
    val durationMs: Long = 0,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val accuracy: Double? = null,
    val place: String = "",
    val battery: Int = -1,
    val status: String = "",
) {
    fun json() = JSONObject().apply {
        put("v", 1); put("id", id); put("owner", owner); put("at", at)
        put("kind", kind.name); put("package", packageName); put("app", appName)
        put("duration", durationMs); put("lat", latitude); put("lon", longitude)
        put("accuracy", accuracy); put("place", place); put("battery", battery)
        put("status", status)
    }

    companion object {
        fun parse(o: JSONObject): Moment {
            require(o.getInt("v") == 1)
            val owner = o.getString("owner")
            require(owner in listOf("white", "champion"))
            val lat = if (o.has("lat")) o.getDouble("lat") else null
            val lon = if (o.has("lon")) o.getDouble("lon") else null
            require(lat == null || (lat.isFinite() && lat in -90.0..90.0))
            require(lon == null || (lon.isFinite() && lon in -180.0..180.0))
            return Moment(
                id = UUID.fromString(o.getString("id")).toString(), owner = owner,
                at = o.getLong("at"), kind = Kind.valueOf(o.getString("kind")),
                packageName = o.optString("package").take(250), appName = o.optString("app").take(100),
                durationMs = o.optLong("duration").coerceAtLeast(0), latitude = lat, longitude = lon,
                accuracy = if (o.has("accuracy")) o.getDouble("accuracy") else null,
                place = o.optString("place").take(200), battery = o.optInt("battery", -1),
                status = o.optString("status").take(100),
            )
        }
    }
}

data class Preset(val name: String, val mark: String, val color: Long, val open: String)

object Apps {
    private val entries = listOf(
        Pair(listOf("com.tencent.mm"), Preset("微信", "微", 0xFF38A76B, "微信选手上线。")),
        Pair(listOf("com.tencent.mobileqq", "com.tencent.tim"), Preset("QQ", "Q", 0xFF579DC2, "企鹅开始营业。")),
        Pair(listOf("com.sankuai.meituan", "com.sankuai.meituan.takeoutnew"), Preset("美团", "美", 0xFFE5AE32, "今天吃啥，又是个难题。")),
        Pair(listOf("com.taobao.taobao"), Preset("淘宝", "淘", 0xFFE87B50, "钱包先冷静。")),
        Pair(listOf("com.jingdong.app.mall"), Preset("京东", "京", 0xFFD65C69, "先逛逛，钱包别紧张。")),
        Pair(listOf("com.xingin.xhs"), Preset("小红书", "红", 0xFFD9667D, "种草雷达预备。")),
        Pair(listOf("com.ss.android.ugc.aweme"), Preset("抖音", "抖", 0xFF554E67, "手指准备上班。")),
        Pair(listOf("com.phoenix.read"), Preset("红果", "果", 0xFFD46B5F, "短剧选手就位。")),
        Pair(listOf("com.netease.cloudmusic"), Preset("网易云音乐", "云", 0xFFCB6672, "耳朵准备上班。")),
        Pair(listOf("com.android.browser", "com.android.chrome", "com.sec.android.app.sbrowser", "com.vivo.browser", "com.mi.globalbrowser", "com.heytap.browser"), Preset("浏览器", "览", 0xFF699AAC, "准备查点东西。")),
        Pair(listOf("tv.danmaku.bili", "com.bilibili.app.in"), Preset("哔哩哔哩", "哔", 0xFFD482AB, "B 站选手上线。")),
        Pair(listOf("com.eg.android.AlipayGphone"), Preset("支付宝", "支", 0xFF629DC8, "钱包进入工作状态。")),
        Pair(listOf("com.autonavi.minimap"), Preset("高德地图", "高", 0xFF7995CB, "方向感外包一下。")),
        Pair(listOf("com.deepseek.chat"), Preset("DeepSeek", "D", 0xFF6E86C7, "外脑准备上班。")),
        Pair(listOf("com.larus.nova"), Preset("豆包", "豆", 0xFF72A6AD, "AI 搭子已就位。")),
        Pair(listOf("com.zhihu.android"), Preset("知乎", "知", 0xFF6D99CC, "知识区选手上线。")),
        Pair(listOf("com.quark.browser"), Preset("夸克浏览器", "夸", 0xFF8887C6, "问题先排队。")),
        Pair(listOf("com.android.camera", "com.android.camera2", "com.sec.android.app.camera", "com.oppo.camera", "com.huawei.camera", "com.vivo.camera", "com.google.android.GoogleCamera"), Preset("相机", "◎", 0xFF84958E, "镜头准备营业。")),
        Pair(listOf("com.cainiao.wireless"), Preset("菜鸟驿站", "菜", 0xFF6CACC1, "快递雷达预备。")),
        Pair(listOf("com.baidu.netdisk"), Preset("百度网盘", "盘", 0xFF75A2CC, "云端仓库开门。")),
    )
    private val byPackage = entries.flatMap { (packages, preset) -> packages.map { it to preset } }.toMap()
    fun preset(packageName: String): Preset? = byPackage[packageName]
    fun important(packageName: String) = packageName in byPackage
    val presets: List<Preset> get() = entries.map { it.second }

    fun sentence(m: Moment): String = when (m.kind) {
        Kind.OPEN -> preset(m.packageName)?.let { "打开了${m.appName}，${it.open}" } ?: "打开应用：${m.appName}"
        Kind.LEAVE -> "离开了${m.appName}，本次前台使用 ${duration(m.durationMs)}。"
        Kind.USAGE -> "前台持续使用：${m.appName} · ${duration(m.durationMs)}"
        Kind.LOCK -> "手机锁屏了，屏幕歇一会儿。"
        Kind.LOCATION -> "位置更新：${Places.display(m.place, m.latitude, m.longitude)}"
        Kind.GAP -> "记录中断后已恢复。中断期间的动作和用时没有补记。"
        Kind.PRESENCE -> m.status
    }

    fun duration(ms: Long): String {
        val seconds = ms / 1000
        return if (seconds < 60) "${seconds}秒" else if (seconds < 3600) "${seconds / 60}分${seconds % 60}秒" else "${seconds / 3600}小时${seconds % 3600 / 60}分"
    }
}
