package com.eldering.safety.alert

import android.util.Log
import com.eldering.safety.core.TimeSource
import com.eldering.safety.core.catchingNonCancellation
import com.eldering.safety.data.db.EmergencyContact
import com.eldering.safety.data.db.OwnIncidentStatus
import com.eldering.safety.data.repository.IncidentDetails
import com.eldering.safety.data.repository.IncidentDraft
import com.eldering.safety.data.repository.IncidentRepository
import com.eldering.safety.data.trail.TrailDownsampler
import com.eldering.safety.data.trail.TrailPoint
import com.eldering.safety.data.trail.TrailStore
import com.eldering.safety.network.ApiError
import com.eldering.safety.network.Resolution
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject

/**
 * Sends an alert (SPEC: IncidentSender):
 * 1. Trail: the frozen pre-event hour plus everything recorded since, downsampled ([Config.maxTrailPoints]).
 * 2. Landmarks: Geocoder names for ~10 key points ([KeyPoints]) within [Config.landmarkBudgetMs]; skipped offline.
 * 3. `POST /v1/incidents` through the outbox ([IncidentRepository.report], keyed by [ActiveAlert.incidentKey]):
 *    stored first, sent now, retried by WorkManager if offline. Skipped when signed out.
 * 4. SMS to the device's emergency contacts with the share link if the server answered within
 *    [Config.shareUrlWaitMs], and always the raw coordinates + a maps link (offline fallback). SMS never waits
 *    for a dead server longer than that, and none goes out once the user said they are OK
 *    ([DispatchHooks.stillWanted]).
 * 5. Live trail updates every [Config.liveIntervalMs] for [Config.liveDurationMs] (also nudged by location
 *    callbacks through [pokeLive], since timers stall while the CPU sleeps), until resolved.
 *
 * The upload runs on [scope] so a caller timing out never cancels it half-way.
 */
class IncidentSender(
    private val trailStore: TrailStore,
    private val incidents: IncidentRepository,
    private val recipients: suspend () -> List<EmergencyContact>,
    private val sms: SmsSender,
    private val landmarks: LandmarkResolver,
    private val isOnline: () -> Boolean,
    private val senderName: suspend () -> String?,
    private val scope: CoroutineScope,
    private val time: TimeSource = TimeSource.System,
    private val config: Config = Config(),
) : AlertDispatcher {

    data class Config(
        val liveIntervalMs: Long = 15_000,
        val liveDurationMs: Long = 60 * 60_000,
        val shareUrlWaitMs: Long = 10_000,
        val landmarkBudgetMs: Long = 5_000,
        val maxLandmarks: Int = 10,
        val maxTrailPoints: Int = TrailStore.INCIDENT_MAX_POINTS,
    ) {
        init {
            require(liveIntervalMs > 0 && liveDurationMs >= 0 && maxTrailPoints >= 2)
        }
    }

    private class LiveSession(val key: String, var lastPointMs: Long, val endsAtMs: Long) {
        val mutex = Mutex()
        var lastPushAtMs: Long = Long.MIN_VALUE
        @Volatile var stoppedByResolve = false
        var job: Job? = null
    }

    private val live = ConcurrentHashMap<String, LiveSession>()

    /** Phone numbers that got the alert SMS, per incident key (for the "they are OK" follow-up). Memory only. */
    private val texted = ConcurrentHashMap<String, List<String>>()

    /** Incident keys with live updates running (debug status). */
    val liveIncidents: Set<String> get() = live.keys.toSet()

    override suspend fun dispatch(alert: ActiveAlert, frozenTrail: List<TrailPoint>, hooks: DispatchHooks): DispatchResult {
        val now = time.nowMillis()
        val after = catchingNonCancellation { trailStore.points(alert.detectedAtMs + 1, now) } ?: emptyList()
        val merged = (frozenTrail + after).distinctBy { it.recordedAtMs }.sortedBy { it.recordedAtMs }
        val trail = TrailDownsampler.downsample(merged, config.maxTrailPoints)
        val incidentPoint = trail.lastOrNull { it.recordedAtMs <= alert.detectedAtMs } ?: trail.firstOrNull()
        val latest = trail.lastOrNull()

        val contacts = if (hooks.smsDone != null) emptyList() else catchingNonCancellation { recipients() } ?: emptyList()
        val names = if (isOnline() && hooks.stillWanted()) resolveLandmarks(trail, alert.detectedAtMs) else emptyMap()
        val draft = IncidentDraft(
            kind = alert.kind,
            detectedAtMs = alert.detectedAtMs,
            lat = incidentPoint?.lat,
            lng = incidentPoint?.lng,
            details = details(alert, if (hooks.smsDone != null) alert.emergencyContactCount else contacts.size),
            trail = trail.mapIndexed { i, p -> p.toDto(names[i]) },
        )

        // Upload in the background scope: the SMS must not wait for a slow server beyond shareUrlWaitMs.
        val report = scope.async {
            try {
                incidents.report(draft, alert.incidentKey)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Incident upload failed unexpectedly", e)
                IncidentRepository.ReportResult.Rejected(DispatchResult.LOCAL_FAILURE_KEY, ApiError.Unexpected(e))
            }
        }
        val smsOutcome = hooks.smsDone ?: run {
            val early = if (hooks.stillWanted()) withTimeoutOrNull(config.shareUrlWaitMs) { report.await() } else null
            val shareUrl = (early as? IncidentRepository.ReportResult.Sent)?.shareUrl
            val position = latest ?: incidentPoint
            val text = SmsText.build(
                name = catchingNonCancellation { senderName() },
                kind = alert.kind,
                detectedAtMs = alert.detectedAtMs,
                lat = position?.lat,
                lng = position?.lng,
                accuracyM = position?.accuracyM,
                shareUrl = shareUrl,
            )
            sendSms(alert.incidentKey, contacts, text, hooks.stillWanted).also(hooks.onSmsDone)
        }
        val result = report.await()
        Log.i(TAG, "Alert ${alert.kind}: ${trail.size} trail points, ${names.size} landmarks, upload=${result.javaClass.simpleName}, sms=$smsOutcome")

        val key = (result as? IncidentRepository.ReportResult.Sent)?.incidentKey
            ?: (result as? IncidentRepository.ReportResult.Queued)?.incidentKey
        if (key != null) {
            startLive(key, sinceMs = maxOf(latest?.recordedAtMs ?: now, alert.detectedAtMs), endsAtMs = time.nowMillis() + config.liveDurationMs, hooks.onLiveEnded)
        }
        val recipientCount = if (hooks.smsDone != null) alert.emergencyContactCount else contacts.size
        return DispatchResult(result, smsOutcome, recipientCount)
    }

    override suspend fun resolve(incidentKey: String, resolution: Resolution) {
        live.remove(incidentKey)?.let { session ->
            session.stoppedByResolve = true
            session.job?.cancel()
        }
        incidents.resolveOwn(incidentKey, resolution)
    }

    override suspend fun resumeLive(
        alert: ActiveAlert,
        liveEndsAtMs: Long,
        onLiveEnded: (String) -> Unit,
    ): IncidentRepository.ReportResult? {
        val key = alert.incidentKey
        val own = catchingNonCancellation { incidents.ownIncident(key) } ?: return null
        if (own.status != OwnIncidentStatus.QUEUED && own.status != OwnIncidentStatus.ACTIVE) return null
        if (time.nowMillis() >= liveEndsAtMs) return null
        // Points pushed before the restart are sent again; the server ignores duplicates (same recorded_at).
        startLive(key, sinceMs = alert.detectedAtMs, endsAtMs = liveEndsAtMs, onLiveEnded)
        val serverId = own.serverId
        val shareUrl = own.shareUrl
        val expires = own.shareExpiresAtMs
        return if (serverId != null && shareUrl != null && expires != null) {
            IncidentRepository.ReportResult.Sent(key, serverId, shareUrl, expires)
        } else {
            IncidentRepository.ReportResult.Queued(key, null)
        }
    }

    override suspend fun notifySafe(alert: ActiveAlert, smsWentOut: Boolean) {
        val phones = texted.remove(alert.incidentKey)
            ?: if (smsWentOut) catchingNonCancellation { recipients() }.orEmpty().map { it.phone } else emptyList()
        if (phones.isEmpty()) return
        val text = SmsText.safe(name = catchingNonCancellation { senderName() }, detectedAtMs = alert.detectedAtMs)
        phones.forEach { phone ->
            try {
                sms.send(phone, text)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Follow-up SMS failed: ${e.javaClass.simpleName}")
            }
        }
        Log.i(TAG, "Told ${phones.size} emergency contact(s) that the alert is over")
    }

    /** A new location fix arrived: push live points now if the last push is at least one interval old. */
    fun pokeLive() {
        val now = time.nowMillis()
        live.values.forEach { session ->
            if (session.lastPushAtMs == Long.MIN_VALUE || now - session.lastPushAtMs >= config.liveIntervalMs) {
                scope.launch { push(session, onlyIfIdle = true) }
            }
        }
    }

    private fun startLive(key: String, sinceMs: Long, endsAtMs: Long, onLiveEnded: (String) -> Unit) {
        val session = LiveSession(key, sinceMs, endsAtMs)
        live.put(key, session)?.job?.cancel()
        session.job = scope.launch {
            var closedElsewhere = false
            try {
                val ticks = ((endsAtMs - time.nowMillis()).coerceAtLeast(0)) / config.liveIntervalMs
                var tick = 0L
                while (tick < ticks && time.nowMillis() < session.endsAtMs) {
                    delay(config.liveIntervalMs)
                    tick++
                    if (push(session, onlyIfIdle = false) == false) {
                        closedElsewhere = true
                        break
                    }
                }
            } finally {
                live.remove(key, session)
                if (!session.stoppedByResolve) {
                    Log.i(TAG, "Live updates for $key ended (${if (closedElsewhere) "incident closed" else "time is up"})")
                    onLiveEnded(key)
                }
            }
        }
    }

    /** Appends the points recorded since the last push. Returns false if the incident is closed, null if skipped. */
    private suspend fun push(session: LiveSession, onlyIfIdle: Boolean): Boolean? {
        if (onlyIfIdle && !session.mutex.tryLock()) return null
        if (!onlyIfIdle) session.mutex.lock()
        try {
            val now = time.nowMillis()
            session.lastPushAtMs = now
            val points = trailStore.points(session.lastPointMs + 1, now)
            val open = incidents.appendTrail(session.key, points.map { it.toDto() })
            if (open && points.isNotEmpty()) session.lastPointMs = points.last().recordedAtMs
            return open
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Live update failed: ${e.javaClass.simpleName}")
            return null
        } finally {
            session.mutex.unlock()
        }
    }

    private suspend fun resolveLandmarks(trail: List<TrailPoint>, incidentAtMs: Long): Map<Int, String> {
        if (trail.isEmpty()) return emptyMap()
        val keys = KeyPoints.select(trail, incidentAtMs, config.maxLandmarks)
        val names = ConcurrentHashMap<Int, String>()
        withTimeoutOrNull(config.landmarkBudgetMs) {
            coroutineScope {
                keys.forEach { i ->
                    launch {
                        val name = try {
                            landmarks.landmark(trail[i].lat, trail[i].lng)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            null
                        }
                        name?.trim()?.takeIf { it.isNotEmpty() }?.let { names[i] = it.take(MAX_LANDMARK_LENGTH) }
                    }
                }
            }
        }
        return names.toMap()
    }

    /** Texts [contacts] one by one, stopping as soon as [stillWanted] turns false ("I'm OK" while sending). */
    private suspend fun sendSms(incidentKey: String, contacts: List<EmergencyContact>, text: String, stillWanted: () -> Boolean): SmsOutcome {
        if (contacts.isEmpty()) return SmsOutcome.NO_CONTACTS
        val results = ArrayList<SmsResult>(contacts.size)
        val reached = ArrayList<String>(contacts.size)
        for (contact in contacts) {
            if (!stillWanted()) break
            val r = try {
                sms.send(contact.phone, text)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                SmsResult.Failed(e.javaClass.simpleName)
            }
            results += r
            if (r is SmsResult.Queued || r is SmsResult.DryRun) reached += contact.phone
        }
        if (reached.isNotEmpty()) texted[incidentKey] = reached
        if (results.isEmpty()) {
            Log.i(TAG, "SMS skipped: the user said they are OK before it went out")
            return SmsOutcome.CANCELLED
        }
        return when {
            results.any { it is SmsResult.Queued } -> SmsOutcome.SENT
            results.any { it is SmsResult.DryRun } -> SmsOutcome.DRY_RUN
            results.all { it is SmsResult.NoPermission } -> SmsOutcome.NO_PERMISSION
            results.all { it is SmsResult.Unsupported } -> SmsOutcome.NOT_SUPPORTED
            else -> SmsOutcome.FAILED
        }
    }

    private fun details(alert: ActiveAlert, contacts: Int): JsonObject {
        val base = alert.event?.let { IncidentDetails.fromDetection(it, alert.mode, Instant.ofEpochMilli(alert.detectedAtMs)) }
            ?: IncidentDetails.sos()
        return IncidentDetails.withRecipients(base, alert.familyRecipients, contacts)
    }

    private companion object {
        const val TAG = "IncidentSender"
        const val MAX_LANDMARK_LENGTH = 200
    }
}
