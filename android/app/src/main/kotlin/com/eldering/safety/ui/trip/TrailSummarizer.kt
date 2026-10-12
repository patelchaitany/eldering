package com.eldering.safety.ui.trip

import com.eldering.safety.data.trail.Geo
import com.eldering.safety.network.Activity
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/** One point of a displayed trail (on-device trail or an incident trail from the server). */
data class TrailSample(
    val lat: Double,
    val lng: Double,
    val tMs: Long,
    val speedMps: Float? = null,
    val accuracyM: Float? = null,
    val activity: Activity? = null,
    /** Landmark text from the sender (incident trails), if any. */
    val landmark: String? = null,
)

enum class LandmarkKind { START, STOP, WAYPOINT, NAMED, END, INCIDENT }

/** A numbered pin on the trail. [name] is a place name if one is known (from the sender or geocoded later). */
data class Landmark(
    val index: Int,
    val kind: LandmarkKind,
    val durationMs: Long = 0,
    val speedMps: Float? = null,
    val bearingDeg: Double? = null,
    val name: String? = null,
)

data class TripSummary(
    val distanceM: Double,
    val startMs: Long,
    val endMs: Long,
    /** The activity with the most time in the window, if the trail carries activities. */
    val dominantActivity: Activity?,
    /** In trail order. */
    val landmarks: List<Landmark>,
    /** Index of the point nearest the incident, if this is an incident trail. */
    val incidentIndex: Int?,
)

/**
 * Picks the few points worth naming on "Trip to past": where it started, where the person stopped, a couple of
 * waypoints with heading and speed, landmarks the sender already named, and where it ended (or the impact).
 */
object TrailSummarizer {
    const val STOP_RADIUS_M = 60.0
    const val STOP_MIN_MS = 3 * 60_000L
    const val MERGE_RADIUS_M = 100.0

    /** Faster than this between two fixes is a GPS jump, not movement (≈324 km/h). */
    private const val MAX_PLAUSIBLE_SPEED_MPS = 90.0

    fun summarize(points: List<TrailSample>, incidentAtMs: Long? = null, maxLandmarks: Int = 6): TripSummary? {
        if (points.isEmpty()) return null
        val cumulative = DoubleArray(points.size)
        for (i in 1 until points.size) {
            val d = Geo.distanceM(points[i - 1].lat, points[i - 1].lng, points[i].lat, points[i].lng)
            val dt = (points[i].tMs - points[i - 1].tMs).coerceAtLeast(1) / 1000.0
            cumulative[i] = cumulative[i - 1] + if (d / dt > MAX_PLAUSIBLE_SPEED_MPS) 0.0 else d
        }
        val total = cumulative.last()
        val incidentIndex = incidentAtMs?.let { at -> points.indices.minByOrNull { kotlin.math.abs(points[it].tMs - at) } }
        val last = points.lastIndex

        val candidates = mutableListOf<Landmark>()
        incidentIndex?.let { candidates += Landmark(it, LandmarkKind.INCIDENT, name = points[it].landmark) }
        candidates += Landmark(0, LandmarkKind.START, name = points[0].landmark)
        if (last > 0 && (incidentIndex == null || distance(points, incidentIndex, last) > MERGE_RADIUS_M)) {
            candidates += Landmark(last, LandmarkKind.END, name = points[last].landmark)
        }
        points.forEachIndexed { i, p ->
            if (p.landmark != null && i != 0 && i != last && i != incidentIndex) candidates += Landmark(i, LandmarkKind.NAMED, name = p.landmark)
        }
        candidates += findStops(points).sortedByDescending { it.durationMs }
        if (total > 500.0) {
            listOf(1.0 / 3, 2.0 / 3).forEach { fraction ->
                val target = total * fraction
                val i = cumulative.indexOfFirst { it >= target }.takeIf { it in 1 until last } ?: return@forEach
                candidates += Landmark(i, LandmarkKind.WAYPOINT, speedMps = speedAt(points, i), bearingDeg = bearingAt(points, i), name = points[i].landmark)
            }
        }

        val kept = mutableListOf<Landmark>()
        candidates.sortedBy { priority(it.kind) }.forEach { candidate ->
            val nearIndex = kept.indexOfFirst { distance(points, it.index, candidate.index) <= MERGE_RADIUS_M && overlapsInTime(points, it, candidate) }
            if (nearIndex >= 0) {
                val existing = kept[nearIndex]
                kept[nearIndex] = existing.copy(
                    durationMs = maxOf(existing.durationMs, candidate.durationMs),
                    name = existing.name ?: candidate.name,
                )
            } else if (kept.size < maxLandmarks) {
                kept += candidate
            }
        }
        return TripSummary(
            distanceM = total,
            startMs = points.first().tMs,
            endMs = points.last().tMs,
            dominantActivity = dominantActivity(points),
            landmarks = kept.sortedBy { it.index },
            incidentIndex = incidentIndex,
        )
    }

    /** Places the person stayed within [STOP_RADIUS_M] for at least [STOP_MIN_MS]. */
    fun findStops(points: List<TrailSample>): List<Landmark> {
        val stops = mutableListOf<Landmark>()
        var i = 0
        while (i < points.size) {
            var j = i
            while (j + 1 < points.size && distance(points, i, j + 1) <= STOP_RADIUS_M) j++
            val duration = points[j].tMs - points[i].tMs
            if (duration >= STOP_MIN_MS) {
                stops += Landmark(i, LandmarkKind.STOP, durationMs = duration, name = points[i].landmark)
                i = j + 1
            } else {
                i++
            }
        }
        return stops
    }

    fun dominantActivity(points: List<TrailSample>): Activity? {
        val time = mutableMapOf<Activity, Long>()
        for (i in 0 until points.lastIndex) {
            val a = points[i].activity ?: continue
            if (a == Activity.UNKNOWN) continue
            time[a] = (time[a] ?: 0L) + (points[i + 1].tMs - points[i].tMs).coerceAtLeast(0)
        }
        return time.maxByOrNull { it.value }?.key ?: points.lastOrNull { it.activity != null && it.activity != Activity.UNKNOWN }?.activity
    }

    /** Initial great-circle bearing from point a to b, degrees clockwise from north. */
    fun bearing(aLat: Double, aLng: Double, bLat: Double, bLng: Double): Double {
        val phi1 = Math.toRadians(aLat)
        val phi2 = Math.toRadians(bLat)
        val dLambda = Math.toRadians(bLng - aLng)
        val y = sin(dLambda) * cos(phi2)
        val x = cos(phi1) * sin(phi2) - sin(phi1) * cos(phi2) * cos(dLambda)
        return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
    }

    private fun priority(kind: LandmarkKind): Int = when (kind) {
        LandmarkKind.INCIDENT -> 0
        LandmarkKind.START -> 1
        LandmarkKind.END -> 2
        LandmarkKind.NAMED -> 3
        LandmarkKind.STOP -> 4
        LandmarkKind.WAYPOINT -> 5
    }

    /** Two candidates only merge if they are the same visit (a route can pass the same place twice). */
    private fun overlapsInTime(points: List<TrailSample>, a: Landmark, b: Landmark): Boolean {
        val aEnd = points[a.index].tMs + a.durationMs
        val bEnd = points[b.index].tMs + b.durationMs
        val gap = maxOf(points[a.index].tMs, points[b.index].tMs) - minOf(aEnd, bEnd)
        return gap <= 5 * 60_000L
    }

    private fun distance(points: List<TrailSample>, i: Int, j: Int): Double =
        Geo.distanceM(points[i].lat, points[i].lng, points[j].lat, points[j].lng)

    private fun speedAt(points: List<TrailSample>, i: Int): Float? {
        points[i].speedMps?.let { return it }
        val a = points[(i - 1).coerceAtLeast(0)]
        val b = points[(i + 1).coerceAtMost(points.lastIndex)]
        val dt = (b.tMs - a.tMs) / 1000.0
        if (dt <= 0) return null
        return (Geo.distanceM(a.lat, a.lng, b.lat, b.lng) / dt).toFloat()
    }

    private fun bearingAt(points: List<TrailSample>, i: Int): Double? {
        val a = points[(i - 1).coerceAtLeast(0)]
        val b = points[(i + 1).coerceAtMost(points.lastIndex)]
        if (Geo.distanceM(a.lat, a.lng, b.lat, b.lng) < 10.0) return null
        return bearing(a.lat, a.lng, b.lat, b.lng)
    }
}
