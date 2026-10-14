package com.eldering.safety.alert

import android.util.Log
import com.eldering.detection.DetectionEvent
import com.eldering.detection.Mode
import com.eldering.safety.core.TimeSource
import com.eldering.safety.core.catchingNonCancellation
import com.eldering.safety.data.db.DetectionOutcome
import com.eldering.safety.data.db.FeedbackLabel
import com.eldering.safety.data.repository.AlertRecipient
import com.eldering.safety.data.repository.IncidentDetails
import com.eldering.safety.data.repository.IncidentRepository
import com.eldering.safety.data.trail.TrailPoint
import com.eldering.safety.network.ApiError
import com.eldering.safety.network.IncidentKind
import com.eldering.safety.network.Resolution
import com.eldering.safety.ui.alert.AlertFeedbackSink
import com.eldering.safety.ui.alert.DetectionFeedbackSink
import com.eldering.safety.ui.alert.FeedbackChoice
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException
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
    /** Family members who get the alert in the app (last known grants; see AlertRecipientsRepository). */
    val familyRecipients: List<AlertRecipient> = emptyList(),
    /**
     * Stable across process restarts: the outbox incident key ([IncidentRepository.report]) and the key of the
     * [PersistedAlert], so an alert resumed after a restart never creates a second incident.
     */
    val incidentKey: String = UUID.randomUUID().toString(),
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

    /**
     * Live trail updates run for this alert (the incident is on the server or queued for it), and they are what
     * ends it ([AlertDispatcher] reports `onLiveEnded`). Signed out, rejected or failed locally: nothing runs.
     */
    val hasLiveSession: Boolean
        get() = report is IncidentRepository.ReportResult.Sent || report is IncidentRepository.ReportResult.Queued

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

    /**
     * Family and/or contacts alerted. With a live session (see [DispatchResult.hasLiveSession]) the live trail
     * updates run and the alert is still active; without one (signed out, upload refused) the screen still shows
     * the result and "I'm safe now" still texts the contacts, but nothing is suppressed or tracked any more.
     */
    data class Sent(override val alert: ActiveAlert, val result: DispatchResult) : AlertState

    data class Finished(override val alert: ActiveAlert, val ending: Ending, val result: DispatchResult? = null) : AlertState

    /**
     * An alert is in progress: new detections are logged as suppressed, and the service keeps alert-grade
     * location. A [Sent] alert without a live session is not active: nothing is running for it.
     */
    val isActive: Boolean
        get() = this is Counting || this is Sending || (this is Sent && this.result.hasLiveSession)
}

enum class Ending {
    /** "I'm OK" during the countdown: nothing was sent. */
    CANCELLED,

    /** "I'm safe now" after sending: the incident was resolved as user_ok. */
    RESOLVED,

    /** Live sharing ended without the user: resolved elsewhere (family marked it handled) or 60 min passed. */
    ENDED,
}

/** "Sent" or "dry run": the contacts were (or, in a debug build, would have been) texted. */
val SmsOutcome.reachedContacts: Boolean get() = this == SmsOutcome.SENT || this == SmsOutcome.DRY_RUN

// --- Ports (Android implementations in AndroidAlertPorts.kt; fakes in tests) -------------------------------------

/** Hooks [AlertController] hands to [AlertDispatcher.dispatch]. */
class DispatchHooks(
    /**
     * False once the user said they are OK while the alert was being sent. Checked right before each SMS: no
     * "may have fallen" text goes out after "I'm OK".
     */
    val stillWanted: () -> Boolean = { true },
    /** The SMS step already ran for this alert (a send resumed after a restart): do not text again. */
    val smsDone: SmsOutcome? = null,
    /** Called once the SMS step ran, before the upload result is awaited. */
    val onSmsDone: (SmsOutcome) -> Unit = {},
    /** Live updates stopped by themselves (closed elsewhere, or the hour is over). */
    val onLiveEnded: (String) -> Unit = {},
)

/** Uploads the incident, texts the emergency contacts and runs the live trail updates (see [IncidentSender]). */
interface AlertDispatcher {
    /** Must not throw (except cancellation). */
    suspend fun dispatch(alert: ActiveAlert, frozenTrail: List<TrailPoint>, hooks: DispatchHooks): DispatchResult

    /** Ends live updates and resolves the incident on the server (queued if offline). */
    suspend fun resolve(incidentKey: String, resolution: Resolution)

    /**
     * Restarts the live updates of [alert] after a process restart, until [liveEndsAtMs]. Returns the incident's
     * upload state, or null when there is nothing to resume (unknown, closed or the hour is over).
     */
    suspend fun resumeLive(alert: ActiveAlert, liveEndsAtMs: Long, onLiveEnded: (String) -> Unit): IncidentRepository.ReportResult?

    /**
     * "I'm OK" after the contacts were texted: tells them too. [smsWentOut] covers a restart in between (the
     * recipients are then the current contact list).
     */
    suspend fun notifySafe(alert: ActiveAlert, smsWentOut: Boolean)
}

/** The countdown screen, notifications, alarm sound and dialer. Called from any thread; must not block. */
interface AlertUi {
    fun showCountdown(alert: ActiveAlert)
    fun showSent(alert: ActiveAlert)
    fun dismiss(alert: ActiveAlert)
    fun startAlarm()
    fun stopAlarm()

    /** ACTION_DIAL with the emergency number; never places the call. */
    fun dialEmergency()

    /** No alert is running (fresh process): drop an alert notification a previous process left behind. */
    fun clearStale() = Unit
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
 *                                 └──I'm OK──▶ Finished(CANCELLED)     └──I'm OK──▶ (no SMS after this; resolved once out)
 * Idle/Finished ──SOS──▶ Sending (no countdown)
 * ```
 *
 * The countdown is owned here (not by the screen) and timed on [scope] while holding [wake]; the countdown
 * screen, the notification actions and the heads-up only signal this class through [AlertActions], whose
 * functions are idempotent. A detection while an alert is active ([AlertState.isActive]) is logged as suppressed.
 * Every send runs on [scope], never in the caller's coroutine, so leaving a screen cannot cancel it.
 *
 * Every transition is written to [store]; [restore] resumes an alert a dead process left behind.
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
    private val store: AlertStore = AlertStore.None,
    /**
     * A sent alert ends by itself after this long even if nobody resolves it, so detection is never suppressed
     * indefinitely. Normally the live updates end first.
     */
    private val sentMaxMs: Long = SENT_MAX_MS,
    /**
     * Family members who will get the alert in the app. Local and fast (never the network): the countdown must not
     * wait for it.
     */
    private val familyRecipients: () -> List<AlertRecipient> = { emptyList() },
) : AlertActions, AlertFeedbackSink {

    private val lock = Any()
    private val mutableState = MutableStateFlow<AlertState>(AlertState.Idle)
    val state: StateFlow<AlertState> = mutableState.asStateFlow()

    private var nextId = 1L
    private var timerJob: Job? = null
    private var restored = false
    /** Mirror of what [store] holds. Guarded by [lock]. */
    private var persisted: PersistedAlert? = null
    private val frozen = ConcurrentHashMap<Long, Deferred<List<TrailPoint>>>()
    private val eventIds = ConcurrentHashMap<Long, Deferred<Long?>>()
    private val results = ConcurrentHashMap<Long, CompletableDeferred<DispatchResult>>()
    /** Detection-log ids once stored (for the persisted record). */
    private val journalIds = ConcurrentHashMap<Long, Long>()

    /** The detector found an accident (any thread). Starts the countdown unless an alert is already active. */
    fun onDetection(event: DetectionEvent, mode: Mode?, windowJson: String? = null) {
        scope.launch { handleDetection(event, mode, windowJson) }
    }

    private suspend fun handleDetection(event: DetectionEvent, mode: Mode?, windowJson: String?) {
        val now = time.nowMillis()
        val kind = IncidentDetails.kindOf(event.kind)
        val countdown = countdownMs(kind, mode)
        val contacts = catchingNonCancellation { contactCount() } ?: 0
        val family = recipientsNow()
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
            familyRecipients = family,
        )
        val alert = synchronized(lock) {
            if (mutableState.value.isActive) null else candidate.copy(id = nextId++).also { mutableState.value = AlertState.Counting(it) }
        }
        if (alert == null) {
            Log.i(TAG, "Detection ${event.kind} ignored: an alert is already active")
            val id = catchingNonCancellation { journal.record(candidate) }
            if (id != null) journal.setOutcome(id, DetectionOutcome.SUPPRESSED, null)
            return
        }
        Log.i(TAG, "Countdown started: ${alert.kind} (${countdown / 1000} s)")
        eventIds[alert.id] = scope.async {
            catchingNonCancellation { journal.record(alert) }?.also { id ->
                synchronized(lock) {
                    journalIds[alert.id] = id
                    persisted?.takeIf { it.key == alert.incidentKey }?.let { persist(it.copy(eventId = id)) }
                }
            }
        }
        // Screen and alarm first: the countdown never waits for storage.
        startCounting(alert)
        synchronized(lock) {
            // Unless "I'm OK" / "Send help now" already moved on (they persist their own state).
            if ((mutableState.value as? AlertState.Counting)?.alert?.id == alert.id && persisted?.key != alert.incidentKey) {
                persist(PersistedAlert.of(alert, PersistedAlert.Phase.COUNTING).copy(eventId = journalIds[alert.id]))
            }
        }
    }

    /** Side effects of entering [AlertState.Counting] (state already set). */
    private fun startCounting(alert: ActiveAlert) {
        wake.acquire(WAKE_TIMEOUT_MS)
        frozen[alert.id] = scope.async { catchingNonCancellation { freezer.freeze(alert.detectedAtMs) } ?: emptyList() }
        results[alert.id] = CompletableDeferred()
        // The timer first: it must never wait for the screen, the notification or the alarm sound to start.
        val delayMs = ((alert.countdownEndsAtMs ?: time.nowMillis()) - time.nowMillis()).coerceAtLeast(0)
        timerJob = scope.launch {
            delay(delayMs)
            expire(alert.id)
        }
        ui.showCountdown(alert)
        ui.startAlarm()
    }

    private fun expire(alertId: Long) {
        val alert = synchronized(lock) {
            val s = mutableState.value as? AlertState.Counting ?: return
            if (s.alert.id != alertId) return
            toSending(s.alert)
            s.alert
        }
        Log.i(TAG, "Countdown expired: sending")
        scope.launch { dispatch(alert) }
    }

    /** "Send help now" (or the screen's backstop when its countdown hits zero). No-op unless counting. */
    override fun sendNow() {
        val alert = synchronized(lock) {
            val s = mutableState.value as? AlertState.Counting ?: return
            toSending(s.alert)
            s.alert
        }
        timerJob?.cancel()
        Log.i(TAG, "Send help now")
        scope.launch { dispatch(alert) }
    }

    /** Under [lock]. */
    private fun toSending(alert: ActiveAlert, resolveWhenSent: Boolean = false) {
        mutableState.value = AlertState.Sending(alert, resolveWhenSent)
        val base = persisted?.takeIf { it.key == alert.incidentKey } ?: PersistedAlert.of(alert, PersistedAlert.Phase.SENDING)
        persist(
            base.copy(
                phase = PersistedAlert.Phase.SENDING,
                contacts = alert.emergencyContactCount,
                resolveWhenSent = resolveWhenSent,
                eventId = base.eventId ?: journalIds[alert.id],
            ),
        )
    }

    /**
     * "I'm OK" while counting (cancel), while sending (no SMS from now on; resolved as soon as it is out) or after
     * sending ("I'm safe now": resolve as user_ok and tell the contacts that were texted). Idempotent.
     */
    override fun imOk() {
        var cancel: ActiveAlert? = null
        var resolve: AlertState.Sent? = null
        synchronized(lock) {
            when (val s = mutableState.value) {
                is AlertState.Counting -> {
                    mutableState.value = AlertState.Finished(s.alert, Ending.CANCELLED)
                    persist(null)
                    cancel = s.alert
                }
                is AlertState.Sending -> if (!s.resolveWhenSent) {
                    mutableState.value = s.copy(resolveWhenSent = true)
                    persisted?.let { persist(it.copy(resolveWhenSent = true)) }
                }
                is AlertState.Sent -> {
                    mutableState.value = AlertState.Finished(s.alert, Ending.RESOLVED, s.result)
                    persist(null)
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
            scope.launch { closeOnServerAndTellContacts(sent.alert, sent.result) }
        }
    }

    private suspend fun closeOnServerAndTellContacts(alert: ActiveAlert, result: DispatchResult) {
        result.incidentKey?.let { key -> catchingNonCancellation { dispatcher.resolve(key, Resolution.USER_OK) } }
        if (result.sms.reachedContacts) catchingNonCancellation { dispatcher.notifySafe(alert, smsWentOut = true) }
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
     * running it is sent now instead; if an alert with a live session was already sent, that result is returned.
     *
     * The send runs on the controller's scope: cancelling the caller (the SOS screen closed with Back) only stops
     * the wait for the result, never the SOS.
     */
    suspend fun triggerSos(): SosOutcome {
        var start: ActiveAlert? = null
        var awaitId: Long? = null
        var already: DispatchResult? = null
        synchronized(lock) {
            when (val s = mutableState.value) {
                is AlertState.Counting -> awaitId = s.alert.id
                is AlertState.Sending -> awaitId = s.alert.id
                is AlertState.Sent -> if (s.result.hasLiveSession) already = s.result else start = newSosLocked()
                AlertState.Idle, is AlertState.Finished -> start = newSosLocked()
            }
        }
        already?.let { return SosOutcome(it.report, it.sms) }
        awaitId?.let { id ->
            sendNow()
            val r = results[id]?.await() ?: return SosOutcome(IncidentRepository.ReportResult.NotSignedIn, SmsOutcome.NOT_SUPPORTED)
            return SosOutcome(r.report, r.sms)
        }
        val alert = start!!
        val done = results.getValue(alert.id)
        Log.i(TAG, "SOS: sending")
        scope.launch {
            wake.acquire(WAKE_TIMEOUT_MS)
            val count = catchingNonCancellation { contactCount() } ?: 0
            val withContacts = alert.copy(emergencyContactCount = count, familyRecipients = recipientsNow())
            synchronized(lock) {
                val s = mutableState.value
                if (s is AlertState.Sending && s.alert.id == alert.id) {
                    mutableState.value = s.copy(alert = withContacts)
                    persisted?.let { persist(it.copy(contacts = count, family = withContacts.familyRecipients)) }
                }
            }
            dispatch(withContacts)
        }
        val r = done.await()
        return SosOutcome(r.report, r.sms)
    }

    private fun recipientsNow(): List<AlertRecipient> = try {
        familyRecipients()
    } catch (e: RuntimeException) {
        Log.w(TAG, "Family recipients unavailable: ${e.javaClass.simpleName}")
        emptyList()
    }

    /** Under [lock]. */
    private fun newSosLocked(): ActiveAlert {
        val alert = ActiveAlert(
            id = nextId++, kind = IncidentKind.SOS, detectedAtMs = time.nowMillis(), mode = null,
            countdownMs = 0, countdownEndsAtMs = null, event = null,
        )
        results[alert.id] = CompletableDeferred()
        toSending(alert)
        return alert
    }

    /** Runs the dispatcher for [alert] (state is already [AlertState.Sending]). Never throws but for cancellation. */
    private suspend fun dispatch(alert: ActiveAlert, smsDone: SmsOutcome? = null): DispatchResult {
        ui.stopAlarm()
        ui.showSent(alert)
        val trail = frozen[alert.id]?.await() ?: catchingNonCancellation { freezer.freeze(alert.detectedAtMs) } ?: emptyList()
        val hooks = DispatchHooks(
            stillWanted = { stillWanted(alert.id) },
            smsDone = smsDone,
            onSmsDone = { outcome -> updatePersisted(alert.incidentKey) { it.copy(sms = outcome.name) } },
            onLiveEnded = { key -> onLiveEnded(alert.id, key) },
        )
        val result = try {
            dispatcher.dispatch(alert, trail, hooks)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Dispatch failed unexpectedly", e)
            DispatchResult(
                IncidentRepository.ReportResult.Rejected(DispatchResult.LOCAL_FAILURE_KEY, ApiError.Unexpected(e)),
                SmsOutcome.FAILED,
            )
        }
        Log.i(TAG, "Sent: report=${result.report.javaClass.simpleName} sms=${result.sms} recipients=${result.smsRecipients} live=${result.hasLiveSession}")
        var resolveNow = false
        synchronized(lock) {
            val s = mutableState.value
            if (s is AlertState.Sending && s.alert.id == alert.id) {
                resolveNow = s.resolveWhenSent
                if (resolveNow) {
                    mutableState.value = AlertState.Finished(s.alert, Ending.RESOLVED, result)
                    persist(null)
                } else {
                    mutableState.value = AlertState.Sent(s.alert, result)
                    // Only an alert with live updates has anything to resume after a restart.
                    persist(
                        if (result.hasLiveSession) {
                            (persisted?.takeIf { it.key == alert.incidentKey } ?: PersistedAlert.of(alert, PersistedAlert.Phase.SENT))
                                .copy(phase = PersistedAlert.Phase.SENT, sms = result.sms.name, sentAtMs = time.nowMillis())
                        } else {
                            null
                        },
                    )
                }
            }
        }
        wake.release()
        results[alert.id]?.complete(result)
        if (!resolveNow) scheduleSentEnd(alert.id, sentMaxMs)
        eventIds[alert.id]?.await()?.let { journal.setOutcome(it, DetectionOutcome.REPORTED, result.incidentKey) }
        if (resolveNow) {
            ui.dismiss(alert)
            closeOnServerAndTellContacts(alert, result)
        }
        return result
    }

    /** False once "I'm OK" arrived while [alertId] was being sent. */
    private fun stillWanted(alertId: Long): Boolean = synchronized(lock) {
        val s = mutableState.value
        !(s is AlertState.Sending && s.alert.id == alertId && s.resolveWhenSent)
    }

    private fun scheduleSentEnd(alertId: Long, afterMs: Long) {
        scope.launch {
            delay(afterMs.coerceAtLeast(0))
            endSent(alertId, "no response within ${sentMaxMs / 60_000} min")
        }
    }

    private fun onLiveEnded(alertId: Long, incidentKey: String) = endSent(alertId, "live updates ended for $incidentKey")

    private fun endSent(alertId: Long, why: String) {
        val ended = synchronized(lock) {
            val s = mutableState.value as? AlertState.Sent ?: return
            if (s.alert.id != alertId) return
            mutableState.value = AlertState.Finished(s.alert, Ending.ENDED, s.result)
            persist(null)
            s.alert
        }
        Log.i(TAG, "Alert ended: $why")
        ui.dismiss(ended)
    }

    // ------------------------------------------------------------------ persistence

    /** Under [lock]. Write-through; a failing store never breaks an alert. */
    private fun persist(p: PersistedAlert?) {
        persisted = p
        try {
            if (p == null) store.clear() else store.save(p)
        } catch (e: Exception) {
            Log.w(TAG, "Could not persist the alert state: ${e.javaClass.simpleName}")
        }
    }

    private fun updatePersisted(key: String, change: (PersistedAlert) -> PersistedAlert) = synchronized(lock) {
        val p = persisted ?: return@synchronized
        if (p.key == key) persist(change(p))
    }

    /**
     * Resumes the alert a previous process was handling when it died (call once, right after construction):
     * - a countdown still running is shown again with the time left; one that expired less than
     *   [RESUME_SEND_MAX_AGE_MS] ago is sent right away; an older one asks again with a fresh countdown;
     * - an interrupted send is completed (same incident key; the SMS is not repeated if it already went out);
     * - a sent alert gets its live updates and its "I'm safe now" back until the live hour is over.
     * Alerts older than [RESUME_MAX_AGE_MS] are dropped. Without anything to resume, a leftover alert notification
     * is removed. The state is set synchronously, so a notification action arriving right after this sees it.
     */
    fun restore() {
        val p = synchronized(lock) {
            if (restored) return
            restored = true
            if (mutableState.value != AlertState.Idle) return
            try {
                store.load()
            } catch (e: Exception) {
                null
            }
        }
        if (p == null) {
            ui.clearStale()
            // Load the persistence code now, not on the first detection (seconds on a cold, interpreted start).
            scope.launch { catchingNonCancellation { PersistedAlert.decode(PersistedAlert.encode(WARM_UP)) } }
            return
        }
        val now = time.nowMillis()
        val ageMs = now - p.detectedAtMs
        if (ageMs > RESUME_MAX_AGE_MS || ageMs < -RESUME_MAX_AGE_MS) {
            Log.w(TAG, "Dropping a ${p.phase} alert from ${ageMs / 60_000} min ago")
            synchronized(lock) { persist(null) }
            ui.clearStale()
            return
        }
        when (p.phase) {
            PersistedAlert.Phase.COUNTING -> {
                val ends = p.countdownEndsAtMs ?: now
                when {
                    ends > now -> resumeCounting(p, ends)
                    now - ends <= RESUME_SEND_MAX_AGE_MS -> resumeSending(p)
                    else -> resumeCounting(p, now + p.countdownMs.coerceAtLeast(COUNTDOWN_WALKING_MS))
                }
            }
            PersistedAlert.Phase.SENDING -> resumeSending(p)
            PersistedAlert.Phase.SENT -> resumeSent(p, now)
        }
    }

    private fun resumeCounting(p: PersistedAlert, endsAtMs: Long) {
        val alert = synchronized(lock) {
            p.toActiveAlert(nextId++).copy(countdownEndsAtMs = endsAtMs).also {
                mutableState.value = AlertState.Counting(it)
                persist(p.copy(phase = PersistedAlert.Phase.COUNTING, countdownEndsAtMs = endsAtMs))
            }
        }
        Log.i(TAG, "Resumed countdown: ${alert.kind}, ${(endsAtMs - time.nowMillis()) / 1000} s left")
        eventIds[alert.id] = CompletableDeferred(p.eventId)
        startCounting(alert)
    }

    private fun resumeSending(p: PersistedAlert) {
        val alert = synchronized(lock) {
            p.toActiveAlert(nextId++).also {
                persisted = p
                toSending(it, resolveWhenSent = p.resolveWhenSent)
            }
        }
        Log.i(TAG, "Resumed send: ${alert.kind} (sms ${p.smsOutcome ?: "not sent yet"})")
        eventIds[alert.id] = CompletableDeferred(p.eventId)
        results[alert.id] = CompletableDeferred()
        wake.acquire(WAKE_TIMEOUT_MS)
        scope.launch { dispatch(alert, smsDone = p.smsOutcome) }
    }

    private fun resumeSent(p: PersistedAlert, now: Long) {
        val sentAt = p.sentAtMs ?: p.detectedAtMs
        val endsAt = sentAt + sentMaxMs
        if (endsAt <= now) {
            synchronized(lock) { persist(null) }
            ui.clearStale()
            return
        }
        val sms = p.smsOutcome ?: SmsOutcome.FAILED
        val alert = synchronized(lock) {
            p.toActiveAlert(nextId++).also {
                // Provisional until the dispatcher has looked the incident up (it keeps the alert active meanwhile).
                mutableState.value = AlertState.Sent(it, DispatchResult(IncidentRepository.ReportResult.Queued(p.key, null), sms, p.contacts))
                persisted = p
            }
        }
        Log.i(TAG, "Resumed sent alert: ${alert.kind}")
        eventIds[alert.id] = CompletableDeferred(p.eventId)
        ui.showSent(alert)
        scheduleSentEnd(alert.id, endsAt - now)
        scope.launch {
            val report = catchingNonCancellation {
                dispatcher.resumeLive(alert, liveEndsAtMs = sentAt + LIVE_DURATION_MS) { key -> onLiveEnded(alert.id, key) }
            }
            if (report == null) {
                endSent(alert.id, "nothing to resume")
                return@launch
            }
            synchronized(lock) {
                val s = mutableState.value
                if (s is AlertState.Sent && s.alert.id == alert.id) mutableState.value = s.copy(result = s.result.copy(report = report))
            }
        }
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
        const val LIVE_DURATION_MS = 60 * 60_000L
        const val SENT_MAX_MS = 61 * 60_000L

        /** A countdown that expired while the process was dead is sent at once if this recent; else asked again. */
        const val RESUME_SEND_MAX_AGE_MS = 5 * 60_000L

        /** Alerts older than this are not resumed after a restart. */
        const val RESUME_MAX_AGE_MS = 6 * 60 * 60_000L

        private val WARM_UP = PersistedAlert(
            key = "warm-up", phase = PersistedAlert.Phase.COUNTING, kind = IncidentKind.FALL, detectedAtMs = 0, countdownMs = 0,
            event = PersistedEvent("FALL", 0f, 0L, mapOf("x" to Float.NaN), ""),
        )

        fun countdownMs(kind: IncidentKind, mode: Mode?): Long =
            if (kind == IncidentKind.VEHICLE_CRASH || mode == Mode.VEHICLE) COUNTDOWN_VEHICLE_MS else COUNTDOWN_WALKING_MS
    }
}

/** Hold-to-SOS through the alert pipeline (upload + SMS + live updates); replaces [ServerOnlySosTrigger]. */
class ControllerSosTrigger(private val controller: AlertController) : SosTrigger {
    override suspend fun triggerSos(): SosOutcome = controller.triggerSos()
}
