package com.eldering.safety.network

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.eldering.safety.testing.FAMILY_ID
import com.eldering.safety.testing.FakeTime
import com.eldering.safety.testing.INCIDENT_ID
import com.eldering.safety.testing.OTHER_USER_ID
import com.eldering.safety.testing.ServerSamples
import com.eldering.safety.testing.authJson
import com.eldering.safety.testing.json
import com.eldering.safety.testing.noContent
import com.eldering.safety.testing.session
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** One round trip per endpoint of SPEC "HTTP API"; fails if an [ElderingApi] function is left untested. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class ElderingApiEndpointsTest {

    private val server = MockWebServer()
    private val sessions = InMemorySessions(session(accessToken = "access-1"))
    private lateinit var api: ElderingApi
    private val exercised = mutableSetOf<String>()

    @Before
    fun setUp() {
        server.start()
        api = ElderingNetwork(server.url("/").toString(), sessions, FakeTime()).api
    }

    @After
    fun tearDown() = server.close()

    private suspend fun <T> exchange(
        name: String,
        response: MockResponse,
        call: suspend ElderingApi.() -> T,
    ): Pair<T, RecordedRequest> {
        server.enqueue(response)
        val value = api.call()
        exercised += name
        return value to server.takeRequest(5, TimeUnit.SECONDS)!!
    }

    private fun RecordedRequest.assertRoute(method: String, path: String, authenticated: Boolean = true) {
        assertEquals(method, this.method)
        assertEquals(path, url.encodedPath)
        if (authenticated) assertEquals("Bearer access-1", headers["Authorization"]) else assertNull(headers["Authorization"])
    }

    private fun RecordedRequest.assertJsonBody(expected: String) {
        assertTrue("Content-Type: ${headers["Content-Type"]}", headers["Content-Type"]!!.startsWith("application/json"))
        assertEquals(Json.parseToJsonElement(expected), Json.parseToJsonElement(body!!.utf8()))
    }

    @Test
    fun `every endpoint`() = runTest {
        // --- auth (no bearer token) ---
        exchange("register", json(201, authJson("a", "r"))) {
            register(RegisterRequest("asha@example.com", "correct horse", "Asha"))
        }.let { (r, req) ->
            req.assertRoute("POST", "/v1/auth/register", authenticated = false)
            req.assertJsonBody("""{"email":"asha@example.com","password":"correct horse","display_name":"Asha"}""")
            assertEquals("a", r.accessToken)
        }
        exchange("login", json(200, authJson("a", "r"))) { login(LoginRequest("asha@example.com", "pw")) }.second.let {
            it.assertRoute("POST", "/v1/auth/login", authenticated = false)
            it.assertJsonBody("""{"email":"asha@example.com","password":"pw"}""")
        }
        exchange("refresh", json(200, authJson("a2", "r2"))) { refresh(RefreshTokenRequest("r")) }.second.let {
            it.assertRoute("POST", "/v1/auth/refresh", authenticated = false)
            it.assertJsonBody("""{"refresh_token":"r"}""")
        }
        exchange("logout", noContent()) { logout(RefreshTokenRequest("r2")) }.second.let {
            it.assertRoute("POST", "/v1/auth/logout", authenticated = false)
            it.assertJsonBody("""{"refresh_token":"r2"}""")
        }

        // --- me ---
        exchange("me", json(200, ServerSamples.ME)) { me() }.let { (me, req) ->
            req.assertRoute("GET", "/v1/me")
            assertEquals(FAMILY_ID, me.families.single().id)
        }
        exchange("updateSettings", json(200, ServerSamples.SETTINGS)) {
            updateSettings(SettingsPatch(SharingState.PAUSED, PauseEnd.Indefinite).toJson())
        }.let { (s, req) ->
            req.assertRoute("PATCH", "/v1/me/settings")
            req.assertJsonBody("""{"sharing_state":"paused","paused_until":null}""")
            assertEquals(SharingState.PAUSED, s.sharingState)
        }

        // --- families ---
        exchange("createFamily", json(201, ServerSamples.CREATE_FAMILY)) { createFamily(FamilyNameRequest("Rivera family")) }.let { (r, req) ->
            req.assertRoute("POST", "/v1/families")
            req.assertJsonBody("""{"name":"Rivera family"}""")
            assertEquals("7KQ2M9XD", r.invite.code)
        }
        exchange("renameFamily", json(200, ServerSamples.FAMILY)) { renameFamily(FAMILY_ID, FamilyNameRequest("Riveras")) }.second.let {
            it.assertRoute("PATCH", "/v1/families/$FAMILY_ID")
            it.assertJsonBody("""{"name":"Riveras"}""")
        }
        exchange("createInvite", json(201, ServerSamples.INVITE)) { createInvite(FAMILY_ID) }.let { (inv, req) ->
            req.assertRoute("POST", "/v1/families/$FAMILY_ID/invites")
            assertEquals(0L, req.bodySize)
            assertEquals("H3J8W0QZ", inv.code)
        }
        exchange("joinFamily", json(200, ServerSamples.FAMILY)) { joinFamily(JoinFamilyRequest("H3J8W0QZ")) }.second.let {
            it.assertRoute("POST", "/v1/families/join")
            it.assertJsonBody("""{"code":"H3J8W0QZ"}""")
        }
        exchange("familyMembers", json(200, ServerSamples.MEMBERS)) { familyMembers(FAMILY_ID) }.let { (m, req) ->
            req.assertRoute("GET", "/v1/families/$FAMILY_ID/members")
            assertEquals(2, m.size)
        }
        exchange("removeFamilyMember", noContent()) { removeFamilyMember(FAMILY_ID, OTHER_USER_ID) }.second
            .assertRoute("DELETE", "/v1/families/$FAMILY_ID/members/$OTHER_USER_ID")

        // --- grants ---
        exchange("grants", json(200, ServerSamples.GRANTS)) { grants() }.second.assertRoute("GET", "/v1/grants")
        exchange("setGrant", json(200, ServerSamples.GRANT)) { setGrant(OTHER_USER_ID, SetGrantRequest(GrantLevel.LIVE)) }.let { (g, req) ->
            req.assertRoute("PUT", "/v1/grants/$OTHER_USER_ID")
            req.assertJsonBody("""{"level":"live"}""")
            assertEquals(GrantLevel.LIVE, g.level)
        }

        // --- location ---
        val at = Instant.parse("2026-10-13T07:59:59Z")
        exchange("heartbeat", json(200, ServerSamples.HEARTBEAT)) {
            heartbeat(HeartbeatRequest(lat = 48.1, lng = 11.5, accuracyM = 9f, activity = Activity.WALKING, batteryPct = 80, recordedAt = at))
        }.let { (hb, req) ->
            req.assertRoute("POST", "/v1/location/heartbeat")
            req.assertJsonBody("""{"lat":48.1,"lng":11.5,"accuracy_m":9.0,"activity":"walking","battery_pct":80,"recorded_at":"2026-10-13T07:59:59Z"}""")
            assertEquals(1, hb.pendingAlerts.size)
        }
        exchange("familyLocations", json(200, ServerSamples.FAMILY_LOCATIONS)) { familyLocations() }.second
            .assertRoute("GET", "/v1/family/locations")

        // --- incidents ---
        exchange("createIncident", json(201, ServerSamples.CREATE_INCIDENT)) {
            createIncident(
                "key-123",
                CreateIncidentRequest(
                    kind = IncidentKind.FALL,
                    detectedAt = at,
                    lat = 48.1,
                    lng = 11.5,
                    details = buildJsonObject { put("source", "sos") },
                    trail = listOf(TrailPointDto(48.0, 11.4, at, 6f, "Isartor")),
                ),
            )
        }.let { (r, req) ->
            req.assertRoute("POST", "/v1/incidents")
            assertEquals("key-123", req.headers["Idempotency-Key"])
            req.assertJsonBody(
                """{"kind":"fall","detected_at":"2026-10-13T07:59:59Z","lat":48.1,"lng":11.5,"details":{"source":"sos"},
                    "trail":[{"lat":48.0,"lng":11.4,"recorded_at":"2026-10-13T07:59:59Z","accuracy_m":6.0,"landmark":"Isartor"}]}""",
            )
            assertEquals(INCIDENT_ID, r.id)
            assertTrue(r.shareUrl.contains("/s/"))
        }
        exchange("appendIncidentTrail", json(200, """{"accepted":1}""")) {
            appendIncidentTrail(INCIDENT_ID, AppendTrailRequest(listOf(TrailPointDto(48.2, 11.6, at))))
        }.let { (r, req) ->
            req.assertRoute("POST", "/v1/incidents/$INCIDENT_ID/trail")
            req.assertJsonBody("""{"points":[{"lat":48.2,"lng":11.6,"recorded_at":"2026-10-13T07:59:59Z"}]}""")
            assertEquals(1, r.accepted)
        }
        exchange("resolveIncident", json(200, ServerSamples.INCIDENT_SUMMARY)) {
            resolveIncident(INCIDENT_ID, ResolveIncidentRequest(Resolution.USER_OK))
        }.second.let {
            it.assertRoute("POST", "/v1/incidents/$INCIDENT_ID/resolve")
            it.assertJsonBody("""{"resolution":"user_ok"}""")
        }
        exchange("ackIncident", json(200, ServerSamples.ACK)) { ackIncident(INCIDENT_ID, AckIncidentRequest(AckState.SEEN)) }.second.let {
            it.assertRoute("POST", "/v1/incidents/$INCIDENT_ID/ack")
            it.assertJsonBody("""{"state":"seen"}""")
        }
        exchange("activeIncidents", json(200, "[${ServerSamples.INCIDENT_SUMMARY}]")) { activeIncidents() }.let { (list, req) ->
            req.assertRoute("GET", "/v1/incidents/active")
            assertEquals(INCIDENT_ID, list.single().id)
        }
        exchange("incident", json(200, ServerSamples.INCIDENT_DETAIL)) { incident(INCIDENT_ID) }.second
            .assertRoute("GET", "/v1/incidents/$INCIDENT_ID")

        // --- public ---
        val html = MockResponse.Builder().code(200).addHeader("Content-Type", "text/html; charset=utf-8").body("<!doctype html><title>Eldering</title>").build()
        exchange("sharePage", html) { sharePage("tok_EN-123").string() }.let { (body, req) ->
            req.assertRoute("GET", "/s/tok_EN-123", authenticated = false)
            assertTrue(body.startsWith("<!doctype html>"))
        }
        exchange("shareData", json(200, ServerSamples.SHARE_DATA)) { shareData("tok_EN-123") }.second
            .assertRoute("GET", "/s/tok_EN-123/data", authenticated = false)
        exchange("health", json(200, """{"status":"ok"}""")) { health() }.let { (h, req) ->
            req.assertRoute("GET", "/healthz", authenticated = false)
            assertEquals("ok", h.status)
        }

        val declared = ElderingApi::class.java.declaredMethods.filterNot { it.isSynthetic }.map { it.name }.toSet()
        assertEquals("endpoints without a round-trip test", emptySet<String>(), declared - exercised)
    }
}

/** Minimal [SessionProvider] for HTTP-level tests. */
class InMemorySessions(initial: Session?) : SessionProvider {
    @Volatile
    var session: Session? = initial

    override fun currentSessionBlocking(): Session? = session

    @Synchronized
    override fun replaceSessionBlocking(expectedRefreshToken: String, session: Session): Boolean {
        if (this.session?.refreshToken != expectedRefreshToken) return false
        this.session = session
        return true
    }

    @Synchronized
    override fun clearSessionBlocking(expectedRefreshToken: String): Boolean {
        if (session?.refreshToken != expectedRefreshToken) return false
        session = null
        return true
    }
}
