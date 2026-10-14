package com.eldering.safety.alert

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.eldering.detection.DetectionEvent
import com.eldering.detection.EventKind
import com.eldering.detection.Mode
import com.eldering.safety.data.db.ElderingDatabase
import com.eldering.safety.data.db.EmergencyContact
import com.eldering.safety.data.db.OwnIncidentStatus
import com.eldering.safety.data.outbox.OutboxProcessor
import com.eldering.safety.data.repository.IncidentRepository
import com.eldering.safety.data.session.SessionStore
import com.eldering.safety.data.trail.TrailPoint
import com.eldering.safety.data.trail.TrailSamplingPolicy
import com.eldering.safety.data.trail.TrailStore
import com.eldering.safety.network.ElderingNetwork
import com.eldering.safety.network.IncidentKind
import com.eldering.safety.network.Resolution
import com.eldering.safety.testing.FakeTime
import com.eldering.safety.testing.INCIDENT_ID
import com.eldering.safety.testing.ServerSamples
import com.eldering.safety.testing.errorJson
import com.eldering.safety.testing.inMemoryDatabase
import com.eldering.safety.testing.json
import com.eldering.safety.testing.session
import com.eldering.safety.testing.testCipher
import com.eldering.safety.testing.testDataStore
import java.time.ZoneOffset
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * IncidentSender against a MockWebServer, an in-memory database and a fake SMS sender (nothing here can text or
 * call anyone): trail + landmarks upload, SMS text with share link and offline coordinates, live updates, resolve.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class IncidentSenderTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val server = MockWebServer()
    private val time = FakeTime()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val scheduled = AtomicInteger()
    private val sms = FakeSms()
    private val geocoded = CopyOnWriteArrayList<Pair<Double, Double>>()
    private var online = true
    private var contacts = listOf(contact("Ravi", "+15550100001"), contact("Meera", "+15550100002"))
    private var keys = 0
    private var recipientsGate: CompletableDeferred<Unit>? = null
    private lateinit var db: ElderingDatabase
    private lateinit var sessions: SessionStore
    private lateinit var trail: TrailStore
    private lateinit var incidents: IncidentRepository

    private val detectedAt = FakeTime.T0 - 20_000

    @Before
    fun setUp() {
        server.start()
        db = inMemoryDatabase()
        sessions = SessionStore(testDataStore(tmp.root, scope), testCipher())
        runBlocking { sessions.save(session(accessExpiresAtMs = Long.MAX_VALUE)) }
        val api = ElderingNetwork(server.url("/").toString(), sessions, time).api
        incidents = IncidentRepository(api, db, sessions, OutboxProcessor(api, db, sessions, time), { scheduled.incrementAndGet() }, time, newIncidentKey = { "key-${++keys}" })
        trail = TrailStore(db.trailDao(), time)
        runBlocking {
            // A walk over the last 10 minutes (every 30 s, ~40 m apart), then the fall.
            for (i in 0..20) {
                val t = detectedAt - (20 - i) * 30_000L
                trail.record(TrailPoint(t, 48.1300 + i * 0.0004, 11.5700, 9f), TrailSamplingPolicy.ALERTING)
            }
        }
    }

    @After
    fun tearDown() {
        scope.cancel()
        server.close()
        db.close()
    }

    private fun sender(config: IncidentSender.Config = IncidentSender.Config(liveIntervalMs = 60_000)) = IncidentSender(
        trailStore = trail,
        incidents = incidents,
        recipients = {
            recipientsGate?.await()
            contacts
        },
        sms = sms,
        landmarks = { lat, lng ->
            geocoded += lat to lng
            if (lat > 48.1379) "Marienplatz, Altstadt" else null
        },
        isOnline = { online },
        senderName = { sessions.current()?.displayName },
        scope = scope,
        time = time,
        config = config,
    )

    private fun alert(kind: IncidentKind = IncidentKind.FALL, key: String = "key-1") = ActiveAlert(
        id = 1,
        kind = kind,
        detectedAtMs = detectedAt,
        mode = Mode.WALKING,
        countdownMs = 15_000,
        countdownEndsAtMs = detectedAt + 25_000,
        event = DetectionEvent(EventKind.FALL, 0.93f, 1L, mapOf("peak_g" to 4.5f, "speed_before" to 1.3f), "fall: impact + lying still"),
        emergencyContactCount = contacts.size,
        incidentKey = key,
    )

    private suspend fun frozen() = trail.snapshot(detectedAt)

    private fun take(): RecordedRequest = server.takeRequest(5, TimeUnit.SECONDS)!!

    private fun body(request: RecordedRequest): JsonObject = Json.parseToJsonElement(request.body!!.utf8()).jsonObject

    @Test
    fun `online alert uploads trail with landmarks, then texts every contact with the share link and coordinates`() = runBlocking {
        server.enqueue(json(201, ServerSamples.CREATE_INCIDENT))
        // A point recorded during the countdown is part of the upload too.
        trail.record(TrailPoint(detectedAt + 5_000, 48.1381, 11.5701, 6f), TrailSamplingPolicy.ALERTING)

        val result = sender().dispatch(alert(), frozen(), DispatchHooks())

        val report = result.report as IncidentRepository.ReportResult.Sent
        assertEquals("https://safety.example/s/abcDEF123_-xyz", report.shareUrl)
        assertEquals(SmsOutcome.DRY_RUN, result.sms)
        assertEquals(2, result.smsRecipients)

        val request = take()
        assertEquals("/v1/incidents", request.url.encodedPath)
        val sent = body(request)
        assertEquals("fall", sent["kind"]!!.jsonPrimitive.content)
        val points = sent["trail"]!!.jsonArray
        assertEquals(22, points.size)
        assertEquals("the incident point is the last fix before the impact", 48.138, sent["lat"]!!.jsonPrimitive.content.toDouble(), 1e-6)
        val landmarks = points.mapNotNull { it.jsonObject["landmark"]?.jsonPrimitive?.takeIf { p -> p.isString }?.content }
        assertTrue(landmarks.isNotEmpty() && landmarks.all { it == "Marienplatz, Altstadt" })
        assertTrue("at most ~10 key points are geocoded", geocoded.size in 2..10)
        val details = sent["details"]!!.jsonObject
        assertEquals("detector", details["source"]!!.jsonPrimitive.content)
        assertEquals("walking", details["mode"]!!.jsonPrimitive.content)
        assertEquals("2 emergency contacts (SMS)", details["also_alerted"]!!.jsonPrimitive.content)

        assertEquals(listOf("+15550100001", "+15550100002"), sms.sent.map { it.first })
        val text = sms.sent.first().second
        assertTrue(text, text.startsWith("ELDERING ALERT: Asha may have fallen at "))
        assertTrue(text, "Live location: https://safety.example/s/abcDEF123_-xyz" in text)
        assertTrue("latest position, not the trail start", "48.13810,11.57010 (+/-6 m)" in text)
        assertTrue(text, "https://maps.google.com/?q=48.13810,11.57010" in text)
        assertTrue(text, text.all { it.code < 128 })
    }

    @Test
    fun `server down - the alert is queued for retry and the SMS still goes out with coordinates only`() = runBlocking {
        server.enqueue(MockResponse.Builder().code(503).build())
        online = false

        val result = sender().dispatch(alert(), frozen(), DispatchHooks())

        assertTrue(result.report is IncidentRepository.ReportResult.Queued)
        assertEquals(1, scheduled.get())
        assertTrue("landmarks are skipped offline", geocoded.isEmpty())
        val text = sms.sent.first().second
        assertFalse(text, "Live location" in text)
        assertTrue(text, "Last position: 48.13800,11.57000" in text)
        assertTrue(text, "maps.google.com/?q=48.13800,11.57000" in text)
        assertEquals(OwnIncidentStatus.QUEUED, incidents.ownIncident("key-1")!!.status)
    }

    @Test
    fun `a slow server does not hold the SMS back`() = runBlocking {
        server.enqueue(json(201, ServerSamples.CREATE_INCIDENT).newBuilder().headersDelay(2, TimeUnit.SECONDS).build())
        val firstSms = CompletableDeferred<Long>()
        val started = System.nanoTime()
        sms.onSend = { firstSms.complete(System.nanoTime()) }

        val result = sender(IncidentSender.Config(liveIntervalMs = 60_000, shareUrlWaitMs = 300)).dispatch(alert(), frozen(), DispatchHooks())

        val smsAfterMs = (withTimeout(5_000) { firstSms.await() } - started) / 1_000_000
        assertTrue("SMS went out after $smsAfterMs ms", smsAfterMs < 1_800)
        assertFalse("no share link yet", "Live location" in sms.sent.first().second)
        assertTrue("the upload still completes", result.report is IncidentRepository.ReportResult.Sent)
    }

    @Test
    fun `signed out - nothing is uploaded, contacts are still texted`() = runBlocking {
        sessions.clear()

        val result = sender().dispatch(alert(IncidentKind.SOS), frozen(), DispatchHooks())

        assertEquals(IncidentRepository.ReportResult.NotSignedIn, result.report)
        assertNull(result.incidentKey)
        assertEquals(0, server.requestCount)
        assertEquals(2, sms.sent.size)
        assertTrue(sms.sent.first().second.startsWith("ELDERING ALERT: The owner of this phone pressed SOS at "))
        assertTrue(sender().liveIncidents.isEmpty())
    }

    @Test
    fun `no emergency contacts - no SMS`() = runBlocking {
        contacts = emptyList()
        server.enqueue(json(201, ServerSamples.CREATE_INCIDENT))
        val result = sender().dispatch(alert(), frozen(), DispatchHooks())
        assertEquals(SmsOutcome.NO_CONTACTS, result.sms)
        assertTrue(sms.sent.isEmpty())
    }

    @Test
    fun `SMS outcome reflects real sends, permission problems and failures`() = runBlocking {
        server.enqueue(json(201, ServerSamples.CREATE_INCIDENT))
        sms.result = SmsResult.Queued(2)
        assertEquals(SmsOutcome.SENT, sender().dispatch(alert(key = "a"), frozen(), DispatchHooks()).sms)

        server.enqueue(json(201, ServerSamples.CREATE_INCIDENT))
        sms.result = SmsResult.NoPermission
        assertEquals(SmsOutcome.NO_PERMISSION, sender().dispatch(alert(key = "b"), frozen(), DispatchHooks()).sms)

        server.enqueue(json(201, ServerSamples.CREATE_INCIDENT))
        sms.result = SmsResult.Failed("RIL")
        assertEquals(SmsOutcome.FAILED, sender().dispatch(alert(key = "c"), frozen(), DispatchHooks()).sms)
    }

    @Test
    fun `live updates append new points until resolved`() = runBlocking {
        server.enqueue(json(201, ServerSamples.CREATE_INCIDENT))
        val s = sender(IncidentSender.Config(liveIntervalMs = 200, liveDurationMs = 60_000))
        val ended = CompletableDeferred<String>()
        val result = s.dispatch(alert(), frozen(), DispatchHooks(onLiveEnded = { ended.complete(it) }))
        take() // create
        assertEquals(setOf("key-1"), s.liveIncidents)

        server.enqueue(json(200, """{"accepted":1}"""))
        time.advance(1_000)
        trail.record(TrailPoint(time.nowMillis(), 48.1390, 11.5702, 5f), TrailSamplingPolicy.ALERTING)
        val append = take()
        assertEquals("/v1/incidents/$INCIDENT_ID/trail", append.url.encodedPath)
        assertEquals(1, body(append)["points"]!!.jsonArray.size)

        server.enqueue(json(200, ServerSamples.INCIDENT_SUMMARY))
        s.resolve(result.incidentKey!!, Resolution.USER_OK)
        val resolve = take()
        assertEquals("/v1/incidents/$INCIDENT_ID/resolve", resolve.url.encodedPath)
        assertEquals("user_ok", body(resolve)["resolution"]!!.jsonPrimitive.content)
        assertTrue(s.liveIncidents.isEmpty())
        assertFalse("resolving is not 'ended elsewhere'", ended.isCompleted)
    }

    @Test
    fun `live updates stop by themselves when family closed the incident`() = runBlocking {
        server.enqueue(json(201, ServerSamples.CREATE_INCIDENT))
        val s = sender(IncidentSender.Config(liveIntervalMs = 150, liveDurationMs = 60_000))
        val ended = CompletableDeferred<String>()
        s.dispatch(alert(), frozen(), DispatchHooks(onLiveEnded = { ended.complete(it) }))
        take()

        server.enqueue(errorJson(409, "incident_closed"))
        time.advance(1_000)
        trail.record(TrailPoint(time.nowMillis(), 48.1390, 11.5702, 5f), TrailSamplingPolicy.ALERTING)

        assertEquals("key-1", withTimeout(5_000) { ended.await() })
        assertEquals(OwnIncidentStatus.RESOLVED, incidents.ownIncident("key-1")!!.status)
        assertTrue(s.liveIncidents.isEmpty())
    }

    @Test
    fun `live updates end after their duration`() = runBlocking {
        server.enqueue(json(201, ServerSamples.CREATE_INCIDENT))
        val ended = CompletableDeferred<String>()
        sender(IncidentSender.Config(liveIntervalMs = 50, liveDurationMs = 150)).dispatch(alert(), frozen(), DispatchHooks(onLiveEnded = { ended.complete(it) }))
        assertEquals("key-1", withTimeout(5_000) { ended.await() })
    }

    @Test
    fun `I'm OK before the SMS step - no contact is texted`() = runBlocking {
        server.enqueue(json(201, ServerSamples.CREATE_INCIDENT))
        val reported = CopyOnWriteArrayList<SmsOutcome>()

        val result = sender().dispatch(alert(), frozen(), DispatchHooks(stillWanted = { false }, onSmsDone = { reported += it }))

        assertEquals(SmsOutcome.CANCELLED, result.sms)
        assertTrue(sms.sent.isEmpty())
        assertEquals(listOf(SmsOutcome.CANCELLED), reported)
        assertTrue("the upload still happens (it is resolved right after)", result.report is IncidentRepository.ReportResult.Sent)
    }

    @Test
    fun `I'm OK in the middle of texting stops the remaining texts`() = runBlocking {
        server.enqueue(json(201, ServerSamples.CREATE_INCIDENT))
        var wanted = true
        sms.onSend = { wanted = false } // "I'm OK" arrives while the first SMS is being handed over

        val result = sender().dispatch(alert(), frozen(), DispatchHooks(stillWanted = { wanted }))

        assertEquals(SmsOutcome.DRY_RUN, result.sms)
        assertEquals(listOf("+15550100001"), sms.sent.map { it.first })
    }

    @Test
    fun `the follow-up after I'm safe now goes to the contacts that were texted`() = runBlocking {
        server.enqueue(json(201, ServerSamples.CREATE_INCIDENT))
        val s = sender()
        s.dispatch(alert(), frozen(), DispatchHooks())
        contacts = listOf(contact("Someone new", "+15550100009")) // changed since: the follow-up goes to who was texted

        s.notifySafe(alert(), smsWentOut = true)

        val followUps = sms.sent.drop(2)
        assertEquals(listOf("+15550100001", "+15550100002"), followUps.map { it.first })
        assertTrue(followUps.first().second, followUps.first().second.startsWith("ELDERING UPDATE: Asha says they are OK now"))
        s.notifySafe(alert(), smsWentOut = false)
        assertEquals("once only", 4, sms.sent.size)
    }

    @Test
    fun `a cancelled caller aborts before anything is uploaded instead of passing as no contacts`() = runBlocking {
        recipientsGate = CompletableDeferred()
        val call = scope.async { sender().dispatch(alert(), frozen(), DispatchHooks()) }
        kotlinx.coroutines.delay(300)
        call.cancel()

        val error = runCatching { call.await() }.exceptionOrNull()
        assertTrue("$error", error is kotlinx.coroutines.CancellationException)
        kotlinx.coroutines.delay(300)
        assertEquals("no upload started", 0, server.requestCount)
        assertTrue(sms.sent.isEmpty())
    }

    @Test
    fun `a send resumed after a restart reuses its incident and does not text again`() = runBlocking {
        server.enqueue(MockResponse.Builder().code(503).build())
        sender().dispatch(alert(), frozen(), DispatchHooks())
        take()
        val texts = sms.sent.size

        server.enqueue(json(201, ServerSamples.CREATE_INCIDENT))
        val resumed = sender().dispatch(alert(), frozen(), DispatchHooks(smsDone = SmsOutcome.DRY_RUN))

        assertEquals(SmsOutcome.DRY_RUN, resumed.sms)
        assertEquals(texts, sms.sent.size)
        assertTrue(resumed.report is IncidentRepository.ReportResult.Sent)
        assertEquals("same incident, same idempotency key", "key-1", take().headers["Idempotency-Key"])
        assertEquals("one create row only: nothing else queued", 0, db.outboxDao().pendingCount(com.eldering.safety.testing.USER_ID))
        assertEquals(2, server.requestCount)
        assertEquals(OwnIncidentStatus.ACTIVE, incidents.ownIncident("key-1")!!.status)
    }

    @Test
    fun `live updates resume after a restart for an open incident only`() = runBlocking {
        server.enqueue(json(201, ServerSamples.CREATE_INCIDENT))
        sender().dispatch(alert(), frozen(), DispatchHooks())
        take()

        // New process: a fresh sender resumes the hour (points pushed before the restart are simply sent again).
        trail.record(TrailPoint(time.nowMillis(), 48.1390, 11.5702, 5f), TrailSamplingPolicy.ALERTING)
        server.enqueue(json(200, """{"accepted":1}"""))
        val fresh = sender(IncidentSender.Config(liveIntervalMs = 200, liveDurationMs = 60_000))
        val report = fresh.resumeLive(alert(), liveEndsAtMs = time.nowMillis() + 60_000) {}
        assertTrue(report is IncidentRepository.ReportResult.Sent)
        assertEquals(setOf("key-1"), fresh.liveIncidents)
        assertEquals("/v1/incidents/$INCIDENT_ID/trail", take().url.encodedPath)

        server.enqueue(json(200, ServerSamples.INCIDENT_SUMMARY))
        fresh.resolve("key-1", Resolution.USER_OK)
        assertNull("closed: nothing to resume", sender().resumeLive(alert(), liveEndsAtMs = time.nowMillis() + 60_000) {})
        assertNull("unknown incident", sender().resumeLive(alert(key = "nope"), liveEndsAtMs = time.nowMillis() + 60_000) {})
    }

    @Test
    fun `SMS text is plain, short and always carries coordinates`() {
        val text = SmsText.build(
            name = "Asha", kind = IncidentKind.VEHICLE_CRASH, detectedAtMs = FakeTime.T0, lat = 48.137154, lng = 11.576124,
            accuracyM = 12.4f, shareUrl = "https://safety.example/s/tok", zone = ZoneOffset.UTC,
        )
        assertEquals(
            "ELDERING ALERT: Asha may have been in a car crash at 08:00. Live location: https://safety.example/s/tok " +
                "Last position: 48.13715,11.57612 (+/-12 m) https://maps.google.com/?q=48.13715,11.57612 If you can't reach them, call 112.",
            text,
        )
        assertTrue(text.length <= 2 * 153) // at most two GSM-7 parts
        val unknown = SmsText.build(null, IncidentKind.FALL, FakeTime.T0, null, null, null, null, ZoneOffset.UTC)
        assertTrue(unknown, "Position unknown." in unknown)
        assertNotNull(AndroidSmsSender.mask("+15550100001").takeIf { it.endsWith("01") && !it.contains("555") })
    }

    @Test
    fun `key points start with the incident and spread over the hour`() = runBlocking {
        val points = trail.recent()
        val keysPicked = KeyPoints.select(points, detectedAt, max = 10)
        assertEquals(points.lastIndex, keysPicked.first())
        assertEquals(0, keysPicked[1])
        assertTrue(keysPicked.size <= 10)
        assertEquals(keysPicked.size, keysPicked.toSet().size)
    }

    private fun contact(name: String, phone: String) = EmergencyContact(name = name, phone = phone, createdAtMs = FakeTime.T0)

    private class FakeSms : SmsSender {
        val sent = CopyOnWriteArrayList<Pair<String, String>>()
        var result: SmsResult = SmsResult.DryRun
        var onSend: () -> Unit = {}
        override suspend fun send(to: String, body: String): SmsResult {
            onSend()
            sent += to to body
            return result
        }
    }
}
