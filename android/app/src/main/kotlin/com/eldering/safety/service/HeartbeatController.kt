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
 * Seeing a family member's incident does not depend on my own sharing (SPEC RBAC): when no heartbeat goes out
 * (my sharing is off or paused, there is no fix yet, or a still phone's heartbeat is not due) `GET
 * /v1/incidents/active` is polled through [pollAlerts] at [HeartbeatPolicy.alertPollIntervalMs] (2 min while
 * still, counted from the last heartbeat or poll), so nobody waits for a 5-min still heartbeat to hear about an
 * accident. Nothing is polled when I am in no family ([hasFamily] false).
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
    /** `GET /v1/incidents/active`; null = no polling (the heartbeat's pending_alerts only). */
    private val pollAlerts: (suspend () -> ApiResult<List<IncidentSummaryDto>>)? = null,
    private val time: TimeSource = TimeSource.System,
    private val policy: HeartbeatPolicy = HeartbeatPolicy(),
    private val settingsMaxAgeMs: Long = 30 * 60_000L,
    private val settingsRetryMs: Long = 5 * 60_000L,
    /** False when `/v1/me` says I am in no family (nobody's alerts to poll); null/true otherwise. */
    private val hasFamily: () -> Boolean? = { null },
) {
    private val mutex = Mutex()
    private var settingsLoadedAtMs = Long.MIN_VALUE
    private var settingsTriedAtMs = Long.MIN_VALUE
    /** Last successful heartbeat (it carries `pending_alerts`) or alert poll. */
    private var lastAlertCheckAtMs = Long.MIN_VALUE

    /** Outcome of the last family-alert poll (debug status). */
    @Volatile
    var lastPollOutcome: String = "none yet"
        private set

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
            pollAlertsIfDue(now, mode, batterySaver)
            return false
        }
        if (point == null) {
            lastOutcome = "skipped: no location yet"
            pollAlertsIfDue(now, mode, batterySaver)
            return false
        }
        val previous = last
        val distance = previous?.let { Geo.distanceM(it.lat, it.lng, point.lat, point.lng) }
        if (!force && !policy.shouldSend(now, mode, signedIn = true, sharingOn = true, batterySaver, previous, distance)) {
            pollAlertsIfDue(now, mode, batterySaver)
            return false
        }

        when (val result = send(point.copy(activity = point.activity ?: mode.toActivity()), battery())) {
            is ApiResult.Success -> {
                last = HeartbeatPolicy.Last(now, point.lat, point.lng)
                lastAlertCheckAtMs = now
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

    /**
     * Family alerts without a heartbeat, at [HeartbeatPolicy.alertPollIntervalMs] since the last heartbeat or poll
     * (failures count too: a dead network is not hammered).
     */
    private suspend fun pollAlertsIfDue(now: Long, mode: Mode, batterySaver: Boolean) {
        val poll = pollAlerts ?: return
        if (hasFamily() == false) return
        val last = lastAlertCheckAtMs
        if (last != Long.MIN_VALUE && now >= last && now - last < policy.alertPollIntervalMs(mode, batterySaver)) return
        lastAlertCheckAtMs = now
        when (val result = poll()) {
            is ApiResult.Success -> {
                val pending = result.value.filter { !it.isMine && it.status == IncidentStatus.ACTIVE && it.myAck == null }
                lastPollOutcome = "ok at ${Instant.ofEpochMilli(now)} active=${result.value.size} pending=${pending.size}"
                if (pending.isNotEmpty()) onPendingAlerts(pending)
            }
            is ApiResult.Failure -> lastPollOutcome = "failed at ${Instant.ofEpochMilli(now)}: ${describe(result.error)}"
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
