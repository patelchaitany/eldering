package com.eldering.safety.data.repository

import com.eldering.safety.network.ApiError
import com.eldering.safety.network.ApiResult
import com.eldering.safety.network.GrantDto
import com.eldering.safety.network.GrantLevel
import com.eldering.safety.network.SettingsDto
import com.eldering.safety.network.SharingState
import com.eldering.safety.testing.FakeTime
import com.eldering.safety.ui.common.IncidentDetailsReader
import java.io.IOException
import java.time.Instant
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Family recipients of my accident alert (countdown "To Mom, Dad (app) and 1 emergency contact (SMS)", E2E: the
 * countdown named only the emergency contact) and how viewers read them back ("Also alerted: You, Sister, 1 contact").
 */
class AlertRecipientsTest {

    private val time = FakeTime()
    private var settings: SettingsDto? = SettingsDto(SharingState.ON)
    private var grantsCalls = 0
    private var grants: ApiResult<List<GrantDto>> = ApiResult.Success(
        listOf(
            GrantDto("u-dad", "Dad", GrantLevel.EMERGENCY, isDefault = true),
            GrantDto("u-mom", "Mom", GrantLevel.LIVE),
            GrantDto("u-kid", "Kid", GrantLevel.OFF),
        ),
    )
    private val cache = AlertRecipientsRepository.Cache.Memory()

    private fun repo() = AlertRecipientsRepository(
        grants = {
            grantsCalls++
            grants
        },
        settings = { settings },
        cache = cache,
        time = time,
    )

    @Test
    fun `live and emergency grants get the alert, off does not`() = runBlocking {
        val r = repo()
        assertTrue("nothing known yet", r.current().isEmpty())
        r.refresh()
        assertEquals(listOf("Dad", "Mom"), r.current().map { it.name })
        assertEquals(listOf("u-dad", "u-mom"), r.current().map { it.userId })
    }

    @Test
    fun `sharing off still alerts with always-alert, and nobody without it`() = runBlocking {
        val r = repo()
        r.refresh()
        settings = SettingsDto(SharingState.OFF, alwaysAlertOnAccident = true)
        assertEquals(2, r.current().size)
        settings = SettingsDto(SharingState.PAUSED, alwaysAlertOnAccident = false)
        assertTrue(r.current().isEmpty())
        // A timed pause that has ended counts as sharing on (server rule).
        settings = SettingsDto(SharingState.PAUSED, pausedUntil = Instant.ofEpochMilli(time.nowMillis() - 1), alwaysAlertOnAccident = false)
        assertEquals(2, r.current().size)
    }

    @Test
    fun `the answer survives a new process without network, with the settings last seen`() = runBlocking {
        repo().refresh()
        settings = SettingsDto(SharingState.OFF, alwaysAlertOnAccident = false)
        repo().onSettingsChanged()
        // New process: /v1/me not loaded yet and no network.
        settings = null
        grants = ApiResult.Failure(ApiError.Network(IOException("offline")))
        val fresh = repo()
        assertTrue("last known: sharing off, no always-alert", fresh.current().isEmpty())
        settings = SettingsDto(SharingState.ON)
        assertEquals(listOf("Dad", "Mom"), fresh.current().map { it.name })
    }

    @Test
    fun `refreshIfStale goes to the network at most every 30 min, and grant edits apply at once`() = runBlocking {
        val r = repo()
        r.refreshIfStale()
        r.refreshIfStale()
        assertEquals(1, grantsCalls)
        time.advance(AlertRecipientsRepository.DEFAULT_MAX_AGE_MS)
        r.refreshIfStale()
        assertEquals(2, grantsCalls)

        r.onGrantChanged(GrantDto("u-mom", "Mom", GrantLevel.OFF))
        assertEquals(listOf("Dad"), r.current().map { it.name })
        r.clear()
        assertTrue(r.current().isEmpty())
        assertNull(cache.load())
    }

    @Test
    fun `incident details carry who was alerted and every viewer reads it from their side`() {
        val details = IncidentDetails.withRecipients(
            JsonObject(mapOf("source" to JsonPrimitive("detector"))),
            listOf(AlertRecipient("u-me", "Viewer"), AlertRecipient("u-sis", "Sister")),
            smsContacts = 1,
        )
        assertEquals("Viewer, Sister (app) and 1 emergency contact (SMS)", details["also_alerted"]!!.jsonPrimitive.content)
        assertEquals(1, details["sms_contacts"]!!.jsonPrimitive.content.toInt())
        assertEquals("u-sis", details["alerted_family"]!!.jsonArray[1].jsonObject["user_id"]!!.jsonPrimitive.content)

        val seenBySister = IncidentDetailsReader.alsoAlertedFor(details, "u-sis", "You") { n -> "$n contact" }
        assertEquals("You, Viewer, 1 contact", seenBySister)
        // Older senders only had the text.
        val old = JsonObject(mapOf("also_alerted" to JsonPrimitive("1 emergency contact (SMS)")))
        assertEquals("1 emergency contact (SMS)", IncidentDetailsReader.alsoAlertedFor(old, "u-sis", "You") { "$it" })
        assertNull(IncidentDetailsReader.alsoAlertedFor(JsonObject(emptyMap()), "u-sis", "You") { "$it" })
        // Nobody to list: details unchanged.
        val bare = JsonObject(mapOf("source" to JsonPrimitive("sos")))
        assertEquals(bare, IncidentDetails.withRecipients(bare, emptyList(), 0))
    }

    @Test
    fun `family phone book - picked number first, then an emergency contact with the same name`() {
        val book = FamilyPhoneBook(FamilyPhoneBook.Store.Memory())
        val contacts = listOf(com.eldering.safety.data.db.EmergencyContact(id = 1, name = "Dad ", phone = "+911111111111", position = 0, createdAtMs = 0))
        assertEquals("+911111111111", book.phoneFor("u-dad", "dad", contacts))
        assertNull(book.phoneFor("u-viewer", "Test Viewer", contacts))
        assertEquals("+919876543210", book.remember("u-viewer", "+91 98765-43210"))
        assertEquals("+919876543210", book.phoneFor("u-viewer", "Test Viewer", contacts))
        assertNull("not a phone number", book.remember("u-x", "call me"))
        book.clear()
        assertNull(book.phoneFor("u-viewer", "Test Viewer", contacts))
    }
}
