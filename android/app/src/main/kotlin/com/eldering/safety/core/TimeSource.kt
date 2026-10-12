package com.eldering.safety.core

import android.os.SystemClock

/** Wall-clock time in epoch milliseconds. Injected everywhere time matters so tests can control it. */
fun interface TimeSource {
    fun nowMillis(): Long

    companion object {
        val System: TimeSource = TimeSource { java.lang.System.currentTimeMillis() }
    }
}

/**
 * Converts a monotonic `elapsedRealtimeNanos` timestamp (the time base of `SensorEvent.timestamp`,
 * `Location.elapsedRealtimeNanos` and `DetectionEvent.triggeredAtNanos`) to wall-clock epoch milliseconds.
 */
fun elapsedRealtimeNanosToWallMillis(
    elapsedNanos: Long,
    nowWallMillis: Long = java.lang.System.currentTimeMillis(),
    nowElapsedNanos: Long = SystemClock.elapsedRealtimeNanos(),
): Long = nowWallMillis - (nowElapsedNanos - elapsedNanos) / 1_000_000L
