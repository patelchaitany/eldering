package com.eldering.safety.alert

import com.eldering.detection.DetectionEvent
import com.eldering.detection.EventKind
import com.eldering.detection.Mode
import com.eldering.safety.data.db.DetectionOutcome
import com.eldering.safety.data.db.FeedbackLabel
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
        assertEquals("incident-1", sent.result.incidentKey)
        assertTrue("alarm stops once sent", "alarm-off" in ui.calls)
        assertTrue("sent" in ui.calls)
        assertEquals(DetectionOutcome.REPORTED, journal.outcomes.single().second)
        assertEquals("incident-1", journal.outcomes.single().third)
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
        c.imOk()
        c.imOk()
        runCurrent()

        assertEquals(Ending.RESOLVED, (c.state.value as AlertState.Finished).ending)
        assertEquals(listOf("incident-1" to Resolution.USER_OK), dispatcher.resolved)
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

        dispatcher.gate!!.complete(Unit)
        runCurrent()
        assertEquals(Ending.RESOLVED, (c.state.value as AlertState.Finished).ending)
        assertEquals(listOf("incident-1" to Resolution.USER_OK), dispatcher.resolved)
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
        dispatcher.onLiveEnded!!("incident-1")
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
        var gate: CompletableDeferred<Unit>? = null
        var fail = false
        var onLiveEnded: ((String) -> Unit)? = null

        override suspend fun dispatch(alert: ActiveAlert, frozenTrail: List<TrailPoint>, onLiveEnded: (String) -> Unit): DispatchResult {
            dispatched += alert to frozenTrail
            this.onLiveEnded = onLiveEnded
            gate?.await()
            if (fail) error("boom")
            val key = "incident-${dispatched.size}"
            return DispatchResult(
                IncidentRepository.ReportResult.Sent(key, "server-$key", "https://share.example/s/x", FakeTime.T0 + 72 * 3_600_000L),
                SmsOutcome.DRY_RUN,
                smsRecipients = 2,
            )
        }

        override suspend fun resolve(incidentKey: String, resolution: Resolution) {
            resolved += incidentKey to resolution
        }
    }

    private companion object {
        const val SENSOR_NOW = 5_000_000_000_000L
    }
}
