package com.eldering.safety.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.eldering.detection.DetectionEvent
import com.eldering.detection.EventKind
import com.eldering.detection.Mode
import com.eldering.safety.alert.EmergencyNumbers
import com.eldering.safety.data.db.DetectionOutcome
import com.eldering.safety.data.db.ElderingDatabase
import com.eldering.safety.data.db.FeedbackLabel
import com.eldering.safety.data.session.SessionStore
import com.eldering.safety.data.trail.TrailPoint
import com.eldering.safety.network.Activity
import com.eldering.safety.network.ApiError
import com.eldering.safety.network.ElderingNetwork
import com.eldering.safety.network.GrantLevel
import com.eldering.safety.network.SharingState
import com.eldering.safety.network.getOrThrow
import com.eldering.safety.testing.FAMILY_ID
import com.eldering.safety.testing.FakeTime
import com.eldering.safety.testing.OTHER_USER_ID
import com.eldering.safety.testing.ServerSamples
import com.eldering.safety.testing.USER_ID
import com.eldering.safety.testing.authJson
import com.eldering.safety.testing.errorJson
import com.eldering.safety.testing.inMemoryDatabase
import com.eldering.safety.testing.json
import com.eldering.safety.testing.noContent
import com.eldering.safety.testing.testCipher
import com.eldering.safety.testing.testDataStore
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
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
class RepositoriesTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val server = MockWebServer()
    private val time = FakeTime()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val discarded = mutableListOf<String>()
    private val meStore = MeStore()
    private lateinit var db: ElderingDatabase
    private lateinit var sessions: SessionStore
    private lateinit var auth: AuthRepository
    private lateinit var families: FamilyRepository
    private lateinit var sharing: SharingRepository
    private lateinit var location: LocationRepository
    private lateinit var api: com.eldering.safety.network.ElderingApi

    @Before
    fun setUp() {
        server.start()
        db = inMemoryDatabase()
        sessions = SessionStore(testDataStore(tmp.root, scope), testCipher())
        api = ElderingNetwork(server.url("/").toString(), sessions, time).api
        auth = AuthRepository(api, sessions, meStore, time, onAccountDataDiscarded = { discarded += it })
        families = FamilyRepository(api, meStore, sessions)
        sharing = SharingRepository(api, meStore)
        location = LocationRepository(api, time)
    }

    @After
    fun tearDown() {
        server.close()
        db.close()
        scope.cancel()
    }

    private fun takeRequest() = server.takeRequest(5, TimeUnit.SECONDS)!!

    private fun signedIn() = runBlocking {
        server.enqueue(json(200, authJson("access-1", "refresh-1")))
        auth.login("asha@example.com", "pw").getOrThrow()
        takeRequest()
        server.enqueue(json(200, ServerSamples.ME))
        auth.refreshMe().getOrThrow()
        takeRequest()
    }

    @Test
    fun `login stores the session and logout revokes it`() = runBlocking {
        server.enqueue(json(200, authJson("access-1", "refresh-1")))
        val session = auth.login("  asha@example.com ", "pw").getOrThrow()
        assertEquals(USER_ID, session.userId)
        assertEquals("access-1", sessions.current()?.accessToken)
        assertTrue(Json.parseToJsonElement(takeRequest().body!!.utf8()).jsonObject["email"] == JsonPrimitive("asha@example.com"))
        assertTrue(auth.isSignedIn.first())

        server.enqueue(noContent())
        auth.logout()

        assertNull(sessions.current())
        assertFalse(auth.isSignedIn.first())
        assertEquals(listOf(USER_ID), discarded)
        val revoke = takeRequest()
        assertEquals("/v1/auth/logout", revoke.url.encodedPath)
        assertTrue(revoke.body!!.utf8().contains("refresh-1"))
    }

    @Test
    fun `logout works offline`() = runBlocking {
        signedIn()
        server.close()
        auth.logout()
        assertNull(sessions.current())
        assertNull(meStore.me.value)
    }

    @Test
    fun `sign-out stops protection, then wipes the phone's personal data`() = runBlocking {
        // E2E: after sign-out the trail, detection log and contacts stayed, and the service kept recording.
        val events = mutableListOf<String>()
        val a = AuthRepository(
            api, sessions, meStore, time,
            onAccountDataDiscarded = { events += "account:$it" },
            onDeviceDataDiscarded = { events += "device" },
            onSignedOut = { events += "stop-protection" },
        )
        server.enqueue(json(200, authJson("access-1", "refresh-1")))
        a.login("asha@example.com", "pw").getOrThrow()
        takeRequest()
        assertEquals("first account on a fresh phone: whatever nobody owned is dropped", listOf("device"), events)
        assertEquals(USER_ID, sessions.dataOwner())

        events.clear()
        server.enqueue(noContent())
        a.logout()
        assertEquals(listOf("stop-protection", "account:$USER_ID", "device"), events)
        assertNull(sessions.dataOwner())
    }

    @Test
    fun `an expired session keeps the data for the same account, never for another one`() = runBlocking {
        val events = mutableListOf<String>()
        val a = AuthRepository(
            api, sessions, meStore, time,
            onAccountDataDiscarded = { events += "account:$it" },
            onDeviceDataDiscarded = { events += "device" },
            onSignedOut = { events += "stop-protection" },
        )
        server.enqueue(json(200, authJson("access-1", "refresh-1")))
        a.login("asha@example.com", "pw").getOrThrow()
        takeRequest()
        events.clear()

        sessions.clear() // the refresh token was rejected: signed out, but not by the user
        server.enqueue(json(200, authJson("access-2", "refresh-2")))
        a.login("asha@example.com", "pw").getOrThrow()
        takeRequest()
        assertEquals("same account again: its trail stays", emptyList<String>(), events)

        sessions.clear()
        server.enqueue(json(200, authJson("access-x", "refresh-x", userId = OTHER_USER_ID)))
        a.login("ravi@example.com", "pw").getOrThrow()
        assertEquals("another account: the previous one's last hour must not ride along", listOf("account:$USER_ID", "device"), events)
        assertEquals(OTHER_USER_ID, sessions.dataOwner())
        assertTrue("no explicit sign-out happened", "stop-protection" !in events)
    }

    @Test
    fun `wrong password is a typed error and stores nothing`() = runBlocking {
        server.enqueue(errorJson(401, "invalid_credentials"))
        val error = auth.login("asha@example.com", "nope").let { (it as com.eldering.safety.network.ApiResult.Failure).error }
        assertEquals("invalid_credentials", (error as ApiError.Http).code)
        assertNull(sessions.current())
    }

    @Test
    fun `signing in as another account discards the previous account's local data`() = runBlocking {
        signedIn()
        server.enqueue(json(200, authJson("access-x", "refresh-x", userId = OTHER_USER_ID)))
        auth.login("ravi@example.com", "pw").getOrThrow()
        assertEquals(listOf(USER_ID), discarded)
        assertNull("profile cache reset", meStore.me.value)
    }

    @Test
    fun `family mutations keep the cached families in sync`() = runBlocking {
        signedIn()
        assertEquals(1, families.families.first().size)

        server.enqueue(json(201, ServerSamples.INVITE))
        assertEquals("H3J8W0QZ", families.createInvite(FAMILY_ID).getOrThrow().code)
        assertEquals("/v1/families/$FAMILY_ID/invites", takeRequest().url.encodedPath)

        server.enqueue(json(200, ServerSamples.FAMILY))
        families.join(" h3j8-w0qz ").getOrThrow()
        assertTrue(takeRequest().body!!.utf8().contains("\"H3J8W0QZ\""))
        assertEquals("joining twice keeps one entry", 1, families.families.first().size)

        server.enqueue(noContent())
        families.removeMember(FAMILY_ID, OTHER_USER_ID).getOrThrow()
        assertEquals(2, families.families.first().single().memberCount)
        assertEquals("/v1/families/$FAMILY_ID/members/$OTHER_USER_ID", takeRequest().url.encodedPath)

        server.enqueue(noContent())
        families.leave(FAMILY_ID).getOrThrow()
        assertEquals("/v1/families/$FAMILY_ID/members/$USER_ID", takeRequest().url.encodedPath)
        assertTrue(families.families.first().isEmpty())
    }

    @Test
    fun `sharing settings and grants`() = runBlocking {
        signedIn()

        server.enqueue(json(200, """{"sharing_state":"paused","paused_until":"2026-10-13T10:00:00Z","always_alert_on_accident":true,"effective_sharing_state":"paused"}"""))
        sharing.pauseSharing(Instant.parse("2026-10-13T10:00:00Z")).getOrThrow()
        val patch = takeRequest()
        assertEquals("PATCH", patch.method)
        assertEquals(
            Json.parseToJsonElement("""{"sharing_state":"paused","paused_until":"2026-10-13T10:00:00Z"}"""),
            Json.parseToJsonElement(patch.body!!.utf8()),
        )
        assertEquals(Instant.parse("2026-10-13T10:00:00Z"), sharing.settings.first()?.pausedUntil)

        server.enqueue(json(200, ServerSamples.GRANT))
        assertEquals(GrantLevel.LIVE, sharing.setGrant(OTHER_USER_ID, GrantLevel.LIVE).getOrThrow().level)
        assertEquals("/v1/grants/$OTHER_USER_ID", takeRequest().url.encodedPath)
    }

    @Test
    fun `heartbeat sanitizes values and surfaces pending alerts and clock offset`() = runBlocking {
        signedIn()
        server.enqueue(json(200, ServerSamples.HEARTBEAT))
        val point = TrailPoint(
            recordedAtMs = time.nowMillis(),
            lat = 48.1,
            lng = 11.5,
            accuracyM = Float.NaN,
            speedMps = -1f,
            bearingDeg = 370f,
            activity = Activity.RUNNING,
        )

        location.sendHeartbeat(point, batteryPct = 140).getOrThrow()

        val body = Json.parseToJsonElement(takeRequest().body!!.utf8()).jsonObject
        assertNull(body["accuracy_m"])
        assertNull(body["speed_mps"])
        assertEquals(JsonPrimitive(10.0f), body["bearing_deg"])
        assertEquals(JsonPrimitive(100), body["battery_pct"])
        assertEquals(JsonPrimitive("running"), body["activity"])
        assertEquals(1, location.pendingAlerts.value.size)
        assertEquals(250L, location.serverClockOffsetMs)

        server.enqueue(json(200, ServerSamples.FAMILY_LOCATIONS))
        location.refreshFamilyLocations().getOrThrow()
        assertEquals(1, location.familyLocations.value.count { it.hasPosition })
    }

    @Test
    fun `settings are visible through the shared me cache`() = runBlocking {
        signedIn()
        assertEquals(SharingState.PAUSED, sharing.settings.first()?.sharingState)
    }

    @Test
    fun `emergency contacts are validated, normalized and ordered`() = runBlocking {
        val contacts = ContactsRepository(db.emergencyContactDao(), time)

        val mom = contacts.add("  Mom ", "+49 (151) 234-5678", relation = "mother") as ContactsRepository.SaveResult.Saved
        assertEquals("+491512345678", mom.contact.phone)
        assertEquals("Mom", mom.contact.name)
        val dad = (contacts.add("Dad", "0049 151 999 0000") as ContactsRepository.SaveResult.Saved).contact
        assertEquals("+491519990000", dad.phone)

        assertEquals(ContactsRepository.SaveResult.Invalid(ContactsRepository.Problem.DUPLICATE_PHONE), contacts.add("Mum again", "+491512345678"))
        assertEquals(ContactsRepository.SaveResult.Invalid(ContactsRepository.Problem.PHONE_INVALID), contacts.add("X", "call me"))
        assertEquals(ContactsRepository.SaveResult.Invalid(ContactsRepository.Problem.NAME_BLANK), contacts.add("  ", "+49112233445"))

        contacts.update(dad.copy(smsEnabled = false))
        assertEquals(listOf("Mom"), contacts.smsRecipients().map { it.name })

        contacts.reorder(listOf(dad.id, mom.contact.id))
        assertEquals(listOf("Dad", "Mom"), contacts.contacts.first().map { it.name })

        assertTrue(contacts.remove(dad.id))
        assertEquals(listOf("Mom"), contacts.all().map { it.name })
    }

    @Test
    fun `emergency services are never saved as SMS contacts`() = runBlocking {
        val contacts = ContactsRepository(db.emergencyContactDao(), time)
        for (number in listOf("112", "911", "999", "000", "+112", "1-1-2", "110", "119", "100", "101", "102", "108")) {
            assertEquals(number, ContactsRepository.SaveResult.Invalid(ContactsRepository.Problem.EMERGENCY_NUMBER), contacts.add("Emergency", number))
        }
        val friend = (contacts.add("Friend", "+15550100001") as ContactsRepository.SaveResult.Saved).contact
        assertEquals(
            ContactsRepository.SaveResult.Invalid(ContactsRepository.Problem.EMERGENCY_NUMBER),
            contacts.update(friend.copy(phone = "911")),
        )
        // The platform check (SIM / network list) is consulted too.
        val withPlatform = ContactsRepository(db.emergencyContactDao(), time, isEmergencyNumber = { it == "+15550100099" })
        assertEquals(ContactsRepository.SaveResult.Invalid(ContactsRepository.Problem.EMERGENCY_NUMBER), withPlatform.add("Local", "+15550100099"))
        // One saved by an older version is never texted.
        db.emergencyContactDao().insert(friend.copy(id = 0, name = "Old", phone = "112", position = 9))
        assertEquals(listOf("Friend"), contacts.smsRecipients().map { it.name })
        assertTrue(EmergencyNumbers.isStaticEmergencyNumber("112"))
        assertFalse(EmergencyNumbers.isStaticEmergencyNumber("+911234567890"))
    }

    @Test
    fun `emergency contacts need the country code - national numbers are converted or refused`() = runBlocking {
        // E2E: "5550100" was accepted although the hint asks for the country code.
        val strict = ContactsRepository(db.emergencyContactDao(), time)
        assertEquals(ContactsRepository.SaveResult.Invalid(ContactsRepository.Problem.PHONE_NO_COUNTRY_CODE), strict.add("Neighbour", "5550100"))
        assertEquals(ContactsRepository.SaveResult.Invalid(ContactsRepository.Problem.PHONE_NO_COUNTRY_CODE), strict.add("Neighbour", "098000 00210"))
        assertEquals(ContactsRepository.SaveResult.Invalid(ContactsRepository.Problem.PHONE_NO_COUNTRY_CODE), strict.add("Short", "+1234"))
        // On the phone a national number is converted with the SIM's country (DevicePhoneNumbers).
        val withSim = ContactsRepository(
            db.emergencyContactDao(), time,
            toInternational = { n -> PhoneNumbers.international(n) ?: if (n == "09800000210") "+919800000210" else null },
        )
        val saved = withSim.add("Neighbour", "098000 00210") as ContactsRepository.SaveResult.Saved
        assertEquals("+919800000210", saved.contact.phone)
        // A number saved before the rule (no country code) can still be renamed or muted without retyping it.
        val legacy = saved.contact.copy(id = 0, name = "Legacy", phone = "5550100", position = 5)
        val legacyId = db.emergencyContactDao().insert(legacy)
        assertTrue(strict.update(legacy.copy(id = legacyId, smsEnabled = false)) is ContactsRepository.SaveResult.Saved)
        assertEquals(
            ContactsRepository.SaveResult.Invalid(ContactsRepository.Problem.PHONE_NO_COUNTRY_CODE),
            strict.update(legacy.copy(id = legacyId, phone = "5550199")),
        )
        assertEquals("+4915112345678", PhoneNumbers.international("+4915112345678"))
        assertNull(PhoneNumbers.international("015112345678"))
    }

    @Test
    fun `phone number normalization`() {
        assertEquals("+919800000210", PhoneNumbers.normalize("+91 98000-00210"))
        assertEquals("+919800000210", PhoneNumbers.normalize("0091 98000 00210"))
        assertEquals("09800000210", PhoneNumbers.normalize("098000.00210"))
        assertEquals("112", PhoneNumbers.normalize("112"))
        assertNull(PhoneNumbers.normalize(""))
        assertNull(PhoneNumbers.normalize("12"))
        assertNull(PhoneNumbers.normalize("+"))
        assertNull(PhoneNumbers.normalize("555-CALL-NOW"))
        assertNull(PhoneNumbers.normalize("1".repeat(18)))
    }

    @Test
    fun `detection events keep features and false-alarm feedback`() = runBlocking {
        val events = DetectionEventRepository(db.detectionEventDao(), time)
        val event = DetectionEvent(
            kind = EventKind.FALL,
            confidence = 0.82f,
            triggeredAtNanos = 123_456_789L,
            features = mapOf("peak_g" to 6.5f, "free_fall_ms" to 310f, "gyro_deg" to Float.NaN),
            reason = "free-fall then impact",
        )
        val id = events.record(event, Mode.WALKING, triggeredAtMs = time.nowMillis(), lat = 48.1, lng = 11.5, windowJson = "[]")
        events.setOutcome(id, DetectionOutcome.CANCELLED)
        events.label(id, FeedbackLabel.FALSE_ALARM, note = "dropped phone on the bed")

        val stored = events.get(id)!!
        assertEquals("FALL", stored.kind)
        assertEquals("WALKING", stored.mode)
        assertEquals(6.5f, stored.features["peak_g"])
        assertTrue(stored.features["gyro_deg"]!!.isNaN())
        assertEquals(DetectionOutcome.CANCELLED, stored.outcome)
        assertEquals(FeedbackLabel.FALSE_ALARM, stored.label)
        assertEquals(time.nowMillis(), stored.labeledAtMs)
        assertEquals(listOf(id), events.labeled().map { it.id })

        // Details sent to the server drop non-finite values (JSON cannot carry NaN).
        val details = IncidentDetails.fromDetection(event, Mode.WALKING)
        val features = details["features"]!!.jsonObject
        assertEquals(setOf("free_fall_ms", "peak_g"), features.keys)
        assertEquals(JsonPrimitive("walking"), details["mode"])

        time.advance(DetectionEventRepository.LABELED_RETENTION_MS + 1)
        assertEquals(1, events.prune())
    }
}
