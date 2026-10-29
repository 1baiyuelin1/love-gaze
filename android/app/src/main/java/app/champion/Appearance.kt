package app.champion

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.util.LruCache
import android.util.Base64
import androidx.compose.foundation.Image
import androidx.compose.foundation.Canvas
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.ByteArrayOutputStream
import java.util.UUID
import org.json.JSONObject

data class GazePalette(
    val paper: Color, val ink: Color, val muted: Color, val accent: Color,
    val self: Color, val card: Color, val chat: Color, val dark: Boolean = false,
)

enum class GazeTheme(val label: String, val note: String, val palette: GazePalette) {
    ROSE("莓果", "温柔一点", GazePalette(Color(0xFFF8F5F7), Color(0xFF322A36), Color(0xFF796E7D), Color(0xFF8B4265), Color(0xFFE9E0EF), Color.White, Color(0xFFF3EEF3))),
    MATCHA("抹茶", "清清爽爽", GazePalette(Color(0xFFF5F7F1), Color(0xFF29372F), Color(0xFF657269), Color(0xFF47684D), Color(0xFFDCE9D9), Color(0xFFFEFFFB), Color(0xFFEBF0E8))),
    APRICOT("奶杏", "暖乎乎的", GazePalette(Color(0xFFFCF6ED), Color(0xFF41342C), Color(0xFF7A6C60), Color(0xFF9C603E), Color(0xFFF2E0C9), Color(0xFFFFFCF7), Color(0xFFF4ECDF))),
    NIGHT("晚安", "夜里不刺眼", GazePalette(Color(0xFF211F29), Color(0xFFEFE7F2), Color(0xFFBBB0C3), Color(0xFFE7A9C7), Color(0xFF443347), Color(0xFF302B38), Color(0xFF1B1922), true)),
}

data class Appearance(
    val theme: GazeTheme = GazeTheme.ROSE,
    val whiteAvatar: String = "", val championAvatar: String = "", val background: String = "",
    val backgroundFade: Float = .32f,
    val avatarPending: Boolean = false,
) {
    fun avatar(owner: String) = if (owner == "white") whiteAvatar else championAvatar
}

/** Own avatar is shared; wallpaper and theme remain local. */
class AppearanceStore(private val context: Context) {
    private val prefs = context.getSharedPreferences("appearance", Context.MODE_PRIVATE)
    val state = MutableStateFlow(read())

    private fun read() = Appearance(
        theme = GazeTheme.entries.find { it.name == prefs.getString("theme", "") } ?: GazeTheme.ROSE,
        whiteAvatar = prefs.getString("white", "").orEmpty(),
        championAvatar = prefs.getString("champion", "").orEmpty(),
        background = prefs.getString("background", "").orEmpty(),
        backgroundFade = prefs.getFloat("fade", .32f).coerceIn(0f, .8f),
        avatarPending = prefs.getString("ownRevision", "") != prefs.getString("avatarAcknowledged", ""),
    )

    fun theme(value: GazeTheme) {
        if (!prefs.edit().putString("theme", value.name).commit()) throw IOException("主题保存失败")
        state.value = read()
    }

    fun fade(value: Float) {
        if (!prefs.edit().putFloat("fade", value.coerceIn(0f, .8f)).commit()) throw IOException("背景设置保存失败")
        state.value = read()
    }

    suspend fun image(key: String, uri: Uri) = withContext(Dispatchers.IO) {
        require(key == BuildConfig.DEVICE_ROLE || key == "background")
        val maxSize = if (key == "background") 1600 else 256
        val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val scale = minOf(1f, maxSize.toFloat() / maxOf(info.size.width, info.size.height))
            decoder.setTargetSize((info.size.width * scale).toInt().coerceAtLeast(1), (info.size.height * scale).toInt().coerceAtLeast(1))
        }
        val folder = File(context.filesDir, "appearance").apply { if (!isDirectory && !mkdirs()) throw IOException("无法保存图片") }
        val file = File(folder, "$key-${UUID.randomUUID()}.jpg")
        try {
            if (key == "background") file.outputStream().use { if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it)) throw IOException("无法读取这张图片") }
            else file.writeBytes(avatarBytes(bitmap))
            synchronized(this@AppearanceStore) {
                val previous = prefs.getString(key, "").orEmpty()
                val edit = prefs.edit().putString(key, file.absolutePath)
                if (key != "background") edit.putString("ownRevision", UUID.randomUUID().toString())
                if (!edit.commit()) throw IOException("图片设置保存失败")
                state.value = read()
                removeOwned(previous)
            }
        } catch (e: Exception) { file.delete(); throw e }
        finally { bitmap.recycle() }
    }

    @Synchronized fun clear(key: String) {
        require(key == BuildConfig.DEVICE_ROLE || key == "background")
        val previous = prefs.getString(key, "").orEmpty()
        val edit = prefs.edit().remove(key)
        if (key != "background") edit.putString("ownRevision", UUID.randomUUID().toString())
        if (!edit.commit()) throw IOException("图片设置保存失败")
        state.value = read()
        removeOwned(previous)
    }

    @Synchronized fun pendingAvatar(): JSONObject? {
        if (prefs.getString("syncOrigin", "") != BuildConfig.SYNC_ORIGIN) {
            if (!prefs.edit().putString("syncOrigin", BuildConfig.SYNC_ORIGIN).remove("avatarAcknowledged").commit())
                throw IOException("头像迁移状态保存失败")
            state.value = read()
        }
        var revision = prefs.getString("ownRevision", "").orEmpty()
        if (revision.isBlank()) {
            revision = UUID.randomUUID().toString()
            if (!prefs.edit().putString("ownRevision", revision).commit()) throw IOException("头像版本保存失败")
            state.value = read()
        }
        if (revision == prefs.getString("avatarAcknowledged", "")) return null
        val path = prefs.getString(BuildConfig.DEVICE_ROLE, "").orEmpty()
        val data = if (path.isBlank()) "" else {
            val bitmap = BitmapFactory.decodeFile(path) ?: throw IOException("头像读取失败，请重新选择照片")
            try { Base64.encodeToString(avatarBytes(bitmap), Base64.NO_WRAP) } finally { bitmap.recycle() }
        }
        return JSONObject().put("revision", revision).put("data", data)
    }

    @Synchronized fun acknowledgeAvatar(revision: String) {
        // Choosing another photo during an upload must leave the newer photo pending.
        if (revision != prefs.getString("ownRevision", "")) return
        if (!prefs.edit().putString("avatarAcknowledged", revision).commit()) throw IOException("头像同步状态保存失败")
        state.value = read()
    }

    fun partnerAvatarRevision(): String = prefs.getString("partnerRevision", "").orEmpty()

    @Synchronized fun receiveAvatar(avatar: JSONObject) {
        val revision = avatar.getString("revision")
        require(revision == "default" || UUID.fromString(revision).toString() == revision)
        if (revision == partnerAvatarRevision()) return
        val data = avatar.getString("data")
        require(data.length <= 43692)
        val bytes = if (data.isBlank()) null else Base64.decode(data, Base64.NO_WRAP).also { bytes ->
            require(bytes.size <= 32768)
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            require(bounds.outMimeType == "image/jpeg" && bounds.outWidth in 1..320 && bounds.outHeight in 1..320)
            val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: throw IOException("对方头像无法读取")
            decoded.recycle()
        }
        val owner = if (BuildConfig.DEVICE_ROLE == "white") "champion" else "white"
        val folder = File(context.filesDir, "appearance").apply { if (!isDirectory && !mkdirs()) throw IOException("无法保存头像") }
        val file = if (bytes == null) null else File(folder, "$owner-${UUID.randomUUID()}.jpg")
        try {
            if (file != null) file.writeBytes(checkNotNull(bytes))
            val previous = prefs.getString(owner, "").orEmpty()
            if (!prefs.edit().putString(owner, file?.absolutePath.orEmpty()).putString("partnerRevision", revision).commit()) throw IOException("对方头像保存失败")
            state.value = read()
            removeOwned(previous)
        } catch (e: Exception) { file?.delete(); throw e }
    }

    private fun avatarBytes(source: Bitmap): ByteArray {
        val scale = minOf(1f, 256f / maxOf(source.width, source.height))
        val bitmap = if (scale < 1f) Bitmap.createScaledBitmap(source, (source.width * scale).toInt().coerceAtLeast(1),
            (source.height * scale).toInt().coerceAtLeast(1), true) else source
        try {
            for (quality in listOf(82, 65, 45, 25)) {
                val out = ByteArrayOutputStream()
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)) throw IOException("头像压缩失败")
                if (out.size() <= 32768) return out.toByteArray()
            }
            throw IOException("头像过大，请换一张图片")
        } finally { if (bitmap !== source) bitmap.recycle() }
    }

    private fun removeOwned(path: String) {
        if (path.isBlank()) return
        val file = File(path)
        if (file.parentFile?.canonicalFile == File(context.filesDir, "appearance").canonicalFile) file.delete()
    }
}

val LocalAppearance = staticCompositionLocalOf { Appearance() }
val LocalPalette = staticCompositionLocalOf { GazeTheme.ROSE.palette }
val Paper: Color @Composable get() = LocalPalette.current.paper
val Ink: Color @Composable get() = LocalPalette.current.ink
val Muted: Color @Composable get() = LocalPalette.current.muted
val Berry: Color @Composable get() = LocalPalette.current.accent
val Lilac: Color @Composable get() = LocalPalette.current.self
val Card: Color @Composable get() = LocalPalette.current.card
val Green: Color @Composable get() = if (LocalAppearance.current.theme.palette.dark) Color(0xFFA5CFB4) else Color(0xFF447663)
val Sage: Color @Composable get() = if (LocalAppearance.current.theme.palette.dark) Color(0xFF2E4038) else Color(0xFFEAF1EB)

@Composable fun ChampionTheme(appearance: Appearance, content: @Composable () -> Unit) {
    val target = appearance.theme.palette
    @Composable fun blend(color: Color) = animateColorAsState(color, tween(180), label = "theme").value
    val p = target.copy(paper = blend(target.paper), ink = blend(target.ink), muted = blend(target.muted),
        accent = blend(target.accent), self = blend(target.self), card = blend(target.card), chat = blend(target.chat))
    val scheme = if (p.dark) darkColorScheme(primary = p.accent, onPrimary = p.paper, surface = p.paper,
        background = p.paper, onSurface = p.ink, onBackground = p.ink, surfaceVariant = p.card, onSurfaceVariant = p.muted)
    else lightColorScheme(primary = p.accent, onPrimary = Color.White, surface = p.paper,
        background = p.paper, onSurface = p.ink, onBackground = p.ink, surfaceVariant = p.card, onSurfaceVariant = p.muted)
    CompositionLocalProvider(LocalAppearance provides appearance, LocalPalette provides p) { MaterialTheme(colorScheme = scheme, content = content) }
}

@Composable fun photo(path: String): ImageBitmap? {
    val bitmap by produceState<ImageBitmap?>(null, path) {
        value = if (path.isBlank()) null else withContext(Dispatchers.IO) {
            synchronized(PhotoCache.images) {
                PhotoCache.images.get(path) ?: BitmapFactory.decodeFile(path)?.asImageBitmap()?.also { PhotoCache.images.put(path, it) }
            }
        }
    }
    return bitmap
}

private object PhotoCache { val images = LruCache<String, ImageBitmap>(5) }

@Composable fun ChatBackdrop(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val appearance = LocalAppearance.current
    val bitmap = photo(appearance.background)
    val palette = LocalPalette.current
    Box(modifier.background(Brush.verticalGradient(listOf(palette.paper, palette.chat, lerp(palette.chat, palette.self, .25f))))) {
        if (bitmap != null) {
            Image(bitmap, null, Modifier.matchParentSize(), contentScale = ContentScale.Crop)
            Box(Modifier.matchParentSize().background(Paper.copy(alpha = appearance.backgroundFade)))
        } else {
            Canvas(Modifier.matchParentSize()) {
                // A few quiet confetti strokes echo the user's hand-drawn cat icon.
                val points = listOf(.09f to .12f, .9f to .06f, .81f to .29f, .18f to .43f, .95f to .66f, .08f to .8f, .74f to .92f)
                points.forEachIndexed { index, (x, y) ->
                    val center = Offset(this.size.width * x, this.size.height * y)
                    val color = palette.accent.copy(alpha = if (palette.dark) .15f else .09f)
                    if (palette.dark) drawCircle(color, 2.dp.toPx(), center)
                    else drawLine(color, center, center + Offset(7.dp.toPx(), if (index % 2 == 0) 3.dp.toPx() else -3.dp.toPx()), 2.dp.toPx(), StrokeCap.Round)
                }
            }
        }
        content()
    }
}
