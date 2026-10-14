package com.eldering.safety.ui.platform

import android.Manifest
import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.location.Address
import android.location.Geocoder
import android.os.BatteryManager
import android.os.Build
import android.util.LruCache
import androidx.core.content.ContextCompat
import com.eldering.safety.core.TimeSource
import com.eldering.safety.data.trail.TrailStore
import com.eldering.safety.network.Activity
import com.google.android.gms.location.LocationServices
import java.io.IOException
import java.util.Locale
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Short human place name for a coordinate ("Ring Road, Indiranagar"), or null. Decoration only. */
fun interface PlaceNamer {
    suspend fun nameFor(lat: Double, lng: Double): String?
}

/** My own latest position (the server never returns me in `/v1/family/locations`). */
data class MyFix(
    val lat: Double,
    val lng: Double,
    val accuracyM: Float?,
    val recordedAtMs: Long,
    val activity: Activity?,
    val speedMps: Float? = null,
)

fun interface MyLocationSource {
    suspend fun lastKnown(): MyFix?
}

/** Whether background protection runs, and the movement mode it last saw. */
data class ProtectionStatus(val running: Boolean, val activity: Activity?)

fun interface ProtectionStatusSource {
    suspend fun current(): ProtectionStatus
}

fun interface BatteryLevelSource {
    fun percent(): Int?
}

/**
 * Android [Geocoder] (needs no API key; uses the platform/Play services backend). Results are cached per ~100 m
 * cell so polling never re-geocodes a member who has not moved.
 */
class GeocoderPlaceNamer(context: Context) : PlaceNamer {
    private val geocoder = Geocoder(context.applicationContext, Locale.getDefault())
    private val cache = LruCache<String, String>(128)

    override suspend fun nameFor(lat: Double, lng: Double): String? {
        if (!Geocoder.isPresent()) return null
        val key = String.format(Locale.ROOT, "%.3f,%.3f", lat, lng)
        cache.get(key)?.let { return it }
        val name = withTimeoutOrNull(LOOKUP_TIMEOUT_MS) { lookup(lat, lng) }?.let(::describe)
        if (name != null) cache.put(key, name)
        return name
    }

    private suspend fun lookup(lat: Double, lng: Double): Address? =
        if (Build.VERSION.SDK_INT >= 33) {
            suspendCancellableCoroutine { cont ->
                geocoder.getFromLocation(
                    lat,
                    lng,
                    1,
                    object : Geocoder.GeocodeListener {
                        override fun onGeocode(addresses: MutableList<Address>) {
                            if (cont.isActive) cont.resume(addresses.firstOrNull())
                        }

                        override fun onError(errorMessage: String?) {
                            if (cont.isActive) cont.resume(null)
                        }
                    },
                )
            }
        } else {
            withContext(Dispatchers.IO) {
                try {
                    @Suppress("DEPRECATION")
                    geocoder.getFromLocation(lat, lng, 1)?.firstOrNull()
                } catch (e: IOException) {
                    null
                } catch (e: IllegalArgumentException) {
                    null
                }
            }
        }

    internal companion object {
        private const val LOOKUP_TIMEOUT_MS = 6_000L

        /** Street (or named place) plus neighbourhood; never the full postal address. */
        fun describe(address: Address): String? {
            val street = address.thoroughfare?.takeIf { it.isNotBlank() }
            val feature = address.featureName?.takeIf { it.isNotBlank() && it != street && !it.any(Char::isDigit) }
            val area = (address.subLocality ?: address.locality)?.takeIf { it.isNotBlank() }
            val primary = feature ?: street
            return when {
                primary != null && area != null && area != primary -> "$primary, $area"
                primary != null -> primary
                else -> area
            }
        }
    }
}

/**
 * Prefers the trail the safety service records (no extra location request, no battery cost); falls back to the
 * fused provider's cached fix when location permission is granted.
 */
class DeviceMyLocation(
    private val context: Context,
    private val trailStore: TrailStore,
    private val time: TimeSource = TimeSource.System,
) : MyLocationSource {
    override suspend fun lastKnown(): MyFix? {
        val now = time.nowMillis()
        trailStore.latest()?.takeIf { now - it.recordedAtMs <= FRESH_MS }?.let {
            return MyFix(it.lat, it.lng, it.accuracyM, it.recordedAtMs, it.activity, it.speedMps)
        }
        if (!hasLocationPermission(context)) return null
        return try {
            @SuppressLint("MissingPermission")
            val location = LocationServices.getFusedLocationProviderClient(context).lastLocation.await()
            location?.let { MyFix(it.latitude, it.longitude, if (it.hasAccuracy()) it.accuracy else null, it.time, null, if (it.hasSpeed()) it.speed else null) }
        } catch (e: SecurityException) {
            null
        } catch (e: Exception) { // Play services missing or the task failed: no position is fine here
            null
        }
    }

    private companion object {
        const val FRESH_MS = 15 * 60_000L
    }
}

fun hasLocationPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

/**
 * "Protection on" means the foreground SafetyService is running (looked up by class name so the UI does not
 * depend on the service implementation). The activity comes from the newest trail point the service stored.
 */
class DeviceProtectionStatus(
    private val context: Context,
    private val trailStore: TrailStore,
    private val time: TimeSource = TimeSource.System,
) : ProtectionStatusSource {
    override suspend fun current(): ProtectionStatus {
        val latest = trailStore.latest()?.takeIf { time.nowMillis() - it.recordedAtMs <= ACTIVITY_FRESH_MS }
        return ProtectionStatus(running = isSafetyServiceRunning(), activity = latest?.activity)
    }

    private fun isSafetyServiceRunning(): Boolean {
        val manager = context.getSystemService(ActivityManager::class.java) ?: return false
        // getRunningServices is deprecated for *other* apps' services; it still reports this app's own services.
        @Suppress("DEPRECATION")
        val services = manager.getRunningServices(Int.MAX_VALUE).orEmpty()
        return services.any { it.service.packageName == context.packageName && it.service.className == SAFETY_SERVICE_CLASS }
    }

    companion object {
        const val SAFETY_SERVICE_CLASS = "com.eldering.safety.service.SafetyService"
        private const val ACTIVITY_FRESH_MS = 10 * 60_000L
    }
}

class DeviceBatteryLevel(private val context: Context) : BatteryLevelSource {
    override fun percent(): Int? = context.getSystemService(BatteryManager::class.java)
        ?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        ?.takeIf { it in 0..100 }
}
