package com.eldering.safety.data.repository

import com.eldering.detection.DetectionEvent
import com.eldering.detection.EventKind
import com.eldering.detection.Mode
import com.eldering.safety.network.IncidentKind
import com.eldering.safety.network.TrailPointDto
import java.time.Instant
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/** Everything needed to raise an incident; see [IncidentRepository.report]. */
data class IncidentDraft(
    val kind: IncidentKind,
    val detectedAtMs: Long,
    val lat: Double? = null,
    val lng: Double? = null,
    /** JSON object, at most 16 KiB (see [IncidentDetails]). */
    val details: JsonObject = JsonObject(emptyMap()),
    /** Pre-event trail, e.g. `TrailStore.snapshot(...)` mapped with `toDto(landmark)`. */
    val trail: List<TrailPointDto> = emptyList(),
) {
    init {
        require((lat == null) == (lng == null)) { "lat and lng must both be set or both be null" }
    }

    /** Oldest-first, de-duplicated, capped trail (the server rejects >5000 points and bodies >1 MB). */
    internal fun normalizedTrail(): List<TrailPointDto> =
        trail.distinctBy { it.recordedAt }.sortedBy { it.recordedAt }.takeLast(MAX_TRAIL_POINTS)

    companion object {
        const val MAX_TRAIL_POINTS = 2_000
    }
}

/** Builders for the free-form `details` object of an incident. */
object IncidentDetails {
    private const val MAX_REASON_LENGTH = 500

    fun kindOf(kind: EventKind): IncidentKind = when (kind) {
        EventKind.VEHICLE_CRASH -> IncidentKind.VEHICLE_CRASH
        EventKind.PEDESTRIAN_IMPACT -> IncidentKind.PEDESTRIAN_IMPACT
        EventKind.FALL -> IncidentKind.FALL
    }

    /** Detector output. Non-finite feature values are dropped (JSON cannot carry NaN/Infinity). */
    fun fromDetection(event: DetectionEvent, mode: Mode?, detectedAt: Instant? = null): JsonObject = buildJsonObject {
        put("source", "detector")
        if (event.confidence.isFinite()) put("confidence", event.confidence)
        put("reason", event.reason.take(MAX_REASON_LENGTH))
        mode?.let { put("mode", it.name.lowercase()) }
        detectedAt?.let { put("device_time", it.toString()) }
        putJsonObject("features") {
            event.features.toSortedMap().forEach { (name, value) -> if (value.isFinite()) put(name, value) }
        }
    }

    /** Manual hold-to-SOS. */
    fun sos(): JsonObject = buildJsonObject { put("source", "sos") }
}
