package com.eldering.safety.alert

import com.eldering.safety.data.trail.Geo
import com.eldering.safety.data.trail.TrailPoint
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Reverse geocoding for trail landmarks (decoration only: the Geocoder docs forbid safety-critical use, so SMS and
 * the server always carry raw coordinates). Returns null when nothing is known. Must honour cancellation.
 */
fun interface LandmarkResolver {
    suspend fun landmark(lat: Double, lng: Double): String?
}

/**
 * Picks the ~10 trail points worth a landmark (docs/research/android-background.md §7), most important first:
 * the incident point, the oldest point, stops (dwell ≥ [dwellMs] within [dwellRadiusM]), large turns
 * (≥ [turnDeg] between ≥ [minSegmentM] segments), then fill-in points evenly spaced in time.
 * Points closer than [minSeparationM] to an already chosen one are skipped (one name per place).
 */
object KeyPoints {
    fun select(
        trail: List<TrailPoint>,
        incidentAtMs: Long,
        max: Int = 10,
        dwellMs: Long = 60_000,
        dwellRadiusM: Double = 40.0,
        turnDeg: Double = 45.0,
        minSegmentM: Double = 30.0,
        minSeparationM: Double = 60.0,
    ): List<Int> {
        if (trail.isEmpty() || max <= 0) return emptyList()
        val chosen = ArrayList<Int>(max)
        fun offer(i: Int) {
            if (chosen.size >= max || i !in trail.indices || i in chosen) return
            if (chosen.any { Geo.distanceM(trail[it], trail[i]) < minSeparationM }) return
            chosen += i
        }
        // 1. Where it happened: the last fix at or before the incident (else the first after it).
        val incident = trail.indexOfLast { it.recordedAtMs <= incidentAtMs }.takeIf { it >= 0 } ?: 0
        offer(incident)
        // 2. Where the hour started.
        offer(0)
        // 3. Stops.
        var i = 0
        while (i < trail.size) {
            var j = i
            while (j + 1 < trail.size && Geo.distanceM(trail[i], trail[j + 1]) <= dwellRadiusM) j++
            if (trail[j].recordedAtMs - trail[i].recordedAtMs >= dwellMs) offer(i)
            i = j + 1
        }
        // 4. Turns.
        for (k in 1 until trail.size - 1) {
            val a = trail[k - 1]
            val b = trail[k]
            val c = trail[k + 1]
            if (Geo.distanceM(a, b) < minSegmentM || Geo.distanceM(b, c) < minSegmentM) continue
            if (angleDiff(bearing(a, b), bearing(b, c)) >= turnDeg) offer(k)
        }
        // 5. Fill evenly in time.
        val slots = max - chosen.size
        if (slots > 0 && trail.size > 1) {
            val t0 = trail.first().recordedAtMs
            val t1 = trail.last().recordedAtMs
            for (s in 1..slots * 2) {
                if (chosen.size >= max) break
                val target = t0 + (t1 - t0) * s / (slots * 2 + 1)
                val nearest = trail.indices.minByOrNull { abs(trail[it].recordedAtMs - target) } ?: continue
                offer(nearest)
            }
        }
        return chosen
    }

    private fun bearing(a: TrailPoint, b: TrailPoint): Double {
        val la1 = Math.toRadians(a.lat)
        val la2 = Math.toRadians(b.lat)
        val dl = Math.toRadians(b.lng - a.lng)
        val y = sin(dl) * cos(la2)
        val x = cos(la1) * sin(la2) - sin(la1) * cos(la2) * cos(dl)
        return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
    }

    private fun angleDiff(a: Double, b: Double): Double {
        val d = abs(a - b) % 360.0
        return if (d > 180.0) 360.0 - d else d
    }
}
