package app.champion

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class PartnerNotifications(private val context: Context, private val partner: String) {
    companion object {
        const val CHANNEL = "partner_activity"
        const val ID = 4200
        fun createChannel(context: Context) {
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, "对方的重点 App 动态", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "微信、小红书、淘宝、抖音的新动态；收到同步后提醒"
                    enableVibration(true)
                })
        }
    }

    private val prefs = context.getSharedPreferences("partner_notifications", Context.MODE_PRIVATE)
    private val enabledAt = prefs.getLong("enabledAt", 0L).let { saved ->
        if (saved > 0) saved else System.currentTimeMillis().also {
            check(prefs.edit().putLong("enabledAt", it).commit()) { "通知设置保存失败" }
        }
    }

    init { createChannel(context) }

    fun show(fresh: List<Moment>) {
        if (LiveState.foreground) return
        val selected = AlertPolicy.select(fresh, partner, enabledAt, System.currentTimeMillis())
        if (selected.isEmpty()) return
        val manager = context.getSystemService(NotificationManager::class.java)
        if (!manager.areNotificationsEnabled() || manager.getNotificationChannel(CHANNEL)?.importance == NotificationManager.IMPORTANCE_NONE) return
        val clock = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())
        val lines = selected.map { "${clock.format(Instant.ofEpochMilli(it.at))} 打开了${Apps.preset(it.packageName)?.name ?: it.appName}" }
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pending = PendingIntent.getActivity(context, ID, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val title = if (selected.size == 1) "$partner 打开了${Apps.preset(selected.first().packageName)?.name}"
            else "$partner 的 ${selected.size} 款 App 新动态"
        val notification = Notification.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_status)
            .setContentTitle(title).setContentText(lines.first()).setContentIntent(pending)
            .setStyle(Notification.InboxStyle().also { style -> lines.forEach { style.addLine(it) } }
                .setSummaryText("点开查看日常 · 按实际发生时间显示"))
            .setWhen(selected.first().at).setShowWhen(true).setAutoCancel(true)
            .setCategory(Notification.CATEGORY_SOCIAL).setVisibility(Notification.VISIBILITY_PRIVATE)
            .setSubText("love gaze").build()
        manager.notify(ID, notification)
    }
}
