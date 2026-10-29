package app.champion

import android.content.Context
import android.location.Geocoder
import android.location.Address
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Build
import android.os.Looper
import android.os.SystemClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.coroutines.resume

class LocationCollector(
    private val context: Context,
    private val pairing: Pairing,
    private val scope: CoroutineScope,
    private val store: EventStore,
) : LocationListener {
    private val manager = checkNotNull(context.getSystemService(LocationManager::class.java))
    private var last: Location? = null
    private val movement = MovementTracker(store.locationAnchor(pairing.role), { previous, sample ->
        val result = FloatArray(1)
        Location.distanceBetween(checkNotNull(previous.latitude), checkNotNull(previous.longitude),
            checkNotNull(sample.latitude), checkNotNull(sample.longitude), result)
        result[0]
    }, store::saveLocationAnchor)
    private var fine = false
    private var forceNext = false
    @Volatile private var generation = 0L
    private var lookup: Job? = null
    @Volatile var currentPlace = "等待定位"
        private set
    @Volatile var error = ""
        private set

    fun start() {
        fine = access(context).precise
        val providers = manager.getProviders(true).filter {
            it == LocationManager.NETWORK_PROVIDER || (fine && it == LocationManager.GPS_PROVIDER)
        }
        if (providers.isEmpty()) { message("系统定位尚不可用，请检查手机定位开关"); return }
        message("正在获取新位置；室内可稍后到窗边重试")
        for (provider in providers) manager.requestLocationUpdates(provider, 30_000L, 25f, this, Looper.getMainLooper())
    }

    fun refresh(force: Boolean = false) {
        if (!force && fine == access(context).precise) return
        manager.removeUpdates(this)
        forceNext = true
        start()
    }

    private fun message(value: String) {
        LiveState.value.update { it.copy(locationMessage = value) }
    }

    override fun onLocationChanged(location: Location) {
        if (!location.hasAccuracy() || !location.accuracy.isFinite() || location.accuracy < 0 ||
            !location.latitude.isFinite() || !location.longitude.isFinite()) return
        val age = SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos
        if (age < 0 || age > 120_000_000_000L) return
        if (location.accuracy > 150f) {
            message("定位误差约 ${location.accuracy.toInt()} 米，正在等待更准确的位置")
            return
        }
        val previous = last
        val candidate = Moment(owner = pairing.role, at = location.time, kind = Kind.LOCATION,
            latitude = location.latitude, longitude = location.longitude, accuracy = location.accuracy.toDouble())
        if (previous != null && !forceNext) {
            val improved = previous.accuracy > 50 && location.accuracy < previous.accuracy / 2
            if (!improved && !movement.moved(candidate)) return
        }
        forceNext = false
        last = Location(location)
        error = ""
        val sample = Location(location)
        val request = ++generation
        lookup?.cancel()
        currentPlace = "坐标 ${Places.coordinates(sample.latitude, sample.longitude)}"
        LiveState.value.update { it.copy(locationPlace = currentPlace, locationAccuracy = sample.accuracy,
            locationAt = sample.time, locationMessage = "已取得坐标，正在查询地址") }
        lookup = scope.launch {
            val address = withContext(Dispatchers.IO) { try {
                if (Geocoder.isPresent()) {
                    val result = withTimeoutOrNull(8_000L) { addressFor(sample) }
                    result?.let {
                        Places.address(it.getAddressLine(0).orEmpty(), it.countryName.orEmpty(), listOfNotNull(
                            it.adminArea, it.subAdminArea, it.locality, it.subLocality, it.thoroughfare,
                            it.subThoroughfare, it.featureName))
                    }.orEmpty()
                } else ""
            } catch (_: java.io.IOException) { "" }
            catch (_: IllegalArgumentException) { "" } }
            if (request != generation) return@launch
            currentPlace = address.ifBlank { "坐标 ${Places.coordinates(sample.latitude, sample.longitude)}" }
            try {
                movement.accept(candidate.copy(place = currentPlace))
                LiveState.value.update { it.copy(locationPlace = currentPlace, locationMessage =
                    if (address.isBlank()) "系统未提供详细地址，已显示真实坐标" else "地址来自手机定位服务") }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                android.util.Log.e("champion", "Location storage failed: ${e.javaClass.simpleName}")
                error = "本次位置未能保存，请检查手机存储空间"
                last = null // Retry the baseline or movement on the next valid fix.
            }
        }
    }

    private suspend fun addressFor(sample: Location): Address? {
        val geocoder = Geocoder(context, Locale.SIMPLIFIED_CHINESE)
        if (Build.VERSION.SDK_INT >= 33) return suspendCancellableCoroutine { continuation ->
            geocoder.getFromLocation(sample.latitude, sample.longitude, 1, object : Geocoder.GeocodeListener {
                override fun onGeocode(addresses: MutableList<Address>) {
                    if (continuation.isActive) continuation.resume(addresses.firstOrNull())
                }
                override fun onError(errorMessage: String?) {
                    if (continuation.isActive) continuation.resume(null)
                }
            })
        }
        @Suppress("DEPRECATION")
        return geocoder.getFromLocation(sample.latitude, sample.longitude, 1)?.firstOrNull()
    }

    override fun onProviderDisabled(provider: String) {
        if (!manager.isLocationEnabled) { error = "系统定位服务已关闭"; message(error) }
    }
    override fun onProviderEnabled(provider: String) { error = "" }
    @Deprecated("Legacy Android callback")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
    fun stop() { ++generation; lookup?.cancel(); manager.removeUpdates(this) }
}
