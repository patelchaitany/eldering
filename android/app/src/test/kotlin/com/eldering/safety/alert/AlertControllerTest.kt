package com.eldering.safety.alert

import com.eldering.detection.DetectionEvent
import com.eldering.detection.EventKind
import com.eldering.detection.Mode
import com.eldering.safety.data.db.DetectionOutcome
import com.eldering.safety.data.db.FeedbackLabel
import com.eldering.safety.data.repository.AlertRecipient
import com.eldering.safety.data.repository.IncidentRepository
import com.eldering.safety.data.trail.TrailPoint
import com.eldering.safety.network.IncidentKind
import com.eldering.safety.network.Resolution
import com.eldering.safety.testing.FakeTime
import com.eldering.safety.ui.alert.AlertPhase
import com.eldering.safety.ui.alert.DeliveryState
import com.eldering.safety.ui.alert.FeedbackChoice
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The accident state machine: countdown, idempotent actions, cancel / send / resolve, SOS, suppression. */
@OptIn(ExperimentalCoroutinesApi::class)
class AlertControllerTest {

    private val time = FakeTime()
    private val ui = FakeUi()
    private val journal = FakeJournal()
    private val wake = FakeWake()
    private val dispatcher = FakeDispatcher()
    private val frozenTrail = listOf(TrailPoint(FakeTime.T0 - 60_000, 48.1, 11.5, 8f))
    private var contacts = 2
    private var family = emptyList<AlertRecipient>()

    private val store = MemoryStore()

    private fun TestScope.controller() = AlertController(
        scope = backgroundScope,
        ui = ui,
        dispatcher = dispatcher,
        journal = journal,
        freezer = { frozenTrail },
        contactCount = { contacts },
        wake = wake,
        time = time,
        wallFromElapsedNanos = { nanos -> FakeTime.T0 - (SENSOR_NOW - nanos) / 1_000_000 },
        store = store,
        familyRecipients = { family },
    )

    private fun event(kind: EventKind = EventKind.FALL, atNanos: Long = SENSOR_NOW - 10_000_000_000L) =
        DetectionEvent(kind, 0.9f, atNanos, mapOf("peak_g" to 4.2f), "test")

    @Test
    fun `a fall starts a 15 s countdown with alarm, wakelock and a journal entry`() = runTest {
        val c = controller()
        c.onDetection(event(), Mode.WALKING, windowJson = "{}")
        runCurrent()

        val counting = c.state.value as AlertState.Counting
        assertEquals(IncidentKind.FALL, counting.alert.kind)
        assertEquals(FakeTime.T0 + 15_000, counting.alert.countdownEndsAtMs)
        assertEquals(FakeTime.T0 - 10_000, counting.alert.detectedAtMs)
        assertEquals(2, counting.alert.emergencyContactCount)
        assertEquals(listOf("countdown", "alarm-on"), ui.calls)
        assertEquals(1, wake.acquired)
        assertEquals(1, journal.records.size)
        assertEquals("{}", journal.records.single().windowJson)
        assertEquals(AlertPhase.COUNTING, AlertPresentation.phase(c.state.value, counting.alert.id))
    }

    @Test
    fun `vehicle crashes and anything detected while driving count down 20 s`() = runTest {
        assertEquals(20_000L, AlertController.countdownMs(IncidentKind.VEHICLE_CRASH, Mode.WALKING))
        assertEquals(20_000L, AlertController.countdownMs(IncidentKind.FALL, Mode.VEHICLE))
        assertEquals(15_000L, AlertController.countdownMs(IncidentKind.PEDESTRIAN_IMPACT, Mode.WALKING))
        val c = controller()
        c.onDetection(event(EventKind.VEHICLE_CRASH), Mode.VEHICLE)
        runCurrent()
        assertEquals(FakeTime.T0 + 20_000, (c.state.value as AlertState.Counting).alert.countdownEndsAtMs)
    }

    @Test
    fun `when the countdown expires the alert is sent exactly once`() = runTest {
        val c = controller()
        c.onDetection(event(), Mode.WALKING)
        runCurrent()
        advanceTimeBy(14_999)
        runCurrent()
        assertTrue(c.state.value is AlertState.Counting)
        assertEquals(0, dispatcher.dispatched.size)

        advanceTimeBy(2)
        runCurrent()
        c.sendNow() // the screen's backstop at zero: must be a no-op
        runCurrent()

        val sent = c.state.value as AlertState.Sent
        assertEquals(1, dispatcher.dispatched.size)
        assertEquals(frozenTrail, dispatcher.dispatched.single().second)
        assertEquals(sent.alert.incidentKey, sent.result.incidentKey)
        assertTrue("alarm stops once sent", "alarm-off" in ui.calls)
        assertTrue("sent" in ui.calls)
        assertEquals(DetectionOutcome.REPORTED, journal.outcomes.single().second)
        assertEquals(sent.alert.incidentKey, journal.outcomes.single().third)
        assertEquals(wake.acquired, wake.released)
        assertEquals(AlertPhase.SENT, AlertPresentation.phase(sent, sent.alert.id))
        assertEquals(DeliveryState.DELIVERED, AlertPresentation.deliveries(sent).first().state)
        assertEquals(DeliveryState.DRY_RUN, AlertPresentation.deliveries(sent)[1].state)
    }

    @Test
    fun `send help now skips the rest of the countdown and is idempotent`() = runTest {
        val c = controller()
        c.onDetection(event(), Mode.WALKING)
        runCurrent()
        c.sendNow()
        c.sendNow()
        runCurrent()
        advanceTimeBy(30_000)
        runCurrent()
        assertEquals(1, dispatcher.dispatched.size)
        assertTrue(c.state.value is AlertState.Sent)
    }

    @Test
    fun `I'm OK during the countdown sends nothing and stores the false-alarm feedback`() = runTest {
        val c = controller()
        c.onDetection(event(), Mode.WALKING, windowJson = """{"accel":[]}""")
        runCurrent()
        advanceTimeBy(5_000)
        c.imOk()
        c.imOk() // idempotent
        runCurrent()
        advanceTimeBy(60_000)
        runCurrent()

        val finished = c.state.value as AlertState.Finished
        assertEquals(Ending.CANCELLED, finished.ending)
        assertEquals(0, dispatcher.dispatched.size)
        assertTrue(ui.calls.containsAll(listOf("alarm-off", "dismiss")))
        assertEquals(DetectionOutcome.CANCELLED, journal.outcomes.single().second)
        assertEquals(FeedbackLabel.FALSE_ALARM to AlertController.NOTE_IM_OK, journal.labels.single())
        assertEquals("""{"accel":[]}""", journal.records.single().windowJson)
        assertEquals(1, wake.released)
        assertEquals(AlertPhase.CANCELLED, AlertPresentation.phase(finished, finished.alert.id))

        // "What happened?" on the screen replaces the provisional verdict.
        c.record(FeedbackChoice.FELL_BUT_FINE)
        assertEquals(FeedbackLabel.REAL_INCIDENT to "fell_but_fine", journal.labels.last())
    }

    @Test
    fun `I'm safe now after sending resolves the incident as user_ok once`() = runTest {
        val c = controller()
        c.onDetection(event(), Mode.WALKING)
        runCurrent()
        c.sendNow()
        runCurrent()
        val key = c.state.value.alert!!.incidentKey
        c.imOk()
        c.imOk()
        runCurrent()

        assertEquals(Ending.RESOLVED, (c.state.value as AlertState.Finished).ending)
        assertEquals(listOf(key to Resolution.USER_OK), dispatcher.resolved)
        assertEquals("the texted contacts hear that the person is OK", 1, dispatcher.safeNotices.size)
        assertNull("nothing left to resume", store.value)
    }

    @Test
    fun `I'm OK while the alert is being sent resolves it as soon as it is out`() = runTest {
        dispatcher.gate = CompletableDeferred()
        val c = controller()
        c.onDetection(event(), Mode.WALKING)
        runCurrent()
        c.sendNow()
        runCurrent()
        assertTrue(c.state.value is AlertState.Sending)
        c.imOk()
        runCurrent()
        assertEquals(AlertPhase.CANCELLED, AlertPresentation.phase(c.state.value, c.state.value.alert!!.id))
        assertTrue(dispatcher.resolved.isEmpty())

        assertFalse("no SMS after I'm OK", dispatcher.hooks!!.stillWanted())
        dispatcher.gate!!.complete(Unit)
        runCurrent()
        val finished = c.state.value as AlertState.Finished
        assertEquals(Ending.RESOLVED, finished.ending)
        assertEquals(SmsOutcome.CANCELLED, finished.result!!.sms)
        assertEquals(false, dispatcher.smsSentWhileWanted)
        assertEquals(listOf(finished.alert.incidentKey to Resolution.USER_OK), dispatcher.resolved)
        assertTrue("nobody was texted, so no follow-up", dispatcher.safeNotices.isEmpty())
    }

    @Test
    fun `a second detection during an active alert is logged as suppressed`() = runTest {
        val c = controller()
        c.onDetection(event(), Mode.WALKING)
        runCurrent()
        val first = c.state.value.alert!!.id
        c.onDetection(event(EventKind.PEDESTRIAN_IMPACT), Mode.WALKING)
        runCurrent()

        assertEquals(first, c.state.value.alert!!.id)
        assertEquals(1, ui.calls.count { it == "countdown" })
        assertEquals(DetectionOutcome.SUPPRESSED, journal.outcomes.single().second)
    }

    @Test
    fun `after a cancelled alert the next detection counts down again`() = runTest {
        val c = controller()
        c.onDetection(event(), Mode.WALKING)
        runCurrent()
        c.imOk()
        runCurrent()
        c.onDetection(event(EventKind.PEDESTRIAN_IMPACT), Mode.WALKING)
        runCurrent()
        val counting = c.state.value as AlertState.Counting
        assertEquals(IncidentKind.PEDESTRIAN_IMPACT, counting.alert.kind)
        assertEquals(2, ui.calls.count { it == "countdown" })
    }

    @Test
    fun `hold-to-SOS sends at once through the same dispatcher, without a countdown`() = runTest {
        contacts = 1
        val c = controller()
        val outcome = async { c.triggerSos() }
        runCurrent()

        val result = outcome.await()
        assertTrue(result.report is IncidentRepository.ReportResult.Sent)
        assertEquals(SmsOutcome.DRY_RUN, result.sms)
        val alert = dispatcher.dispatched.single().first
        assertEquals(IncidentKind.SOS, alert.kind)
        assertNull(alert.countdownEndsAtMs)
        assertEquals(1, alert.emergencyContactCount)
        assertFalse("no countdown, no alarm", "countdown" in ui.calls || "alarm-on" in ui.calls)
        assertTrue(c.state.value is AlertState.Sent)
        assertEquals(0, journal.records.size) // nothing detected, nothing to label
    }

    @Test
    fun `SOS during a countdown sends that alert now`() = runTest {
        val c = controller()
        c.onDetection(event(), Mode.WALKING)
        runCurrent()
        val outcome = async { c.triggerSos() }
        runCurrent()
        outcome.await()
        assertEquals(1, dispatcher.dispatched.size)
        assertEquals(IncidentKind.FALL, dispatcher.dispatched.single().first.kind)
    }

    @Test
    fun `live updates ending elsewhere close the alert`() = runTest {
        val c = controller()
        c.onDetection(event(), Mode.WALKING)
        runCurrent()
        c.sendNow()
        runCurrent()
        dispatcher.onLiveEnded!!(c.state.value.alert!!.incidentKey)
        assertEquals(Ending.ENDED, (c.state.value as AlertState.Finished).ending)
        assertTrue("dismiss" in ui.calls)
        c.imOk() // nothing left to resolve
        runCurrent()
        assertTrue(dispatcher.resolved.isEmpty())
    }

    @Test
    fun `a sent alert nobody resolves ends by itself so detection is not suppressed forever`() = runTest {
        val c = controller()
        c.onDetection(event(), Mode.WALKING)
        runCurrent()
        c.sendNow()
        runCurrent()
        advanceTimeBy(AlertController.SENT_MAX_MS - 1)
        runCurrent()
        assertTrue(c.state.value is AlertState.Sent)
        advanceTimeBy(2)
        runCurrent()
        assertEquals(Ending.ENDED, (c.state.value as AlertState.Finished).ending)

        c.onDetection(event(EventKind.PEDESTRIAN_IMPACT), Mode.WALKING)
        runCurrent()
        assertTrue(c.state.value is AlertState.Counting)
    }

    @Test
    fun `call 112 only opens the dialer`() = runTest {
        controller().callEmergency()
        assertEquals(listOf("dial"), ui.calls)
    }

    @Test
    fun `an impact time ahead of the wall clock is clamped to now`() = runTest {
        val c = controller()
        c.onDetection(event(atNanos = SENSOR_NOW + 30_000_000_000L), Mode.WALKING)
        runCurrent()
        assertEquals(FakeTime.T0, c.state.value.alert!!.detectedAtMs)
    }

    @Test
    fun `a failing dispatcher still ends in a sent state with failure lines`() = runTest {
        dispatcher.fail = true
        val c = controller()
        c.onDetection(event(), Mode.WALKING)
        runCurrent()
        c.sendNow()
        runCurrent()
        val sent = c.state.value as AlertState.Sent
        assertNull(sent.result.incidentKey)
        assertEquals(DeliveryState.FAILED, AlertPresentation.deliveries(sent).first().state)
        assertEquals(wake.acquired, wake.released)
        assertFalse("nothing runs for it: not active", sent.isActive)
    }

    // ------------------------------------------------------------------ review fixes

    @Test
    fun `closing the SOS screen does not cancel the SOS`() = runTest {
        dispatcher.gate = CompletableDeferred()
        val c = controller()
        // The SOS screen's viewModelScope: cleared (Back) while the send is still waiting on landmarks/share URL.
        val screen = launch { c.triggerSos() }
        runCurrent()
        assertEquals(1, dispatcher.dispatched.size)
        screen.cancel()
        runCurrent()

        dispatcher.gate!!.complete(Unit)
        runCurrent()
        val sent = c.state.value as AlertState.Sent
        assertEquals(SmsOutcome.DRY_RUN, sent.result.sms)
        assertTrue(sent.result.report is IncidentRepository.ReportResult.Sent)
        assertTrue(sent.isActive)
    }

    @Test
    fun `a signed-out alert does not suppress the next detection or SOS`() = runTest {
        dispatcher.report = { IncidentRepository.ReportResult.NotSignedIn }
        val c = controller()
        c.onDetection(event(), Mode.WALKING)
        runCurrent()
        c.sendNow()
        runCurrent()
        val sent = c.state.value as AlertState.Sent
        assertFalse("no live session: nothing keeps it active", sent.isActive)
        assertNull("nothing to resume after a restart", store.value)

        // 20 minutes later, a real accident: a fresh countdown, not SUPPRESSED.
        advanceTimeBy(20 * 60_000L)
        c.onDetection(event(EventKind.PEDESTRIAN_IMPACT), Mode.WALKING)
        runCurrent()
        assertTrue(c.state.value is AlertState.Counting)
        assertTrue(journal.outcomes.none { it.second == DetectionOutcome.SUPPRESSED })
        c.imOk()
        runCurrent()

        // And an SOS sends a new alert instead of returning the old result.
        val outcome = async { c.triggerSos() }
        runCurrent()
        outcome.await()
        assertEquals(IncidentKind.SOS, dispatcher.dispatched.last().first.kind)
        assertEquals(2, dispatcher.dispatched.size)
    }

    @Test
    fun `I'm safe now after a signed-out alert still tells the texted contacts`() = runTest {
        dispatcher.report = { IncidentRepository.ReportResult.NotSignedIn }
        val c = controller()
        c.onDetection(event(), Mode.WALKING)
        runCurrent()
        c.sendNow()
        runCurrent()
        c.imOk()
        runCurrent()
        assertEquals(Ending.RESOLVED, (c.state.value as AlertState.Finished).ending)
        assertTrue("nothing on a server to resolve", dispatcher.resolved.isEmpty())
        assertEquals(1, dispatcher.safeNotices.size)
    }

    @Test
    fun `the running countdown is persisted and cleared when cancelled`() = runTest {
        val c = controller()
        c.onDetection(event(), Mode.WALKING, windowJson = "{}")
        runCurrent()
        val saved = store.value!!
        val alert = c.state.value.alert!!
        assertEquals(PersistedAlert.Phase.COUNTING, saved.phase)
        assertEquals(alert.incidentKey, saved.key)
        assertEquals(alert.countdownEndsAtMs, saved.countdownEndsAtMs)
        assertEquals(1L, saved.eventId)
        assertEquals(EventKind.FALL.name, saved.event!!.kind)
        c.imOk()
        assertNull(store.value)
    }

    @Test
    fun `the countdown names the family members who get the app alert, through a restart and into the sent lines`() = runTest {
        // E2E: "· To 1 emergency contact (SMS)" although Test Viewer was alerted through the app.
        family = listOf(AlertRecipient("u-mom", "Mom"), AlertRecipient("u-dad", "Dad"))
        val first = controller()
        first.onDetection(event(), Mode.WALKING)
        runCurrent()
        val counting = first.state.value as AlertState.Counting
        val info = AlertPresentation.info(counting.alert, counting)
        assertEquals(listOf("Mom", "Dad"), info.familyRecipients)
        assertEquals(2, info.emergencyContactCount)
        assertEquals(family, store.value!!.family)

        // A new process (no network yet) still knows who was named.
        family = emptyList()
        val c = controller()
        c.restore()
        runCurrent()
        c.sendNow()
        runCurrent()
        val sent = c.state.value as AlertState.Sent
        assertEquals(listOf("Mom", "Dad"), sent.alert.familyRecipients.map { it.name })
        assertEquals(listOf("Mom", "Dad"), dispatcher.dispatched.single().first.familyRecipients.map { it.name })

        // "Delivered" turns into who has actually seen it once family acknowledges.
        val before = AlertPresentation.deliveries(sent)
        assertEquals(DeliveryState.DELIVERED, before.first().state)
        val acks = listOf(com.eldering.safety.network.AckDto("i", "u-dad", "Dad", com.eldering.safety.network.AckState.ON_MY_WAY, java.time.Instant.EPOCH))
        val after = AlertPresentation.deliveries(sent, acks)
        assertEquals(listOf(DeliveryState.DELIVERED, DeliveryState.ON_THE_WAY, DeliveryState.DRY_RUN), after.map { it.state })
    }

    @Test
    fun `an SOS names the family members too`() = runTest {
        family = listOf(AlertRecipient("u-mom", "Mom"))
        val c = controller()
        val sos = async { c.triggerSos() }
        runCurrent()
        sos.await()
        assertEquals(listOf("Mom"), dispatcher.dispatched.single().first.familyRecipients.map { it.name })
    }

    @Test
    fun `after process death a running countdown comes back with the time left`() = runTest {
        val first = controller()
        first.onDetection(event(), Mode.WALKING)
        runCurrent()
        val key = first.state.value.alert!!.incidentKey

        // New process 6 s later: same store, fresh controller.
        time.advance(6_000)
        ui.calls.clear()
        val c = controller()
        c.restore()
        val counting = c.state.value as AlertState.Counting
        assertEquals(key, counting.alert.incidentKey)
        assertEquals(FakeTime.T0 + 15_000, counting.alert.countdownEndsAtMs)
        assertEquals(listOf("countdown", "alarm-on"), ui.calls)

        // A notification "I'm OK" arriving right after the restart acts on it.
        c.imOk()
        assertEquals(Ending.CANCELLED, (c.state.value as AlertState.Finished).ending)
        runCurrent()
        assertTrue(dispatcher.dispatched.isEmpty())
    }

    @Test
    fun `a countdown that expired while the process was dead is sent at once with the same key`() = runTest {
        val first = controller()
        first.onDetection(event(), Mode.WALKING)
        runCurrent()
        val key = first.state.value.alert!!.incidentKey

        time.advance(60_000) // expired 45 s ago
        val c = controller()
        c.restore()
        runCurrent()
        val sent = c.state.value as AlertState.Sent
        assertEquals(key, dispatcher.dispatched.single().first.incidentKey)
        assertEquals(key, sent.result.incidentKey)
        assertEquals(PersistedAlert.Phase.SENT, store.value!!.phase)
    }

    @Test
    fun `an interrupted send whose SMS already went out does not text again`() = runTest {
        store.value = PersistedAlert(
            key = "k-1", phase = PersistedAlert.Phase.SENDING, kind = IncidentKind.FALL, detectedAtMs = FakeTime.T0 - 30_000,
            countdownMs = 15_000, countdownEndsAtMs = FakeTime.T0 - 15_000, contacts = 2, sms = SmsOutcome.SENT.name,
        )
        val c = controller()
        c.restore()
        runCurrent()
        assertEquals(SmsOutcome.SENT, dispatcher.hooks!!.smsDone)
        assertEquals(SmsOutcome.SENT, (c.state.value as AlertState.Sent).result.sms)
    }

    @Test
    fun `a sent alert resumes its live updates and its I'm safe now`() = runTest {
        store.value = PersistedAlert(
            key = "k-2", phase = PersistedAlert.Phase.SENT, kind = IncidentKind.FALL, detectedAtMs = FakeTime.T0 - 10 * 60_000,
            countdownMs = 15_000, sms = SmsOutcome.SENT.name, sentAtMs = FakeTime.T0 - 9 * 60_000,
        )
        dispatcher.resumeResult = IncidentRepository.ReportResult.Queued("k-2", null)
        val c = controller()
        c.restore()
        assertTrue("active right away (notification actions)", c.state.value.isActive)
        runCurrent()
        assertEquals(FakeTime.T0 - 9 * 60_000 + AlertController.LIVE_DURATION_MS, dispatcher.resumed.single().second)
        assertTrue("sent" in ui.calls)

        c.imOk()
        runCurrent()
        assertEquals(listOf("k-2" to Resolution.USER_OK), dispatcher.resolved)
        assertEquals(1, dispatcher.safeNotices.size)
        assertNull(store.value)
    }

    @Test
    fun `a sent alert whose incident is already closed is not resumed`() = runTest {
        store.value = PersistedAlert(
            key = "k-3", phase = PersistedAlert.Phase.SENT, kind = IncidentKind.SOS, detectedAtMs = FakeTime.T0 - 60_000,
            countdownMs = 0, sms = SmsOutcome.NO_CONTACTS.name, sentAtMs = FakeTime.T0 - 60_000,
        )
        dispatcher.resumeResult = null
        val c = controller()
        c.restore()
        runCurrent()
        assertEquals(Ending.ENDED, (c.state.value as AlertState.Finished).ending)
        assertNull(store.value)
    }

    @Test
    fun `nothing to resume clears a notification left behind, old alerts are dropped`() = runTest {
        controller().restore()
        assertEquals(listOf("clear-stale"), ui.calls)

        ui.calls.clear()
        store.value = PersistedAlert(
            key = "old", phase = PersistedAlert.Phase.COUNTING, kind = IncidentKind.FALL,
            detectedAtMs = FakeTime.T0 - AlertController.RESUME_MAX_AGE_MS - 1, countdownMs = 15_000,
        )
        val c = controller()
        c.restore()
        assertEquals(AlertState.Idle, c.state.value)
        assertNull(store.value)
        assertEquals(listOf("clear-stale"), ui.calls)
    }

    // ------------------------------------------------------------------ fakes

    private class FakeUi : AlertUi {
        val calls = CopyOnWriteArrayList<String>()
        override fun showCountdown(alert: ActiveAlert) { calls += "countdown" }
        override fun showSent(alert: ActiveAlert) { calls += "sent" }
        override fun dismiss(alert: ActiveAlert) { calls += "dismiss" }
        override fun startAlarm() { calls += "alarm-on" }
        override fun stopAlarm() { calls += "alarm-off" }
        override fun dialEmergency() { calls += "dial" }
        override fun clearStale() { calls += "clear-stale" }
    }

    private class FakeJournal : DetectionJournal {
        val records = CopyOnWriteArrayList<ActiveAlert>()
        val outcomes = CopyOnWriteArrayList<Triple<Long, DetectionOutcome, String?>>()
        val labels = CopyOnWriteArrayList<Pair<FeedbackLabel?, String?>>()
        override suspend fun record(alert: ActiveAlert): Long? {
            if (alert.event == null) return null
            records += alert
            return records.size.toLong()
        }
        override suspend fun setOutcome(eventId: Long, outcome: DetectionOutcome, incidentKey: String?) {
            outcomes += Triple(eventId, outcome, incidentKey)
        }
        override suspend fun label(eventId: Long, label: FeedbackLabel?, note: String?) {
            labels += label to note
        }
    }

    private class FakeWake : WakeHold {
        var acquired = 0
        var released = 0
        override fun acquire(timeoutMs: Long) { acquired++ }
        override fun release() { released++ }
    }

    private class FakeDispatcher : AlertDispatcher {
        val dispatched = CopyOnWriteArrayList<Pair<ActiveAlert, List<TrailPoint>>>()
        val resolved = CopyOnWriteArrayList<Pair<String, Resolution>>()
        val safeNotices = CopyOnWriteArrayList<Pair<ActiveAlert, Boolean>>()
        val resumed = CopyOnWriteArrayList<Pair<ActiveAlert, Long>>()
        var gate: CompletableDeferred<Unit>? = null
        var fail = false
        var report: (String) -> IncidentRepository.ReportResult = { key ->
            IncidentRepository.ReportResult.Sent(key, "server-$key", "https://share.example/s/x", FakeTime.T0 + 72 * 3_600_000L)
        }
        var sms = SmsOutcome.DRY_RUN
        var onLiveEnded: ((String) -> Unit)? = null
        var hooks: DispatchHooks? = null
        var smsSentWhileWanted: Boolean? = null
        var resumeResult: IncidentRepository.ReportResult? = null

        override suspend fun dispatch(alert: ActiveAlert, frozenTrail: List<TrailPoint>, hooks: DispatchHooks): DispatchResult {
            dispatched += alert to frozenTrail
            this.hooks = hooks
            this.onLiveEnded = hooks.onLiveEnded
            gate?.await()
            if (fail) error("boom")
            // Like IncidentSender: the SMS step asks whether the alert is still wanted right before texting.
            val outcome = hooks.smsDone ?: if (hooks.stillWanted()) sms else SmsOutcome.CANCELLED
            smsSentWhileWanted = hooks.stillWanted()
            hooks.onSmsDone(outcome)
            return DispatchResult(report(alert.incidentKey), outcome, smsRecipients = 2)
        }

        override suspend fun resolve(incidentKey: String, resolution: Resolution) {
            resolved += incidentKey to resolution
        }

        override suspend fun resumeLive(alert: ActiveAlert, liveEndsAtMs: Long, onLiveEnded: (String) -> Unit): IncidentRepository.ReportResult? {
            resumed += alert to liveEndsAtMs
            this.onLiveEnded = onLiveEnded
            return resumeResult
        }

        override suspend fun notifySafe(alert: ActiveAlert, smsWentOut: Boolean) {
            safeNotices += alert to smsWentOut
        }
    }

    private class MemoryStore : AlertStore {
        var value: PersistedAlert? = null
        override fun load(): PersistedAlert? = value
        override fun save(alert: PersistedAlert) {
            // Round-trip through the real encoding, as on the phone.
            value = PersistedAlert.decode(PersistedAlert.encode(alert))
        }
        override fun clear() {
            value = null
        }
    }

    private companion object {
        const val SENSOR_NOW = 5_000_000_000_000L
    }
}
