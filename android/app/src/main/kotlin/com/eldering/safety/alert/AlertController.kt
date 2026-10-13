package com.eldering.safety.alert

import android.util.Log
import com.eldering.detection.DetectionEvent
import com.eldering.detection.Mode
import com.eldering.safety.core.TimeSource
import com.eldering.safety.data.db.DetectionOutcome
import com.eldering.safety.data.db.FeedbackLabel
import com.eldering.safety.data.repository.IncidentDetails
import com.eldering.safety.data.repository.IncidentRepository
import com.eldering.safety.data.trail.TrailPoint
import com.eldering.safety.network.ApiError
import com.eldering.safety.network.IncidentKind
import com.eldering.safety.network.Resolution
import com.eldering.safety.ui.alert.AlertFeedbackSink
import com.eldering.safety.ui.alert.DetectionFeedbackSink
import com.eldering.safety.ui.alert.FeedbackChoice
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.min
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** One accident (or SOS) being handled by [AlertController]. */
data class ActiveAlert(
    /** Local sequence number (process lifetime). */
    val id: Long,
    val kind: IncidentKind,
    /** Wall-clock time of the impact (never in the future). */
    val detectedAtMs: Long,
    /** Movement mode at detection. */
    val mode: Mode?,
    /** Countdown length; 0 for an SOS (holding the button was the confirmation). */
    val countdownMs: Long,
    /** When the countdown ends and the alert goes out; null for an SOS. */
    val countdownEndsAtMs: Long?,
    /** The detector's event; null for an SOS. */
    val event: DetectionEvent?,
    /** Raw sensor window around the event (JSON), stored with the false-alarm feedback. */
    val windowJson: String? = null,
    /** Emergency contacts with SMS enabled when the alert started. */
    val emergencyContactCount: Int = 0,
) {
    val isSos: Boolean get() = kind == IncidentKind.SOS
}

/** What [AlertDispatcher.dispatch] achieved. */
data class DispatchResult(
    val report: IncidentRepository.ReportResult,
    val sms: SmsOutcome,
    /** SMS recipients (0 when there are no contacts). */
    val smsRecipients: Int = 0,
) {
    val incidentKey: String?
        get() = when (val r = report) {
            is IncidentRepository.ReportResult.Sent -> r.incidentKey
            is IncidentRepository.ReportResult.Queued -> r.incidentKey
            is IncidentRepository.ReportResult.Rejected -> r.incidentKey.takeIf { it != LOCAL_FAILURE_KEY }
            IncidentRepository.ReportResult.NotSignedIn -> null
        }

    companion object {
        internal const val LOCAL_FAILURE_KEY = "local-failure"
    }
}

sealed interface AlertState {
    val alert: ActiveAlert? get() = null

    /** Nothing happened yet in this process. */
    data object Idle : AlertState

    data class Counting(override val alert: ActiveAlert) : AlertState

    /** The countdown ended (or "Send help now"/SOS): upload and SMS in flight. */
    data class Sending(override val alert: ActiveAlert, val resolveWhenSent: Boolean = false) : AlertState

    /** Family and contacts alerted; live trail updates run. */
    data class Sent(override val alert: ActiveAlert, val result: DispatchResult) : AlertState

    data class Finished(override val alert: ActiveAlert, val ending: Ending, val result: DispatchResult? = null) : AlertState

    val isActive: Boolean get() = this is Counting || this is Sending || this is Sent
}

enum class Ending {
    /** "I'm OK" during the countdown: nothing was sent. */
    CANCELLED,

    /** "I'm safe now" after sending: the incident was resolved as user_ok. */
    RESOLVED,

    /** Live sharing ended without the user: resolved elsewhere (family marked it handled) or 60 min passed. */
    ENDED,
}

// --- Ports (Android implementations in AndroidAlertPorts.kt; fakes in tests) -------------------------------------

/** Uploads the incident, texts the emergency contacts and runs the live trail updates (see [IncidentSender]). */
interface AlertDispatcher {
    /**
     * Must not throw. [onLiveEnded] is called with the incident key when live updates stop by themselves (the
     * incident was closed elsewhere or the 60 minutes are over).
     */
    suspend fun dispatch(alert: ActiveAlert, frozenTrail: List<TrailPoint>, onLiveEnded: (String) -> Unit): DispatchResult

    /** Ends live updates and resolves the incident on the server (queued if offline). */
    suspend fun resolve(incidentKey: String, resolution: Resolution)
}

/** The countdown screen, notifications, alarm sound and dialer. Called from any thread. */
interface AlertUi {
    fun showCountdown(alert: ActiveAlert)
    fun showSent(alert: ActiveAlert)
    fun dismiss(alert: ActiveAlert)
    fun startAlarm()
    fun stopAlarm()

    /** ACTION_DIAL with the emergency number; never places the call. */
    fun dialEmergency()
}

/** The on-device detection log (false-alarm feedback with the event window). */
interface DetectionJournal {
    suspend fun record(alert: ActiveAlert): Long?
    suspend fun setOutcome(eventId: Long, outcome: DetectionOutcome, incidentKey: String?)
    suspend fun label(eventId: Long, label: FeedbackLabel?, note: String?)
}

/** Copies the trail up to an incident time out of the ring buffer (SPEC: "freeze trail"). */
fun interface TrailFreezer {
    suspend fun freeze(atMs: Long): List<TrailPoint>
}

/** A bounded partial wakelock ("eldering:Incident"): timers and sockets stall without it while the screen is off. */
interface WakeHold {
    fun acquire(timeoutMs: Long)
    fun release()
}

/**
 * The accident state machine (SPEC: AlertController — freeze trail → full-screen countdown → IncidentSender):
 *
 * ```
 * Idle/Finished ──detection──▶ Counting ──timer / Send help now──▶ Sending ──▶ Sent ──I'm safe──▶ Finished(RESOLVED)
 *                                 │                                  │            └──closed elsewhere / 60 min──▶ Finished(ENDED)
 *                                 └──I'm OK──▶ Finished(CANCELLED)     └──I'm OK──▶ (resolved as soon as it is sent)
 * Idle/Finished ──SOS──▶ Sending (no countdown)
 * ```
 *
 * The countdown is owned here (not by the screen) and timed on [scope] while holding [wake]; the countdown
 * screen, the notification actions and the heads-up only signal this class through [AlertActions], whose
 * functions are idempotent. A detection while an alert is active is logged as suppressed.
 * Thread-safe: transitions are atomic under a lock; side effects run on [scope].
 */
class AlertController(
    private val scope: CoroutineScope,
    private val ui: AlertUi,
    private val dispatcher: AlertDispatcher,
    private val journal: DetectionJournal,
    private val freezer: TrailFreezer,
    private val contactCount: suspend () -> Int,
    private val wake: WakeHold,
    private val time: TimeSource = TimeSource.System,
    /** `elapsedRealtimeNanos` (sensor time) → wall-clock ms. */
    private val wallFromElapsedNanos: (Long) -> Long,
    /**
     * A sent alert ends by itself after this long even if nobody resolves it (e.g. signed out: nothing to resolve
     * on a server), so detection is never suppressed indefinitely. Normally the live updates end first.
     */
    private val sentMaxMs: Long = SENT_MAX_MS,
) : AlertActions, AlertFeedbackSink {

    private val lock = Any()
    private val mutableState = MutableStateFlow<AlertState>(AlertState.Idle)
    val state: StateFlow<AlertState> = mutableState.asStateFlow()

    private var nextId = 1L
    private var timerJob: Job? = null
    private val frozen = ConcurrentHashMap<Long, Deferred<List<TrailPoint>>>()
    private val eventIds = ConcurrentHashMap<Long, Deferred<Long?>>()
    private val results = ConcurrentHashMap<Long, CompletableDeferred<DispatchResult>>()

    /** The detector found an accident (any thread). Starts the countdown unless an alert is already active. */
    fun onDetection(event: DetectionEvent, mode: Mode?, windowJson: String? = null) {
        scope.launch { handleDetection(event, mode, windowJson) }
    }

    private suspend fun handleDetection(event: DetectionEvent, mode: Mode?, windowJson: String?) {
        val now = time.nowMillis()
        val kind = IncidentDetails.kindOf(event.kind)
        val countdown = countdownMs(kind, mode)
        val contacts = runCatching { contactCount() }.getOrDefault(0)
        val candidate = ActiveAlert(
            id = 0,
            kind = kind,
            detectedAtMs = min(now, wallFromElapsedNanos(event.triggeredAtNanos)),
            mode = mode,
            countdownMs = countdown,
            countdownEndsAtMs = now + countdown,
            event = event,
            windowJson = windowJson,
            emergencyContactCount = contacts,
        )
        val alert = synchronized(lock) {
            if (mutableState.value.isActive) null else candidate.copy(id = nextId++).also { mutableState.value = AlertState.Counting(it) }
        }
        if (alert == null) {
            Log.i(TAG, "Detection ${event.kind} ignored: an alert is already active")
            val id = runCatching { journal.record(candidate) }.getOrNull()
            if (id != null) journal.setOutcome(id, DetectionOutcome.SUPPRESSED, null)
            return
        }
        Log.i(TAG, "Countdown started: ${alert.kind} (${countdown / 1000} s)")
        wake.acquire(WAKE_TIMEOUT_MS)
        frozen[alert.id] = scope.async { runCatching { freezer.freeze(alert.detectedAtMs) }.getOrDefault(emptyList()) }
        eventIds[alert.id] = scope.async { runCatching { journal.record(alert) }.getOrNull() }
        results[alert.id] = CompletableDeferred()
        ui.showCountdown(alert)
        ui.startAlarm()
        timerJob = scope.launch {
            delay(countdown)
            expire(alert.id)
        }
    }

    private fun expire(alertId: Long) {
        val alert = synchronized(lock) {
            val s = mutableState.value as? AlertState.Counting ?: return
            if (s.alert.id != alertId) return
            mutableState.value = AlertState.Sending(s.alert)
            s.alert
        }
        Log.i(TAG, "Countdown expired: sending")
        scope.launch { dispatch(alert) }
    }

    /** "Send help now" (or the screen's backstop when its countdown hits zero). No-op unless counting. */
    override fun sendNow() {
        val alert = synchronized(lock) {
            val s = mutableState.value as? AlertState.Counting ?: return
            mutableState.value = AlertState.Sending(s.alert)
            s.alert
        }
        timerJob?.cancel()
        Log.i(TAG, "Send help now")
        scope.launch { dispatch(alert) }
    }

    /** "I'm OK" while counting (cancel) or "I'm safe now" after sending (resolve as user_ok). Idempotent. */
    override fun imOk() {
        var cancel: ActiveAlert? = null
        var resolve: AlertState.Sent? = null
        synchronized(lock) {
            when (val s = mutableState.value) {
                is AlertState.Counting -> {
                    mutableState.value = AlertState.Finished(s.alert, Ending.CANCELLED)
                    cancel = s.alert
                }
                is AlertState.Sending -> if (!s.resolveWhenSent) mutableState.value = s.copy(resolveWhenSent = true)
                is AlertState.Sent -> {
                    mutableState.value = AlertState.Finished(s.alert, Ending.RESOLVED, s.result)
                    resolve = s
                }
                AlertState.Idle, is AlertState.Finished -> Unit
            }
        }
        cancel?.let { alert ->
            Log.i(TAG, "I'm OK: countdown cancelled, nothing sent")
            timerJob?.cancel()
            ui.stopAlarm()
            ui.dismiss(alert)
            wake.release()
            results.remove(alert.id)?.cancel()
            scope.launch {
                val id = eventIds[alert.id]?.await() ?: return@launch
                journal.setOutcome(id, DetectionOutcome.CANCELLED, null)
                // Provisional verdict; the "What happened?" answer on the screen replaces it.
                journal.label(id, FeedbackLabel.FALSE_ALARM, NOTE_IM_OK)
            }
        }
        resolve?.let { sent ->
            Log.i(TAG, "I'm safe now: resolving the incident")
            ui.stopAlarm()
            ui.dismiss(sent.alert)
            wake.release()
            val key = sent.result.incidentKey ?: return@let
            scope.launch { dispatcher.resolve(key, Resolution.USER_OK) }
        }
    }

    override fun callEmergency() = ui.dialEmergency()

    /** Stores the "What happened?" answer on the latest detector alert. */
    override suspend fun record(choice: FeedbackChoice) {
        val alert = mutableState.value.alert ?: return
        val id = eventIds[alert.id]?.await() ?: return
        journal.label(id, DetectionFeedbackSink.labelFor(choice), choice.name.lowercase())
    }

    /**
     * Hold-to-SOS: alerts immediately through the same dispatcher (upload, SMS, live updates). If a countdown is
     * running it is sent now instead; if an alert was already sent, that result is returned.
     */
    suspend fun triggerSos(): SosOutcome {
        var start: ActiveAlert? = null
        var awaitId: Long? = null
        var already: DispatchResult? = null
        synchronized(lock) {
            when (val s = mutableState.value) {
                is AlertState.Counting -> awaitId = s.alert.id
                is AlertState.Sending -> awaitId = s.alert.id
                is AlertState.Sent -> already = s.result
                AlertState.Idle, is AlertState.Finished -> {
                    val now = time.nowMillis()
                    start = ActiveAlert(
                        id = nextId++, kind = IncidentKind.SOS, detectedAtMs = now, mode = null,
                        countdownMs = 0, countdownEndsAtMs = null, event = null,
                    ).also {
                        mutableState.value = AlertState.Sending(it)
                        results[it.id] = CompletableDeferred()
                    }
                }
            }
        }
        already?.let { return SosOutcome(it.report, it.sms) }
        awaitId?.let { id ->
            sendNow()
            val r = results[id]?.await() ?: return SosOutcome(IncidentRepository.ReportResult.NotSignedIn, SmsOutcome.NOT_SUPPORTED)
            return SosOutcome(r.report, r.sms)
        }
        val alert = start!!
        Log.i(TAG, "SOS: sending")
        wake.acquire(WAKE_TIMEOUT_MS)
        val count = runCatching { contactCount() }.getOrDefault(0)
        val withContacts = alert.copy(emergencyContactCount = count)
        synchronized(lock) {
            val s = mutableState.value
            if (s is AlertState.Sending && s.alert.id == alert.id) mutableState.value = s.copy(alert = withContacts)
        }
        val r = dispatch(withContacts)
        return SosOutcome(r.report, r.sms)
    }

    /** Runs the dispatcher for [alert] (state is already [AlertState.Sending]). */
    private suspend fun dispatch(alert: ActiveAlert): DispatchResult {
        ui.stopAlarm()
        ui.showSent(alert)
        val trail = frozen[alert.id]?.await() ?: runCatching { freezer.freeze(alert.detectedAtMs) }.getOrDefault(emptyList())
        val result = try {
            dispatcher.dispatch(alert, trail) { key -> onLiveEnded(alert.id, key) }
        } catch (e: Exception) {
            Log.e(TAG, "Dispatch failed unexpectedly", e)
            DispatchResult(
                IncidentRepository.ReportResult.Rejected(DispatchResult.LOCAL_FAILURE_KEY, ApiError.Unexpected(e)),
                SmsOutcome.FAILED,
            )
        }
        Log.i(TAG, "Sent: report=${result.report.javaClass.simpleName} sms=${result.sms} recipients=${result.smsRecipients}")
        var resolveNow = false
        synchronized(lock) {
            val s = mutableState.value
            if (s is AlertState.Sending && s.alert.id == alert.id) {
                resolveNow = s.resolveWhenSent
                mutableState.value = if (resolveNow) {
                    AlertState.Finished(s.alert, Ending.RESOLVED, result)
                } else {
                    AlertState.Sent(s.alert, result)
                }
            }
        }
        wake.release()
        results[alert.id]?.complete(result)
        if (!resolveNow) {
            scope.launch {
                delay(sentMaxMs)
                endSent(alert.id, "no response within ${sentMaxMs / 60_000} min")
            }
        }
        eventIds[alert.id]?.await()?.let { journal.setOutcome(it, DetectionOutcome.REPORTED, result.incidentKey) }
        if (resolveNow) {
            ui.dismiss(alert)
            result.incidentKey?.let { dispatcher.resolve(it, Resolution.USER_OK) }
        }
        return result
    }

    private fun onLiveEnded(alertId: Long, incidentKey: String) = endSent(alertId, "live updates ended for $incidentKey")

    private fun endSent(alertId: Long, why: String) {
        val ended = synchronized(lock) {
            val s = mutableState.value as? AlertState.Sent ?: return
            if (s.alert.id != alertId) return
            mutableState.value = AlertState.Finished(s.alert, Ending.ENDED, s.result)
            s.alert
        }
        Log.i(TAG, "Alert ended: $why")
        ui.dismiss(ended)
    }

    companion object {
        private const val TAG = "AlertController"

        /** SPEC: 15 s walking/fall, 20 s vehicle. */
        const val COUNTDOWN_WALKING_MS = 15_000L
        const val COUNTDOWN_VEHICLE_MS = 20_000L

        /** Countdown + upload + SMS; released as soon as the alert is sent or cancelled. */
        const val WAKE_TIMEOUT_MS = 5 * 60_000L

        const val NOTE_IM_OK = "im_ok"

        /** Live sharing lasts 60 min; a sent alert is over a minute after that at the latest. */
        const val SENT_MAX_MS = 61 * 60_000L

        fun countdownMs(kind: IncidentKind, mode: Mode?): Long =
            if (kind == IncidentKind.VEHICLE_CRASH || mode == Mode.VEHICLE) COUNTDOWN_VEHICLE_MS else COUNTDOWN_WALKING_MS
    }
}

/** Hold-to-SOS through the alert pipeline (upload + SMS + live updates); replaces [ServerOnlySosTrigger]. */
class ControllerSosTrigger(private val controller: AlertController) : SosTrigger {
    override suspend fun triggerSos(): SosOutcome = controller.triggerSos()
}
