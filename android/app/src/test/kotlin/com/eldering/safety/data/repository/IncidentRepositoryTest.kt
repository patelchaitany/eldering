package com.eldering.safety.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.eldering.safety.data.db.ElderingDatabase
import com.eldering.safety.data.db.OutboxItem
import com.eldering.safety.data.db.OutboxState
import com.eldering.safety.data.db.OutboxType
import com.eldering.safety.data.db.OwnIncident
import com.eldering.safety.data.db.OwnIncidentStatus
import com.eldering.safety.data.outbox.OutboxProcessor
import com.eldering.safety.data.session.SessionStore
import com.eldering.safety.network.ApiError
import com.eldering.safety.network.ElderingNetwork
import com.eldering.safety.network.IncidentKind
import com.eldering.safety.network.Resolution
import com.eldering.safety.network.TrailPointDto
import com.eldering.safety.testing.FakeTime
import com.eldering.safety.testing.INCIDENT_ID
import com.eldering.safety.testing.OTHER_USER_ID
import com.eldering.safety.testing.ServerSamples
import com.eldering.safety.testing.USER_ID
import com.eldering.safety.testing.errorJson
import com.eldering.safety.testing.inMemoryDatabase
import com.eldering.safety.testing.json
import com.eldering.safety.testing.session
import com.eldering.safety.testing.testCipher
import com.eldering.safety.testing.testDataStore
import java.time.Instant
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class IncidentRepositoryTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val server = MockWebServer()
    private val time = FakeTime()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val scheduled = AtomicInteger()
    private var keys = 0
    private lateinit var db: ElderingDatabase
    private lateinit var sessions: SessionStore
    private lateinit var repo: IncidentRepository

    private val detectedAt = FakeTime.T0 - 5_000

    @Before
    fun setUp() {
        server.start()
        db = inMemoryDatabase()
        sessions = SessionStore(testDataStore(tmp.root, scope), testCipher())
        runBlocking { sessions.save(session(accessExpiresAtMs = Long.MAX_VALUE)) }
        val api = ElderingNetwork(server.url("/").toString(), sessions, time).api
        val outbox = OutboxProcessor(api, db, sessions, time)
        repo = IncidentRepository(api, db, sessions, outbox, { scheduled.incrementAndGet() }, time, newIncidentKey = { "incident-key-${++keys}" })
    }

    @After
    fun tearDown() {
        server.close()
        db.close()
        scope.cancel()
    }

    private fun draft(trailPoints: Int = 3) = IncidentDraft(
        kind = IncidentKind.FALL,
        detectedAtMs = detectedAt,
        lat = 48.137,
        lng = 11.575,
        details = IncidentDetails.sos(),
        trail = (0 until trailPoints).map { i -> point(detectedAt - (trailPoints - i) * 60_000L) },
    )

    private fun point(atMs: Long) = TrailPointDto(48.137, 11.575, Instant.ofEpochMilli(atMs), 5f)

    private fun takeRequest() = server.takeRequest(5, TimeUnit.SECONDS)!!

    private fun bodyOf(request: mockwebserver3.RecordedRequest) = Json.parseToJsonElement(request.body!!.utf8()).jsonObject

    @Test
    fun `online report is sent immediately with its trail`() = runBlocking {
        server.enqueue(json(201, ServerSamples.CREATE_INCIDENT))

        val result = repo.report(draft())

        result as IncidentRepository.ReportResult.Sent
        assertEquals(INCIDENT_ID, result.serverId)
        assertEquals("https://safety.example/s/abcDEF123_-xyz", result.shareUrl)
        val request = takeRequest()
        assertEquals("/v1/incidents", request.url.encodedPath)
        assertEquals("incident-key-1", request.headers["Idempotency-Key"])
        assertEquals("fall", bodyOf(request)["kind"]!!.jsonPrimitive.content)
        assertEquals(3, bodyOf(request)["trail"]!!.jsonArray.size)

        val own = repo.ownIncident("incident-key-1")!!
        assertEquals(OwnIncidentStatus.ACTIVE, own.status)
        assertEquals(INCIDENT_ID, own.serverId)
        assertEquals(USER_ID, own.userId)
        assertEquals(0, db.outboxDao().pendingCount(USER_ID))
        assertEquals(0, scheduled.get())
    }

    @Test
    fun `offline report is queued, then delivered in order with merged trail appends`() = runBlocking {
        server.enqueue(errorJson(503, "unavailable"))
        val queued = repo.report(draft())
        queued as IncidentRepository.ReportResult.Queued
        assertTrue((queued.error as ApiError.Http).status == 503)
        assertEquals(1, scheduled.get())
        takeRequest()

        // Live updates while the create is backing off: queued behind it, nothing sent yet.
        assertTrue(repo.appendTrail(queued.incidentKey, listOf(point(FakeTime.T0 + 15_000), point(FakeTime.T0 + 1_000))))
        assertTrue(repo.appendTrail(queued.incidentKey, listOf(point(FakeTime.T0 + 30_000), point(FakeTime.T0 + 15_000))))
        assertEquals(1, server.requestCount)
        assertEquals(3, db.outboxDao().pendingCount(USER_ID))

        // Connectivity is back: WorkManager drains.
        server.enqueue(json(201, ServerSamples.CREATE_INCIDENT))
        server.enqueue(json(200, """{"accepted":3}"""))
        val drained = repo.retryPending()

        assertEquals(0, drained.remaining)
        val create = takeRequest()
        assertEquals("/v1/incidents", create.url.encodedPath)
        assertEquals("same idempotency key on retry", "incident-key-1", create.headers["Idempotency-Key"])
        val append = takeRequest()
        assertEquals("/v1/incidents/$INCIDENT_ID/trail", append.url.encodedPath)
        val times = bodyOf(append)["points"]!!.jsonArray.map { it.jsonObject["recorded_at"]!!.jsonPrimitive.content }
        assertEquals(
            "both rows merged, de-duplicated and time-ordered",
            listOf(FakeTime.T0 + 1_000, FakeTime.T0 + 15_000, FakeTime.T0 + 30_000).map { Instant.ofEpochMilli(it).toString() },
            times,
        )
        assertEquals(3, server.requestCount)
        assertEquals(OwnIncidentStatus.ACTIVE, repo.ownIncident("incident-key-1")!!.status)
    }

    @Test
    fun `I'm OK before the alert ever left the phone cancels it locally`() = runBlocking {
        // State after a crash between storing the incident and the first send attempt.
        db.ownIncidentDao().insert(OwnIncident("k", USER_ID, kind = "fall", detectedAtMs = detectedAt, lat = null, lng = null, createdAtMs = detectedAt, updatedAtMs = detectedAt))
        db.outboxDao().insert(OutboxItem(type = OutboxType.CREATE_INCIDENT, incidentKey = "k", userId = USER_ID, payloadJson = "{}", createdAtMs = detectedAt))

        val incident = repo.resolveOwn("k", Resolution.FALSE_ALARM)!!

        assertEquals(OwnIncidentStatus.CANCELLED, incident.status)
        assertEquals("false_alarm", incident.resolution)
        assertEquals(0, db.outboxDao().pendingCount(USER_ID))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `resolve after sending reaches the server and stops further updates`() = runBlocking {
        server.enqueue(json(201, ServerSamples.CREATE_INCIDENT))
        val sent = repo.report(draft()) as IncidentRepository.ReportResult.Sent
        takeRequest()

        server.enqueue(json(200, ServerSamples.INCIDENT_SUMMARY))
        val resolved = repo.resolveOwn(sent.incidentKey, Resolution.USER_OK)!!

        assertEquals(OwnIncidentStatus.RESOLVED, resolved.status)
        val request = takeRequest()
        assertEquals("/v1/incidents/$INCIDENT_ID/resolve", request.url.encodedPath)
        assertEquals("user_ok", bodyOf(request)["resolution"]!!.jsonPrimitive.content)
        assertFalse("closed incidents take no more trail", repo.appendTrail(sent.incidentKey, listOf(point(FakeTime.T0))))
    }

    @Test
    fun `incident closed by family drops pending appends instead of retrying`() = runBlocking {
        server.enqueue(json(201, ServerSamples.CREATE_INCIDENT))
        val sent = repo.report(draft()) as IncidentRepository.ReportResult.Sent
        takeRequest()

        server.enqueue(errorJson(409, "incident_closed"))
        repo.appendTrail(sent.incidentKey, listOf(point(FakeTime.T0)))

        assertEquals("/v1/incidents/$INCIDENT_ID/trail", takeRequest().url.encodedPath)
        assertEquals(0, db.outboxDao().pendingCount(USER_ID))
        assertEquals(0, scheduled.get())
        // Closed locally as well, so live updates stop (appendTrail reports the incident is over).
        assertEquals(OwnIncidentStatus.RESOLVED, repo.ownIncident(sent.incidentKey)!!.status)
        assertFalse(repo.appendTrail(sent.incidentKey, listOf(point(FakeTime.T0 + 1_000))))
    }

    @Test
    fun `permanently rejected create fails the incident and is not retried`() = runBlocking {
        server.enqueue(errorJson(400, "invalid_trail", "trail point recorded_at is out of range"))

        val result = repo.report(draft())

        result as IncidentRepository.ReportResult.Rejected
        assertEquals("invalid_trail", (result.error as ApiError.Http).code)
        assertEquals(OwnIncidentStatus.FAILED, repo.ownIncident(result.incidentKey)!!.status)
        assertEquals(0, db.outboxDao().pendingCount(USER_ID))
        assertEquals(OutboxState.FAILED, db.outboxDao().get(1)!!.state)
        assertEquals(0, scheduled.get())
    }

    @Test
    fun `queued uploads never go out under another account`() = runBlocking {
        server.enqueue(errorJson(503, "unavailable"))
        repo.report(draft())
        takeRequest()

        sessions.save(session(userId = OTHER_USER_ID, accessExpiresAtMs = Long.MAX_VALUE))
        val result = repo.retryPending()

        assertEquals(0, result.remaining)
        assertEquals(1, server.requestCount)
        assertEquals(1, db.outboxDao().pendingCount(USER_ID))
    }

    @Test
    fun `uploads older than the share lifetime expire`() = runBlocking {
        server.enqueue(errorJson(503, "unavailable"))
        val queued = repo.report(draft()) as IncidentRepository.ReportResult.Queued
        takeRequest()

        time.advance(OutboxProcessor.MAX_ITEM_AGE_MS + 1)
        repo.retryPending()

        assertEquals(1, server.requestCount)
        assertEquals(OwnIncidentStatus.FAILED, repo.ownIncident(queued.incidentKey)!!.status)
    }

    @Test
    fun `not signed in reports nothing`() = runBlocking {
        sessions.clear()
        assertEquals(IncidentRepository.ReportResult.NotSignedIn, repo.report(draft()))
        assertNull(repo.ownIncident("incident-key-1"))
    }

    @Test
    fun `discarding account data removes queued uploads`() = runBlocking {
        server.enqueue(errorJson(503, "unavailable"))
        repo.report(draft())
        repo.discardAccountData(USER_ID)
        assertEquals(0, db.outboxDao().pendingCount(USER_ID))
        assertNull(repo.ownIncident("incident-key-1"))
    }

    @Test
    fun `backoff grows exponentially, is capped and honours Retry-After`() {
        assertEquals(15_000, OutboxProcessor.backoffMs(1))
        assertEquals(30_000, OutboxProcessor.backoffMs(2))
        assertEquals(OutboxProcessor.MAX_BACKOFF_MS, OutboxProcessor.backoffMs(30))
        assertEquals(120_000, OutboxProcessor.backoffMs(1, java.time.Duration.ofMinutes(2)))
    }
}
