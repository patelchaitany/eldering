package com.eldering.safety.service

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.content.pm.ServiceInfo
import android.location.LocationManager
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.eldering.safety.MainActivity
import com.eldering.safety.network.IncidentKind
import com.eldering.safety.network.IncidentStatus
import com.eldering.safety.network.IncidentSummaryDto
import com.eldering.safety.testing.FakeTime
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Family-alert notification (viewer side), its de-duplication, the ongoing notification and FGS type choice. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class ServiceNotificationsTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val nm = app.getSystemService(NotificationManager::class.java)

    @Before
    fun setUp() {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    }

    private val alert = IncidentSummaryDto(
        id = "9f8e7d6c-5b4a-4392-8170-6f5e4d3c2b1a",
        userId = "u1",
        displayName = "Ravi",
        kind = IncidentKind.FALL,
        status = IncidentStatus.ACTIVE,
        detectedAt = Instant.ofEpochMilli(FakeTime.T0),
    )

    @Test
    fun `family alert notification opens the family-alert screen for that incident`() {
        ServiceNotifications.showFamilyAlert(app, alert)

        val posted = shadowOf(nm).allNotifications.single()
        assertEquals(ServiceNotifications.CH_FAMILY, posted.channelId)
        assertEquals("Ravi may have fallen", posted.extras.getString(NotificationCompat.EXTRA_TITLE))
        assertEquals(NotificationCompat.CATEGORY_ALARM, posted.category)
        val intent = shadowOf(posted.contentIntent).savedIntent
        assertEquals(MainActivity::class.java.name, intent.component!!.className)
        assertEquals(alert.id, intent.getStringExtra(MainActivity.EXTRA_INCIDENT_ID))
        assertEquals(NotificationManager.IMPORTANCE_HIGH, nm.getNotificationChannel(ServiceNotifications.CH_FAMILY).importance)
    }

    @Test
    fun `each family incident is notified once, for 72 hours`() {
        assertTrue(ProtectionPrefs.markAlertNotified(app, "a", FakeTime.T0))
        assertFalse(ProtectionPrefs.markAlertNotified(app, "a", FakeTime.T0 + 60_000))
        assertTrue(ProtectionPrefs.markAlertNotified(app, "b", FakeTime.T0 + 60_000))
        assertTrue(ProtectionPrefs.markAlertNotified(app, "a", FakeTime.T0 + 73 * 3_600_000L))
    }

    @Test
    fun `ongoing notification is low-key and explains the signed-out mode`() {
        val n = ServiceNotifications.ongoing(app, modeLabel = "Walking", signedIn = false)
        assertEquals(ServiceNotifications.CH_PROTECTION, n.channelId)
        assertEquals("Protection on", n.extras.getString(NotificationCompat.EXTRA_TITLE))
        assertTrue(n.extras.getCharSequence(NotificationCompat.EXTRA_TEXT).toString().startsWith("Not signed in"))
        assertEquals(NotificationManager.IMPORTANCE_LOW, nm.getNotificationChannel(ServiceNotifications.CH_PROTECTION).importance)
        // Not exempt from battery optimization in Robolectric: the notification offers the exemption.
        assertEquals("Keep running", n.actions.single().title.toString())

        val signedIn = ServiceNotifications.ongoing(app, modeLabel = "Walking", signedIn = true)
        assertEquals("Walking · watching for falls and crashes", signedIn.extras.getCharSequence(NotificationCompat.EXTRA_TEXT).toString())
    }

    @Test
    fun `location type only with location access, specialUse always`() {
        shadowOf(app.getSystemService(LocationManager::class.java)).setLocationEnabled(true)
        val none = FgsTypes.current(app, fromForeground = true)
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE, none)

        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        assertEquals("while-in-use only: not from the background", ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE, FgsTypes.current(app, fromForeground = false))
        assertEquals(
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
            FgsTypes.current(app, fromForeground = true),
        )
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        assertEquals("location|specialUse", FgsTypes.describe(FgsTypes.current(app, fromForeground = false)))

        shadowOf(app.getSystemService(LocationManager::class.java)).setLocationEnabled(false)
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE, FgsTypes.current(app, fromForeground = true))
    }
}
