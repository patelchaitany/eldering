package com.eldering.safety.service

import com.eldering.detection.Mode
import com.eldering.safety.network.SettingsDto
import com.eldering.safety.network.SharingState
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** SPEC "Battery" tiers: sensor rates, batching, location requests, heartbeat pacing and mode selection. */
class SensingPlansTest {

    @Test
    fun `still samples the accelerometer at 25 Hz batched about 10 s, no gyro, passive location`() {
        val cfg = SensingPlans.sensing(Mode.STILL, alerting = false, batterySaver = false, hasGyro = true, hasPressure = true)
        assertEquals(SensingConfig.Tier.STILL, cfg.tier)
        assertEquals(25, cfg.accelHz)
        assertEquals(40_000, cfg.accelPeriodUs)
        assertEquals(0, cfg.gyroPeriodUs)
        assertEquals(10_000_000, cfg.maxLatencyUs)
        assertEquals(5, cfg.pressureHz)
        assertEquals(LocationPlan.PRIORITY_PASSIVE, SensingPlans.location(Mode.STILL, false, false).priority)
    }

    @Test
    fun `walking, running and unknown use accel and gyro at 50 Hz with balanced location every 30 s`() {
        for (mode in listOf(Mode.WALKING, Mode.RUNNING, Mode.UNKNOWN)) {
            val cfg = SensingPlans.sensing(mode, alerting = false, batterySaver = false)
            assertEquals(mode.name, SensingConfig.Tier.WALKING, cfg.tier)
            assertEquals(50, cfg.accelHz)
            assertEquals(50, cfg.gyroHz)
            assertTrue("batched", cfg.maxLatencyUs > 0)
            val loc = SensingPlans.location(mode, alerting = false, batterySaver = false)
            assertEquals(LocationPlan.PRIORITY_BALANCED_POWER_ACCURACY, loc.priority)
            assertEquals(30_000L, loc.intervalMs)
            // No request-side distance filter: callbacks pace sensor drains (the trail applies 20 m).
            assertEquals(0f, loc.minDistanceM)
        }
    }

    @Test
    fun `vehicle and cycling use 100 Hz and high-accuracy fixes every 5 s`() {
        for (mode in listOf(Mode.VEHICLE, Mode.CYCLING)) {
            val cfg = SensingPlans.sensing(mode, alerting = false, batterySaver = false)
            assertEquals(SensingConfig.Tier.VEHICLE, cfg.tier)
            assertEquals(100, cfg.accelHz)
            assertEquals(100, cfg.gyroHz)
            assertEquals(2_000_000, cfg.maxLatencyUs)
            val loc = SensingPlans.location(mode, false, false)
            assertEquals(LocationPlan.PRIORITY_HIGH_ACCURACY, loc.priority)
            assertEquals(5_000L, loc.intervalMs)
        }
    }

    @Test
    fun `a possible accident switches every mode to full rate without batching and 1 s GPS`() {
        for (mode in Mode.entries) {
            val cfg = SensingPlans.sensing(mode, alerting = true, batterySaver = true)
            assertEquals(SensingConfig.Tier.ALERT, cfg.tier)
            assertEquals(200, cfg.accelHz)
            assertEquals(0, cfg.maxLatencyUs)
            val loc = SensingPlans.location(mode, alerting = true, batterySaver = true)
            assertEquals(LocationPlan.PRIORITY_HIGH_ACCURACY, loc.priority)
            assertEquals(1_000L, loc.intervalMs)
        }
    }

    @Test
    fun `battery saver stretches still and walking but never vehicle or alert`() {
        val walking = SensingPlans.sensing(Mode.WALKING, alerting = false, batterySaver = false, hasPressure = true)
        val walkingSaver = SensingPlans.sensing(Mode.WALKING, alerting = false, batterySaver = true, hasPressure = true)
        assertEquals(walking.maxLatencyUs * 2, walkingSaver.maxLatencyUs)
        assertEquals(walking.accelPeriodUs, walkingSaver.accelPeriodUs) // detection quality unchanged
        assertEquals(60_000L, SensingPlans.location(Mode.WALKING, false, true).intervalMs)
        assertEquals(0, SensingPlans.sensing(Mode.STILL, false, batterySaver = true, hasPressure = true).pressurePeriodUs)

        assertEquals(
            SensingPlans.sensing(Mode.VEHICLE, false, false),
            SensingPlans.sensing(Mode.VEHICLE, false, true),
        )
        assertEquals(SensingPlans.location(Mode.VEHICLE, false, false), SensingPlans.location(Mode.VEHICLE, false, true))
    }

    @Test
    fun `missing gyroscope or barometer are simply left out`() {
        val cfg = SensingPlans.sensing(Mode.VEHICLE, alerting = false, batterySaver = false, hasGyro = false, hasPressure = false)
        assertEquals(0, cfg.gyroPeriodUs)
        assertEquals(0, cfg.pressurePeriodUs)
        assertEquals(100, cfg.accelHz)
    }

    @Test
    fun `periods are microseconds, never the SENSOR_DELAY constants`() {
        for (mode in Mode.entries) for (alerting in listOf(false, true)) for (saver in listOf(false, true)) {
            val cfg = SensingPlans.sensing(mode, alerting, saver, hasGyro = true, hasPressure = true)
            assertTrue(cfg.accelPeriodUs >= SensingConfig.MIN_PERIOD_US)
            assertTrue(cfg.gyroPeriodUs == 0 || cfg.gyroPeriodUs >= SensingConfig.MIN_PERIOD_US)
        }
    }

    // ------------------------------------------------------------------ heartbeat

    private val policy = HeartbeatPolicy()

    @Test
    fun `heartbeat only while signed in and sharing`() {
        assertFalse(policy.shouldSend(0, Mode.WALKING, signedIn = false, sharingOn = true, batterySaver = false, last = null, distanceFromLastM = null))
        assertFalse(policy.shouldSend(0, Mode.WALKING, signedIn = true, sharingOn = false, batterySaver = false, last = null, distanceFromLastM = null))
        assertTrue(policy.shouldSend(0, Mode.WALKING, signedIn = true, sharingOn = true, batterySaver = false, last = null, distanceFromLastM = null))
    }

    @Test
    fun `heartbeat every 60 s walking and 30 s in a vehicle`() {
        val last = HeartbeatPolicy.Last(1_000_000, 48.0, 11.0)
        fun due(mode: Mode, after: Long) = policy.shouldSend(1_000_000 + after, mode, true, true, false, last, 100.0)
        assertFalse(due(Mode.WALKING, 59_000))
        assertTrue(due(Mode.WALKING, 60_000))
        assertFalse(due(Mode.VEHICLE, 29_000))
        assertTrue(due(Mode.VEHICLE, 30_000))
    }

    @Test
    fun `still and unchanged sends only the keep-alive, a real move goes out sooner`() {
        val last = HeartbeatPolicy.Last(0, 48.0, 11.0)
        assertFalse(policy.shouldSend(4 * 60_000, Mode.STILL, true, true, false, last, distanceFromLastM = 3.0))
        assertTrue(policy.shouldSend(5 * 60_000, Mode.STILL, true, true, false, last, distanceFromLastM = 3.0))
        assertTrue(policy.shouldSend(60_000, Mode.STILL, true, true, false, last, distanceFromLastM = 80.0))
        assertEquals(10 * 60_000L, policy.intervalMs(Mode.STILL, batterySaver = true))
    }

    @Test
    fun `sharing is on when on, or when a timed pause has ended`() {
        val now = Instant.parse("2026-10-13T08:00:00Z").toEpochMilli()
        assertNull(HeartbeatController.isSharingOn(null, now))
        assertEquals(true, HeartbeatController.isSharingOn(SettingsDto(SharingState.ON), now))
        assertEquals(false, HeartbeatController.isSharingOn(SettingsDto(SharingState.OFF), now))
        assertEquals(false, HeartbeatController.isSharingOn(SettingsDto(SharingState.PAUSED), now))
        val endedPause = SettingsDto(SharingState.PAUSED, pausedUntil = Instant.parse("2026-10-13T07:59:00Z"), effectiveSharingState = SharingState.PAUSED)
        assertEquals(true, HeartbeatController.isSharingOn(endedPause, now))
        val runningPause = endedPause.copy(pausedUntil = Instant.parse("2026-10-13T09:00:00Z"))
        assertEquals(false, HeartbeatController.isSharingOn(runningPause, now))
    }

    // ------------------------------------------------------------------ modes

    @Test
    fun `mode priority is debug override, then GPS speed, then activity recognition`() {
        val resolver = ModeResolver()
        assertEquals(Mode.UNKNOWN to ModeResolver.Source.NONE, resolver.resolve(0))
        resolver.onRecognized(Mode.WALKING)
        assertEquals(Mode.WALKING, resolver.resolve(0).first)

        resolver.onSpeed(15f, 1_000)
        assertEquals("one fast fix is not enough", Mode.WALKING, resolver.resolve(1_000).first)
        resolver.onSpeed(16f, 2_000)
        assertEquals(Mode.VEHICLE to ModeResolver.Source.SPEED, resolver.resolve(2_000))

        resolver.forced = Mode.STILL
        assertEquals(Mode.STILL to ModeResolver.Source.DEBUG, resolver.resolve(2_000))
        resolver.forced = null

        repeat(3) { resolver.onSpeed(0.5f, 3_000L + it) }
        assertEquals(Mode.WALKING, resolver.resolve(4_000).first)
    }

    @Test
    fun `speed-based vehicle mode expires without fixes`() {
        val resolver = ModeResolver(speedStaleMs = 60_000)
        resolver.onSpeed(20f, 0)
        resolver.onSpeed(20f, 1_000)
        assertEquals(Mode.VEHICLE, resolver.resolve(2_000).first)
        assertEquals(Mode.UNKNOWN, resolver.resolve(70_000).first)
    }

    @Test
    fun `mode names parse leniently`() {
        assertEquals(Mode.VEHICLE, parseMode("vehicle"))
        assertEquals(Mode.VEHICLE, parseMode("IN_VEHICLE"))
        assertEquals(Mode.VEHICLE, parseMode("driving"))
        assertEquals(Mode.WALKING, parseMode(" Walking "))
        assertNull(parseMode("flying"))
        assertNull(parseMode(null))
    }

    @Test
    fun `possible-accident tier is rate limited`() {
        val governor = PossibleAccidentGovernor(holdMs = 30_000, reentryMs = 120_000)
        assertTrue(governor.onCandidate(0))
        assertTrue(governor.isHolding(10_000))
        assertFalse("already holding", governor.onCandidate(10_000))
        assertFalse(governor.isHolding(31_000))
        assertFalse("every stride while running must not keep full rate on", governor.onCandidate(60_000))
        assertTrue(governor.onCandidate(121_000))
    }
}
