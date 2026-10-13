package com.eldering.safety.service

import com.eldering.detection.Mode
import com.eldering.safety.data.trail.TrailPoint
import com.eldering.safety.network.Activity
import com.eldering.safety.network.ApiError
import com.eldering.safety.network.ApiResult
import com.eldering.safety.network.HeartbeatResponse
import com.eldering.safety.network.IncidentKind
import com.eldering.safety.network.IncidentStatus
import com.eldering.safety.network.IncidentSummaryDto
import com.eldering.safety.network.SettingsDto
import com.eldering.safety.network.SharingState
import com.eldering.safety.testing.FakeTime
import java.io.IOException
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Heartbeat gating (signed in + sharing on), pacing, and the pending-alert hand-off to the family notification. */
class HeartbeatControllerTest {

    private val time = FakeTime()
    private var signedIn = true
    private var settings: SettingsDto? = SettingsDto(SharingState.ON)
    private var refreshes = 0
    private val sent = mutableListOf<Pair<TrailPoint, Int?>>()
    private var response: ApiResult<HeartbeatResponse> = ApiResult.Success(HeartbeatResponse(emptyList(), Instant.ofEpochMilli(FakeTime.T0)))
    private val notified = mutableListOf<List<IncidentSummaryDto>>()

    private val controller = HeartbeatController(
        signedIn = { signedIn },
        settings = { settings },
        refreshSettings = {
            refreshes++
            ApiResult.Failure(ApiError.Network(IOException("offline")))
        },
        send = { point, battery ->
            sent += point to battery
            response
        },
        battery = { 81 },
        onPendingAlerts = { notified += it },
        time = time,
    )

    private val point = TrailPoint(FakeTime.T0 - 1_000, 48.137, 11.575, 7f)

    private fun alert(id: String, mine: Boolean = false, status: IncidentStatus = IncidentStatus.ACTIVE) = IncidentSummaryDto(
        id = id, userId = "u-$id", displayName = "Ravi", kind = IncidentKind.FALL, status = status,
        detectedAt = Instant.ofEpochMilli(FakeTime.T0 - 60_000), isMine = mine,
    )

    @Test
    fun `nothing is sent while signed out or not sharing`() = runBlocking {
        signedIn = false
        assertFalse(controller.maybeSend(point, Mode.WALKING, batterySaver = false))
        assertEquals("skipped: signed out", controller.lastOutcome)

        signedIn = true
        settings = SettingsDto(SharingState.PAUSED)
        assertFalse(controller.maybeSend(point, Mode.WALKING, batterySaver = false))
        settings = SettingsDto(SharingState.OFF)
        assertFalse(controller.maybeSend(point, Mode.WALKING, batterySaver = false, force = true))
        assertTrue(sent.isEmpty())
    }

    @Test
    fun `unknown sharing state is refreshed from the server, rate limited`() = runBlocking {
        settings = null
        assertFalse(controller.maybeSend(point, Mode.WALKING, batterySaver = false))
        assertFalse(controller.maybeSend(point, Mode.WALKING, batterySaver = false))
        assertEquals(1, refreshes)
        time.advance(5 * 60_000L)
        controller.maybeSend(point, Mode.WALKING, batterySaver = false)
        assertEquals(2, refreshes)
    }

    @Test
    fun `sends with the mode as activity and battery, then paces by mode`() = runBlocking {
        assertTrue(controller.maybeSend(point, Mode.VEHICLE, batterySaver = false))
        assertEquals(Activity.VEHICLE, sent.single().first.activity)
        assertEquals(81, sent.single().second)

        time.advance(20_000)
        assertFalse(controller.maybeSend(point, Mode.VEHICLE, batterySaver = false))
        time.advance(10_000)
        assertTrue(controller.maybeSend(point, Mode.VEHICLE, batterySaver = false))
        assertEquals(2, sent.size)
    }

    @Test
    fun `pending alerts of others are handed to the notifier, mine and closed ones are not`() = runBlocking {
        response = ApiResult.Success(
            HeartbeatResponse(
                listOf(alert("a"), alert("mine", mine = true), alert("closed", status = IncidentStatus.RESOLVED)),
                Instant.ofEpochMilli(FakeTime.T0),
            ),
        )
        controller.maybeSend(point, Mode.WALKING, batterySaver = false)
        assertEquals(listOf("a"), notified.single().map { it.id })
    }

    @Test
    fun `a failed heartbeat is not retried on every location callback`() = runBlocking {
        response = ApiResult.Failure(ApiError.Network(IOException("offline")))
        assertFalse(controller.maybeSend(point, Mode.WALKING, batterySaver = false))
        assertTrue(controller.lastOutcome, controller.lastOutcome.startsWith("failed"))
        time.advance(1_000)
        assertFalse(controller.maybeSend(point, Mode.WALKING, batterySaver = false))
        assertEquals(1, sent.size)
    }
}
