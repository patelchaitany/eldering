package com.eldering.safety.network

import com.eldering.safety.testing.FAMILY_ID
import com.eldering.safety.testing.INCIDENT_ID
import com.eldering.safety.testing.OTHER_USER_ID
import com.eldering.safety.testing.ServerSamples
import com.eldering.safety.testing.USER_ID
import com.eldering.safety.testing.authJson
import com.eldering.safety.testing.session
import java.time.Instant
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Wire-format contract with the Go server (decoding its exact output, encoding exactly what it parses). */
class ApiSerializationTest {

    private fun parse(s: String): JsonElement = Json.parseToJsonElement(s)

    private fun <T> assertEncodes(expectedJson: String, serializer: kotlinx.serialization.KSerializer<T>, value: T) =
        assertEquals(parse(expectedJson), parse(ApiJson.encodeToString(serializer, value)))

    @Test
    fun `timestamps accept Go RFC 3339 variants and encode as UTC`() {
        fun decode(s: String) = ApiJson.decodeFromString(InstantSerializer, "\"$s\"")
        assertEquals(Instant.parse("2026-10-13T08:15:00.123456789Z"), decode("2026-10-13T08:15:00.123456789Z"))
        assertEquals(Instant.parse("2026-10-13T02:45:00Z"), decode("2026-10-13T08:15:00+05:30"))
        assertEquals(Instant.parse("2026-10-13T08:15:00Z"), decode("2026-10-13T08:15:00Z"))
        assertThrows(SerializationException::class.java) { decode("13/10/2026") }
        assertEquals("\"2026-10-13T08:15:00.120Z\"", ApiJson.encodeToString(InstantSerializer, Instant.parse("2026-10-13T08:15:00.12Z")))
    }

    @Test
    fun `auth response`() {
        val r = ApiJson.decodeFromString(AuthResponse.serializer(), authJson("acc", "ref"))
        assertEquals("acc", r.accessToken)
        assertEquals("ref", r.refreshToken)
        assertEquals(900L, r.expiresIn)
        assertEquals(USER_ID, r.user.id)
        assertEquals("Asha", r.user.displayName)
        assertEquals(Instant.parse("2026-10-01T08:00:00Z"), r.user.createdAt)
        // SPEC minimum: {access_token, refresh_token, user}
        val minimal = """{"access_token":"a","refresh_token":"r","user":{"id":"u","email":"e@x.io","display_name":"E"}}"""
        assertEquals(0L, ApiJson.decodeFromString(AuthResponse.serializer(), minimal).expiresIn)
    }

    @Test
    fun `session expiry uses the relative lifetime, not the server clock`() {
        val r = ApiJson.decodeFromString(AuthResponse.serializer(), authJson("acc", "ref"))
        val receivedAt = 1_000_000L
        assertEquals(receivedAt + 900_000L, Session.from(r, receivedAt).accessExpiresAtMs)
        val noLifetime = r.copy(expiresIn = 0)
        assertEquals(receivedAt + Session.DEFAULT_ACCESS_TTL_MS, Session.from(noLifetime, receivedAt).accessExpiresAtMs)
    }

    @Test
    fun `me, settings and families`() {
        val me = ApiJson.decodeFromString(MeResponse.serializer(), ServerSamples.ME)
        assertEquals(SharingState.PAUSED, me.settings.sharingState)
        assertNull(me.settings.pausedUntil)
        assertEquals(FAMILY_ID, me.families.single().id)
        assertEquals(FamilyRole.ADMIN, me.families.single().role)
        assertEquals(3, me.families.single().memberCount)

        val older = ApiJson.decodeFromString(SettingsDto.serializer(), """{"sharing_state":"on","always_alert_on_accident":false}""")
        assertEquals("effective state defaults to the state", SharingState.ON, older.effectiveSharingState)
    }

    @Test
    fun `create family flattens the embedded family and carries the invite`() {
        val r = ApiJson.decodeFromString(CreateFamilyResponse.serializer(), ServerSamples.CREATE_FAMILY)
        assertEquals(FAMILY_ID, r.family.id)
        assertEquals(FamilyRole.ADMIN, r.family.role)
        assertEquals("7KQ2M9XD", r.invite.code)
        assertEquals(10, r.invite.maxUses)
    }

    @Test
    fun `members and grants`() {
        val members = ApiJson.decodeFromString(ListSerializer(FamilyMemberDto.serializer()), ServerSamples.MEMBERS)
        assertTrue(members[0].isMe)
        assertNull(members[0].myGrant)
        assertEquals(GrantLevel.EMERGENCY, members[1].myGrant)
        assertEquals(GrantLevel.LIVE, members[1].theirGrant)

        val grants = ApiJson.decodeFromString(ListSerializer(GrantDto.serializer()), ServerSamples.GRANTS)
        assertTrue(grants.single().isDefault)
        assertNull(grants.single().updatedAt)
    }

    @Test
    fun `family locations omit coordinates unless live`() {
        val list = ApiJson.decodeFromString(ListSerializer(MemberLocationDto.serializer()), ServerSamples.FAMILY_LOCATIONS)
        val (ravi, meera, dev) = list
        assertTrue(ravi.hasPosition)
        assertEquals(Activity.WALKING, ravi.activity)
        assertEquals(76, ravi.batteryPct)
        assertEquals(LocationStatus.HIDDEN, meera.status)
        assertFalse(meera.hasPosition)
        assertNull(meera.lat)
        assertEquals(LocationStatus.PAUSED, dev.status)
    }

    @Test
    fun `heartbeat request has exactly the server's keys and omits unknown values`() {
        val full = HeartbeatRequest(
            lat = 48.137154, lng = 11.576124, accuracyM = 8.5f, speedMps = 1.25f, bearingDeg = 90f,
            activity = Activity.VEHICLE, batteryPct = 64, recordedAt = Instant.parse("2026-10-13T08:00:00Z"),
        )
        assertEncodes(
            """{"lat":48.137154,"lng":11.576124,"accuracy_m":8.5,"speed_mps":1.25,"bearing_deg":90.0,"activity":"vehicle",
                "battery_pct":64,"recorded_at":"2026-10-13T08:00:00Z"}""",
            HeartbeatRequest.serializer(), full,
        )
        assertEncodes(
            """{"lat":1.0,"lng":2.0,"recorded_at":"2026-10-13T08:00:00Z"}""",
            HeartbeatRequest.serializer(),
            HeartbeatRequest(lat = 1.0, lng = 2.0, recordedAt = Instant.parse("2026-10-13T08:00:00Z")),
        )
        val response = ApiJson.decodeFromString(HeartbeatResponse.serializer(), ServerSamples.HEARTBEAT)
        assertEquals(INCIDENT_ID, response.pendingAlerts.single().id)
        assertEquals(IncidentKind.PEDESTRIAN_IMPACT, response.pendingAlerts.single().kind)
        assertEquals(6.5f, response.pendingAlerts.single().lastLocation?.accuracyM)
    }

    @Test
    fun `create incident request`() {
        val request = CreateIncidentRequest(
            kind = IncidentKind.VEHICLE_CRASH,
            detectedAt = Instant.parse("2026-10-13T07:58:00Z"),
            lat = 48.1,
            lng = 11.5,
            details = buildJsonObject { put("source", "detector"); put("confidence", 0.9) },
            trail = listOf(
                TrailPointDto(48.0, 11.4, Instant.parse("2026-10-13T07:00:00Z"), accuracyM = 5f, landmark = "Isartor"),
                TrailPointDto(48.1, 11.5, Instant.parse("2026-10-13T07:58:00Z")),
            ),
        )
        assertEncodes(
            """{"kind":"vehicle_crash","detected_at":"2026-10-13T07:58:00Z","lat":48.1,"lng":11.5,
                "details":{"source":"detector","confidence":0.9},
                "trail":[{"lat":48.0,"lng":11.4,"recorded_at":"2026-10-13T07:00:00Z","accuracy_m":5.0,"landmark":"Isartor"},
                         {"lat":48.1,"lng":11.5,"recorded_at":"2026-10-13T07:58:00Z"}]}""",
            CreateIncidentRequest.serializer(), request,
        )
        // SOS without a fix: lat/lng omitted together, empty details object and trail still sent.
        assertEncodes(
            """{"kind":"sos","detected_at":"2026-10-13T07:58:00Z","details":{},"trail":[]}""",
            CreateIncidentRequest.serializer(),
            CreateIncidentRequest(IncidentKind.SOS, Instant.parse("2026-10-13T07:58:00Z")),
        )
    }

    @Test
    fun `small incident request bodies`() {
        assertEncodes("""{"resolution":"false_alarm"}""", ResolveIncidentRequest.serializer(), ResolveIncidentRequest(Resolution.FALSE_ALARM))
        assertEncodes("""{"state":"on_my_way"}""", AckIncidentRequest.serializer(), AckIncidentRequest(AckState.ON_MY_WAY))
        assertEncodes("""{"level":"emergency"}""", SetGrantRequest.serializer(), SetGrantRequest(GrantLevel.EMERGENCY))
        assertEncodes("""{"code":"ABCD1234"}""", JoinFamilyRequest.serializer(), JoinFamilyRequest("ABCD1234"))
        assertEncodes("""{"refresh_token":"r"}""", RefreshTokenRequest.serializer(), RefreshTokenRequest("r"))
        assertEncodes(
            """{"email":"a@b.co","password":"pw123456","display_name":"A"}""",
            RegisterRequest.serializer(), RegisterRequest("a@b.co", "pw123456", "A"),
        )
    }

    @Test
    fun `settings patch distinguishes absent from explicit null`() {
        assertEquals(parse("""{"sharing_state":"paused","paused_until":null}"""),
            SettingsPatch(sharingState = SharingState.PAUSED, pausedUntil = PauseEnd.Indefinite).toJson())
        assertEquals(parse("""{"sharing_state":"paused","paused_until":"2026-10-13T10:00:00Z"}"""),
            SettingsPatch(SharingState.PAUSED, PauseEnd.At(Instant.parse("2026-10-13T10:00:00Z"))).toJson())
        assertEquals(parse("""{"always_alert_on_accident":false}"""), SettingsPatch(alwaysAlertOnAccident = false).toJson())
        assertTrue(SettingsPatch().isEmpty)
    }

    @Test
    fun `incident detail embeds the summary and carries trail, acks and details`() {
        val d = ApiJson.decodeFromString(IncidentDetailDto.serializer(), ServerSamples.INCIDENT_DETAIL)
        assertEquals(IncidentKind.FALL, d.kind)
        assertEquals(IncidentStatus.RESOLVED, d.status)
        assertEquals(Resolution.HANDLED, d.resolution)
        assertEquals(AckState.SEEN, d.myAck)
        assertNull(d.lat)
        assertEquals(USER_ID, d.resolvedBy)
        assertEquals(2, d.trail.size)
        assertEquals("Marienplatz, Altstadt", d.trail[1].landmark)
        assertNull(d.trail[0].accuracyM)
        assertEquals(AckState.ON_MY_WAY, d.acks.single().state)
        assertEquals(JsonPrimitive("detector"), d.details["source"])
        assertEquals(d.id, d.summary.id)
        assertEquals(OTHER_USER_ID, d.summary.userId)
    }

    @Test
    fun `public share feed`() {
        val s = ApiJson.decodeFromString(SharedIncidentDto.serializer(), ServerSamples.SHARE_DATA)
        assertEquals(IncidentKind.VEHICLE_CRASH, s.kind)
        assertNull(s.incidentLocation?.landmark)
        assertEquals("A9", s.latest?.landmark)
        assertTrue(s.trail.isEmpty())
    }

    @Test
    fun `unknown fields from a newer server are ignored`() {
        val withExtras = """{"status":"ok","version":"1.4.0","uptime_s":42}"""
        assertEquals("ok", ApiJson.decodeFromString(HealthDto.serializer(), withExtras).status)
    }

    @Test
    fun `secrets never appear in toString`() {
        val texts = listOf(
            session(accessToken = "SECRET-A", refreshToken = "SECRET-R").toString(),
            ApiJson.decodeFromString(AuthResponse.serializer(), authJson("SECRET-A", "SECRET-R")).toString(),
            LoginRequest("a@b.co", "SECRET-P").toString(),
            RegisterRequest("a@b.co", "SECRET-P", "A").toString(),
            RefreshTokenRequest("SECRET-R").toString(),
            ApiJson.decodeFromString(CreateIncidentResponse.serializer(), ServerSamples.CREATE_INCIDENT).toString(),
        )
        texts.forEach { assertFalse(it, it.contains("SECRET") || it.contains("/s/")) }
    }
}
