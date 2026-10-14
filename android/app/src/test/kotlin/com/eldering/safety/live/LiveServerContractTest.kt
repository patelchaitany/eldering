package com.eldering.safety.live

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.eldering.safety.core.TimeSource
import com.eldering.safety.data.db.ElderingDatabase
import com.eldering.safety.data.db.OwnIncidentStatus
import com.eldering.safety.data.outbox.OutboxProcessor
import com.eldering.safety.data.repository.AuthRepository
import com.eldering.safety.data.repository.FamilyRepository
import com.eldering.safety.data.repository.IncidentDetails
import com.eldering.safety.data.repository.IncidentDraft
import com.eldering.safety.data.repository.IncidentRepository
import com.eldering.safety.data.repository.LocationRepository
import com.eldering.safety.data.repository.MeStore
import com.eldering.safety.data.repository.SharingRepository
import com.eldering.safety.data.session.SessionStore
import com.eldering.safety.data.trail.TrailPoint
import com.eldering.safety.network.AckState
import com.eldering.safety.network.Activity
import com.eldering.safety.network.ApiError
import com.eldering.safety.network.ApiResult
import com.eldering.safety.network.ElderingApi
import com.eldering.safety.network.ElderingNetwork
import com.eldering.safety.network.GrantLevel
import com.eldering.safety.network.IncidentKind
import com.eldering.safety.network.IncidentStatus
import com.eldering.safety.network.LocationStatus
import com.eldering.safety.network.Resolution
import com.eldering.safety.network.apiCall
import com.eldering.safety.network.getOrThrow
import com.eldering.safety.testing.inMemoryDatabase
import com.eldering.safety.testing.testCipher
import com.eldering.safety.testing.testDataStore
import java.io.File
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Contract test of the whole client stack against a *running* Eldering API. Skipped unless
 * `ELDERING_LIVE_API_URL` is set, e.g. the local compose stack:
 *
 *     ELDERING_LIVE_API_URL=http://127.0.0.1:8095 ./gradlew :app:testDebugUnitTest --tests '*LiveServerContractTest'
 *
 * Creates two throwaway accounts named like the server's e2e suite (`e2e-android-<random>@example.test`);
 * `make e2e-clean` in `server/` (or `server/e2e/cleanup.sql`) deletes them with their family.
 * Uses only test values; never point it at a server with real users unless that is intended.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class LiveServerContractTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val baseUrl: String? = System.getenv("ELDERING_LIVE_API_URL")?.trim()?.takeIf { it.isNotEmpty() }
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val databases = mutableListOf<ElderingDatabase>()

    /** Wall clock with an adjustable skew, to make access tokens look expired. */
    private class SkewableTime : TimeSource {
        @Volatile var skewMs = 0L
        override fun nowMillis(): Long = System.currentTimeMillis() + skewMs
    }

    private inner class Actor(name: String) {
        val time = SkewableTime()
        val sessions = SessionStore(testDataStore(File(tmp.root, name).apply { mkdirs() }, scope), testCipher())
        val api: ElderingApi = ElderingNetwork(baseUrl!!, sessions, time).api
        val me = MeStore()
        val db = inMemoryDatabase().also(databases::add)
        val auth = AuthRepository(api, sessions, me, time)
        val families = FamilyRepository(api, me, sessions)
        val sharing = SharingRepository(api, me)
        val location = LocationRepository(api, time)
        val incidents = IncidentRepository(api, db, sessions, OutboxProcessor(api, db, sessions, time), { }, time)
        val email = "e2e-android-${UUID.randomUUID().toString().take(12)}@example.test"
        val password = "pw-" + UUID.randomUUID()
    }

    @Before
    fun requireServer() = assumeTrue("ELDERING_LIVE_API_URL not set", baseUrl != null)

    @After
    fun tearDown() {
        databases.forEach { it.close() }
        scope.cancel()
    }

    private fun fix(lat: Double, lng: Double, atMs: Long = System.currentTimeMillis()) =
        TrailPoint(recordedAtMs = atMs, lat = lat, lng = lng, accuracyM = 8f, speedMps = 1.3f, bearingDeg = 45f, activity = Activity.WALKING)

    private fun <T> ApiResult<T>.errorCode(): String? = ((this as? ApiResult.Failure)?.error as? ApiError.Http)?.code

    @Test
    fun `full client flow against the real server`() = runBlocking {
        val asha = Actor("asha")
        val ravi = Actor("ravi")

        // --- health + accounts -------------------------------------------------------------------------------
        assertEquals("ok", apiCall { asha.api.health() }.getOrThrow().status)
        val ashaSession = asha.auth.register(asha.email, asha.password, "Asha (android e2e)").getOrThrow()
        ravi.auth.register(ravi.email, ravi.password, "Ravi (android e2e)").getOrThrow()
        assertEquals("email_taken", asha.auth.register(asha.email, asha.password, "dup").errorCode())
        assertEquals("invalid_credentials", Actor("x").auth.login(asha.email, "wrong-password").errorCode())

        val me = asha.auth.refreshMe().getOrThrow()
        assertEquals(ashaSession.userId, me.user.id)
        assertTrue(me.settings.alwaysAlertOnAccident)

        // --- family, invite, join ----------------------------------------------------------------------------
        val created = asha.families.create("Android e2e family").getOrThrow()
        assertEquals(8, created.invite.code.length)
        assertEquals("invite_invalid", ravi.families.join("ZZZZZZZZ").errorCode())
        val joined = ravi.families.join(created.invite.code.lowercase().chunked(4).joinToString("-")).getOrThrow()
        assertEquals(created.id, joined.id)
        val members = asha.families.members(created.id).getOrThrow()
        assertEquals(2, members.size)
        val raviId = members.single { !it.isMe }.userId
        assertEquals("SPEC default for new co-members", GrantLevel.EMERGENCY, members.single { !it.isMe }.myGrant)
        val invite = asha.families.createInvite(created.id).getOrThrow()
        assertTrue(invite.expiresAt.isAfter(Instant.now()))

        // --- sharing: emergency-only hides the live location, live shows it ----------------------------------
        asha.sharing.turnSharingOn().getOrThrow()
        asha.location.sendHeartbeat(fix(48.137154, 11.576124), batteryPct = 81).getOrThrow()
        ravi.location.refreshFamilyLocations().getOrThrow()
        assertEquals(LocationStatus.HIDDEN, ravi.location.familyLocations.value.single().status)
        assertNull(ravi.location.familyLocations.value.single().lat)

        assertEquals(GrantLevel.LIVE, asha.sharing.setGrant(raviId, GrantLevel.LIVE).getOrThrow().level)
        asha.location.sendHeartbeat(fix(48.1372, 11.5762), batteryPct = 80).getOrThrow()
        ravi.location.refreshFamilyLocations().getOrThrow()
        val ashaOnMap = ravi.location.familyLocations.value.single()
        assertEquals(LocationStatus.LIVE, ashaOnMap.status)
        assertEquals(48.1372, ashaOnMap.lat!!, 1e-6)
        assertEquals(80, ashaOnMap.batteryPct)
        assertEquals(Activity.WALKING, ashaOnMap.activity)

        asha.sharing.pauseSharing(Instant.now().plusSeconds(3_600)).getOrThrow()
        ravi.location.refreshFamilyLocations().getOrThrow()
        assertEquals(LocationStatus.PAUSED, ravi.location.familyLocations.value.single().status)
        asha.sharing.turnSharingOn().getOrThrow()

        // --- incident: report with trail, public share feed, viewer alert + ack, live append, resolve -------
        val now = System.currentTimeMillis()
        val trail = (10 downTo 1).map { fix(48.13 + it * 0.0005, 11.57 + it * 0.0003, now - it * 60_000L).toDto(if (it == 5) "Viktualienmarkt" else null) }
        val report = asha.incidents.report(
            IncidentDraft(IncidentKind.FALL, detectedAtMs = now - 2_000, lat = 48.1372, lng = 11.5762, details = IncidentDetails.sos(), trail = trail),
        )
        report as IncidentRepository.ReportResult.Sent
        val token = report.shareUrl.substringAfterLast("/s/")
        val shared = apiCall { ravi.api.shareData(token) }.getOrThrow() // public: any client may read it
        assertEquals(IncidentKind.FALL, shared.kind)
        assertEquals(10, shared.trail.size)
        assertEquals("Viktualienmarkt", shared.trail[5].landmark)
        assertTrue(apiCall { ravi.api.sharePage(token) }.getOrThrow().string().contains("<html", ignoreCase = true))

        val alerts = ravi.location.sendHeartbeat(fix(48.2, 11.6)).getOrThrow().pendingAlerts
        assertEquals(report.serverId, alerts.single().id)
        assertEquals(IncidentStatus.ACTIVE, ravi.incidents.active().getOrThrow().single { it.id == report.serverId }.status)
        assertEquals(AckState.ON_MY_WAY, ravi.incidents.acknowledge(report.serverId, AckState.ON_MY_WAY).getOrThrow().state)
        assertTrue("acked alerts are no longer pending", ravi.location.sendHeartbeat(fix(48.2, 11.6)).getOrThrow().pendingAlerts.isEmpty())

        assertTrue(asha.incidents.appendTrail(report.incidentKey, listOf(fix(48.1373, 11.5763).toDto())))
        val detail = asha.incidents.detail(report.serverId).getOrThrow()
        assertEquals(11, detail.trail.size)
        assertEquals(AckState.ON_MY_WAY, detail.acks.single().state)
        assertEquals(raviId, detail.acks.single().userId)

        assertEquals(OwnIncidentStatus.RESOLVED, asha.incidents.resolveOwn(report.incidentKey, Resolution.USER_OK)!!.status)
        assertEquals(0, asha.db.outboxDao().pendingCount(ashaSession.userId))
        assertEquals(Resolution.USER_OK, ravi.incidents.detail(report.serverId).getOrThrow().resolution)
        assertEquals("incident_closed", ravi.incidents.markHandled(report.serverId).errorCode())

        // --- token refresh against the real rotation endpoint --------------------------------------------------
        val before = asha.sessions.current()!!
        asha.time.skewMs = 16 * 60_000L // access token now looks expired: proactive refresh
        asha.auth.refreshMe().getOrThrow()
        val proactive = asha.sessions.current()!!
        assertNotEquals(before.refreshToken, proactive.refreshToken)
        asha.time.skewMs = 0
        asha.sessions.save(proactive.copy(accessToken = "not-a-valid-jwt")) // server rejects it: reactive refresh
        asha.auth.refreshMe().getOrThrow()
        assertNotEquals(proactive.refreshToken, asha.sessions.current()!!.refreshToken)

        // --- leave + logout ------------------------------------------------------------------------------------
        ravi.families.leave(created.id).getOrThrow()
        asha.location.sendHeartbeat(fix(48.1372, 11.5762)).getOrThrow()
        ravi.location.refreshFamilyLocations().getOrThrow()
        assertTrue("no co-members after leaving", ravi.location.familyLocations.value.isEmpty())

        val raviRefresh = ravi.sessions.current()!!.refreshToken
        ravi.auth.logout()
        assertNull(ravi.sessions.current())
        assertEquals("invalid_refresh_token", apiCall { ravi.api.refresh(com.eldering.safety.network.RefreshTokenRequest(raviRefresh)) }.errorCode())
        asha.auth.logout()
    }
}
