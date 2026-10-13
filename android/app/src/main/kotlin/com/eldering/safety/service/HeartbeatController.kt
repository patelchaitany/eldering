package com.eldering.safety.service

import com.eldering.detection.Mode
import com.eldering.safety.core.TimeSource
import com.eldering.safety.data.trail.Geo
import com.eldering.safety.data.trail.TrailPoint
import com.eldering.safety.network.ApiError
import com.eldering.safety.network.ApiResult
import com.eldering.safety.network.HeartbeatResponse
import com.eldering.safety.network.IncidentStatus
import com.eldering.safety.network.IncidentSummaryDto
import com.eldering.safety.network.SettingsDto
import com.eldering.safety.network.SharingState
import java.time.Instant
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Sends my location heartbeat while sharing is on (SPEC) and hands the response's `pending_alerts` — family
 * incidents I can see and have not acknowledged — to [onPendingAlerts], which raises the family-alert
 * notification. Uploads are skipped while signed out.
 *
 * The sharing state comes from the cached `/v1/me` ([settings]); it is refreshed through [refreshSettings] when
 * unknown or older than [settingsMaxAgeMs], so a change made on another device is picked up eventually and one
 * made in this app's Sharing screen immediately (shared MeStore).
 */
class HeartbeatController(
    private val signedIn: suspend () -> Boolean,
    private val settings: () -> SettingsDto?,
    private val refreshSettings: suspend () -> ApiResult<*>,
    private val send: suspend (point: TrailPoint, batteryPct: Int?) -> ApiResult<HeartbeatResponse>,
    private val battery: () -> Int?,
    private val onPendingAlerts: (List<IncidentSummaryDto>) -> Unit,
    private val time: TimeSource = TimeSource.System,
    private val policy: HeartbeatPolicy = HeartbeatPolicy(),
    private val settingsMaxAgeMs: Long = 30 * 60_000L,
    private val settingsRetryMs: Long = 5 * 60_000L,
) {
    private val mutex = Mutex()
    private var settingsLoadedAtMs = Long.MIN_VALUE
    private var settingsTriedAtMs = Long.MIN_VALUE

    @Volatile
    var last: HeartbeatPolicy.Last? = null
        private set

    /** Human-readable outcome of the last attempt (debug status). */
    @Volatile
    var lastOutcome: String = "none yet"
        private set

    /** Marks the cached settings as fresh (e.g. after the UI loaded `/v1/me`). */
    fun onSettingsLoaded() {
        settingsLoadedAtMs = time.nowMillis()
    }

    /** Sends a heartbeat with [point] if the policy says one is due (or [force]). Returns true if one was sent. */
    suspend fun maybeSend(point: TrailPoint?, mode: Mode, batterySaver: Boolean, force: Boolean = false): Boolean = mutex.withLock {
        val now = time.nowMillis()
        if (!signedIn()) {
            lastOutcome = "skipped: signed out"
            return false
        }
        refreshSettingsIfStale(now)
        val sharingOn = isSharingOn(settings(), now)
        if (sharingOn != true) {
            lastOutcome = "skipped: sharing ${if (sharingOn == null) "unknown" else "not on"}"
            return false
        }
        if (point == null) {
            lastOutcome = "skipped: no location yet"
            return false
        }
        val previous = last
        val distance = previous?.let { Geo.distanceM(it.lat, it.lng, point.lat, point.lng) }
        if (!force && !policy.shouldSend(now, mode, signedIn = true, sharingOn = true, batterySaver, previous, distance)) return false

        when (val result = send(point.copy(activity = point.activity ?: mode.toActivity()), battery())) {
            is ApiResult.Success -> {
                last = HeartbeatPolicy.Last(now, point.lat, point.lng)
                val pending = result.value.pendingAlerts.filter { !it.isMine && it.status == IncidentStatus.ACTIVE }
                lastOutcome = "ok at ${Instant.ofEpochMilli(now)} pending_alerts=${result.value.pendingAlerts.size}"
                if (pending.isNotEmpty()) onPendingAlerts(pending)
                true
            }
            is ApiResult.Failure -> {
                // Count a failed attempt as "sent" for pacing, so a dead network is not hammered every callback.
                last = HeartbeatPolicy.Last(now, point.lat, point.lng)
                lastOutcome = "failed at ${Instant.ofEpochMilli(now)}: ${describe(result.error)}"
                false
            }
        }
    }

    private suspend fun refreshSettingsIfStale(now: Long) {
        val fresh = settings() != null && settingsLoadedAtMs != Long.MIN_VALUE && now - settingsLoadedAtMs < settingsMaxAgeMs
        if (fresh) return
        if (settings() != null && settingsLoadedAtMs == Long.MIN_VALUE) {
            // Loaded by the UI before we started watching: trust it for one max-age period.
            settingsLoadedAtMs = now
            return
        }
        if (settingsTriedAtMs != Long.MIN_VALUE && now - settingsTriedAtMs < settingsRetryMs) return
        settingsTriedAtMs = now
        if (refreshSettings() is ApiResult.Success) settingsLoadedAtMs = now
    }

    private fun describe(e: ApiError): String = when (e) {
        is ApiError.Http -> "HTTP ${e.status} ${e.code}"
        else -> e.javaClass.simpleName
    }

    companion object {
        /** True if live sharing is on now (a timed pause that has ended counts as on); null when unknown. */
        fun isSharingOn(settings: SettingsDto?, nowMs: Long): Boolean? {
            settings ?: return null
            if (settings.effectiveSharingState == SharingState.ON) return true
            val until = settings.pausedUntil
            return settings.sharingState == SharingState.PAUSED && until != null && until.toEpochMilli() <= nowMs
        }
    }
}
