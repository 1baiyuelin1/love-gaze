package app.champion

import android.Manifest
import android.app.AppOpsManager
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.os.Process

data class Access(val usage: Boolean, val location: Boolean, val background: Boolean, val notifications: Boolean, val locationEnabled: Boolean, val precise: Boolean, val partnerAlerts: Boolean = true) {
    val ready get() = usage && notifications
    val locationReady get() = location && background && locationEnabled
}

fun access(context: Context): Access {
    fun granted(permission: String) = context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    val ops = checkNotNull(context.getSystemService(AppOpsManager::class.java))
    val usage = ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName) == AppOpsManager.MODE_ALLOWED
    return Access(
        usage,
        granted(Manifest.permission.ACCESS_COARSE_LOCATION) || granted(Manifest.permission.ACCESS_FINE_LOCATION),
        granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION),
        (Build.VERSION.SDK_INT < 33 || granted(Manifest.permission.POST_NOTIFICATIONS)) && checkNotNull(context.getSystemService(NotificationManager::class.java)).areNotificationsEnabled(),
        checkNotNull(context.getSystemService(LocationManager::class.java)).isLocationEnabled,
        granted(Manifest.permission.ACCESS_FINE_LOCATION),
        context.getSystemService(NotificationManager::class.java).getNotificationChannel(PartnerNotifications.CHANNEL)?.importance
            != NotificationManager.IMPORTANCE_NONE,
    )
}
