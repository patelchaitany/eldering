package com.eldering.safety.data.trail

import com.eldering.safety.network.Activity

/**
 * Which fixes [TrailStore] keeps. Location requests are deliberately not distance-filtered (their callbacks also
 * pace sensor FIFO drains, see docs/research/android-background.md §0), so the spatial filter lives here.
 *
 * A fix is kept when it is newer than the last kept one and either
 * - at least [minIntervalMs] passed and it moved at least [minDistanceM], or
 * - [maxIntervalMs] passed (a keep-alive point, so stops still show up on the timeline).
 * Fixes worse than [maxAccuracyM] are only used as keep-alive points.
 */
data class TrailSamplingPolicy(
    val minIntervalMs: Long,
    val minDistanceM: Double,
    val maxIntervalMs: Long,
    val maxAccuracyM: Float,
) {
    init {
        require(minIntervalMs >= 0 && maxIntervalMs >= minIntervalMs) { "Invalid intervals" }
        require(minDistanceM >= 0) { "Invalid distance" }
    }

    fun shouldKeep(previous: TrailPoint?, next: TrailPoint): Boolean {
        if (previous == null) return true
        val dt = next.recordedAtMs - previous.recordedAtMs
        if (dt <= 0) return false // duplicate or out of order
        val accuracy = next.accuracyM
        if (accuracy != null && accuracy > maxAccuracyM) return dt >= maxIntervalMs
        if (dt < minIntervalMs) return false
        if (dt >= maxIntervalMs) return true
        return Geo.distanceM(previous, next) >= minDistanceM
    }

    companion object {
        /** Passive fixes only; mostly keep-alive points. */
        val STILL = TrailSamplingPolicy(minIntervalMs = 30_000, minDistanceM = 30.0, maxIntervalMs = 5 * 60_000, maxAccuracyM = 200f)

        /** SPEC: walking 30 s / 20 m. */
        val WALKING = TrailSamplingPolicy(minIntervalMs = 5_000, minDistanceM = 20.0, maxIntervalMs = 60_000, maxAccuracyM = 100f)

        /** SPEC: vehicle 5–10 s. */
        val VEHICLE = TrailSamplingPolicy(minIntervalMs = 2_000, minDistanceM = 10.0, maxIntervalMs = 15_000, maxAccuracyM = 100f)

        /** Possible accident / live incident: keep (almost) everything. */
        val ALERTING = TrailSamplingPolicy(minIntervalMs = 1_000, minDistanceM = 0.0, maxIntervalMs = 5_000, maxAccuracyM = 500f)

        fun forActivity(activity: Activity?): TrailSamplingPolicy = when (activity) {
            Activity.VEHICLE, Activity.CYCLING -> VEHICLE
            Activity.WALKING, Activity.RUNNING -> WALKING
            Activity.STILL -> STILL
            Activity.UNKNOWN, null -> WALKING
        }
    }
}
