package com.eldering.safety.data.trail

import android.location.Location
import android.os.Build
import com.eldering.safety.data.db.TrailPointEntity
import com.eldering.safety.network.Activity
import com.eldering.safety.network.ApiJson
import com.eldering.safety.network.TrailPointDto
import java.time.Instant
import kotlinx.serialization.json.JsonPrimitive

/** A location fix in the device trail. */
data class TrailPoint(
    /** Wall-clock epoch ms of the fix. */
    val recordedAtMs: Long,
    val lat: Double,
    val lng: Double,
    val accuracyM: Float? = null,
    val speedMps: Float? = null,
    val bearingDeg: Float? = null,
    val altitudeM: Double? = null,
    val activity: Activity? = null,
    /** Monotonic fix time (`Location.elapsedRealtimeNanos`), aligned with sensor timestamps. */
    val elapsedRealtimeNanos: Long? = null,
) {
    init {
        require(lat in -90.0..90.0 && lng in -180.0..180.0) { "Invalid coordinates" }
    }

    fun toDto(landmark: String? = null): TrailPointDto = TrailPointDto(
        lat = lat,
        lng = lng,
        recordedAt = Instant.ofEpochMilli(recordedAtMs),
        accuracyM = accuracyM,
        landmark = landmark,
    )
}

/** Converts a Fused Location fix (keeps only fields the fix actually has). */
fun Location.toTrailPoint(activity: Activity? = null): TrailPoint = TrailPoint(
    recordedAtMs = time,
    lat = latitude,
    lng = longitude,
    accuracyM = if (hasAccuracy()) accuracy else null,
    speedMps = if (hasSpeed()) speed else null,
    bearingDeg = if (hasBearing()) bearing else null,
    altitudeM = if (hasAltitude()) altitude else null,
    activity = activity,
    elapsedRealtimeNanos = elapsedRealtimeNanos.takeIf { it > 0 },
)

/** Mock fixes (developer options / test tools) are reported so the caller can decide to ignore them. */
val Location.isMockFix: Boolean
    get() = if (Build.VERSION.SDK_INT >= 31) isMock else @Suppress("DEPRECATION") isFromMockProvider

internal fun TrailPointEntity.toTrailPoint(): TrailPoint = TrailPoint(
    recordedAtMs = recordedAtMs,
    lat = lat,
    lng = lng,
    accuracyM = accuracyM,
    speedMps = speedMps,
    bearingDeg = bearingDeg,
    altitudeM = altitudeM,
    activity = activity?.let(::activityFromWire),
    elapsedRealtimeNanos = elapsedRealtimeNanos,
)

internal fun TrailPoint.toEntity(): TrailPointEntity = TrailPointEntity(
    recordedAtMs = recordedAtMs,
    elapsedRealtimeNanos = elapsedRealtimeNanos,
    lat = lat,
    lng = lng,
    accuracyM = accuracyM,
    speedMps = speedMps,
    bearingDeg = bearingDeg,
    altitudeM = altitudeM,
    activity = activity?.let(::activityToWire),
)

internal fun activityToWire(activity: Activity): String =
    (ApiJson.encodeToJsonElement(Activity.serializer(), activity) as JsonPrimitive).content

internal fun activityFromWire(value: String): Activity? =
    Activity.entries.firstOrNull { activityToWire(it) == value }
