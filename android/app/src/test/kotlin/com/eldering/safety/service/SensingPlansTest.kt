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
    fun `vehicle uses 100 Hz and high-accuracy fixes every 10 s at most`() {
        val cfg = SensingPlans.sensing(Mode.VEHICLE, alerting = false, batterySaver = false)
        assertEquals(SensingConfig.Tier.VEHICLE, cfg.tier)
        assertEquals(100, cfg.accelHz)
        assertEquals(100, cfg.gyroHz)
        assertEquals(2_000_000, cfg.maxLatencyUs)
        val loc = SensingPlans.location(Mode.VEHICLE, false, false)
        assertEquals(LocationPlan.PRIORITY_HIGH_ACCURACY, loc.priority)
        assertEquals("SPEC upper bound", 10_000L, loc.intervalMs)
        assertEquals(5_000L, loc.minIntervalMs)
        assertEquals("location wake-ups pace drains at most half the 100 Hz FIFO depth (~22 s)", true, loc.intervalMs <= 11_000L)
    }

    @Test
    fun `cycling samples at the 50 Hz its rules are calibrated for, with balanced fixes every 20 s`() {
        val cfg = SensingPlans.sensing(Mode.CYCLING, alerting = false, batterySaver = false)
        assertEquals(SensingConfig.Tier.WALKING, cfg.tier)
        assertEquals(50, cfg.accelHz)
        val loc = SensingPlans.location(Mode.CYCLING, false, false)
        assertEquals(LocationPlan.PRIORITY_BALANCED_POWER_ACCURACY, loc.priority)
        assertEquals(20_000L, loc.intervalMs)
        assertEquals("battery saver does not stretch cycling", loc, SensingPlans.location(Mode.CYCLING, false, true))
    }

    @Test
    fun `a sent alert's live hour uses 5 s fixes, not the 1 s alert plan`() {
        for (mode in Mode.entries) {
            val live = SensingPlans.location(mode, alerting = false, batterySaver = false, live = true)
            assertEquals(LocationPlan.PRIORITY_HIGH_ACCURACY, live.priority)
            assertEquals(5_000L, live.intervalMs)
            assertEquals(2_500L, live.minIntervalMs)
            assertEquals("alerting wins", 1_000L, SensingPlans.location(mode, alerting = true, batterySaver = false, live = true).intervalMs)
        }
    }

    @Test
    fun `the STILL drain interval is half the FIFO depth, clamped`() {
        assertEquals("target phone: 3000 reserved events at 25 Hz", 60_000L, SensingPlans.stillDrainIntervalMs(3_000))
        assertEquals("no FIFO info (emulator)", SensingPlans.DEFAULT_STILL_DRAIN_MS, SensingPlans.stillDrainIntervalMs(0))
        assertEquals(SensingPlans.MIN_STILL_DRAIN_MS, SensingPlans.stillDrainIntervalMs(300))
        assertEquals(SensingPlans.MAX_STILL_DRAIN_MS, SensingPlans.stillDrainIntervalMs(100_000))
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
        // UNKNOWN (no Activity Recognition) follows the same rules: a phone on a nightstand is not a walker.
        for (mode in listOf(Mode.STILL, Mode.UNKNOWN)) {
            val last = HeartbeatPolicy.Last(0, 48.0, 11.0)
            assertFalse(mode.name, policy.shouldSend(60_000, mode, true, true, false, last, distanceFromLastM = 3.0))
            assertFalse(mode.name, policy.shouldSend(4 * 60_000, mode, true, true, false, last, distanceFromLastM = 3.0))
            assertTrue(mode.name, policy.shouldSend(5 * 60_000, mode, true, true, false, last, distanceFromLastM = 3.0))
            assertTrue(mode.name, policy.shouldSend(60_000, mode, true, true, false, last, distanceFromLastM = 80.0))
            assertEquals(10 * 60_000L, policy.intervalMs(mode, batterySaver = true))
        }
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
        assertEquals(61_000L, resolver.speedStaleAtMs())
        assertEquals(Mode.UNKNOWN, resolver.resolve(70_000).first)
        assertNull(resolver.speedStaleAtMs())
    }

    @Test
    fun `indoor fixes without speed do not keep a speed-inferred vehicle mode alive`() {
        val resolver = ModeResolver() // 3 min staleness
        for (i in 0 until 10) resolver.onSpeed(15f, i * 5_000L)
        resolver.onRecognized(Mode.STILL, atMs = 0) // AR still says STILL from before the drive
        assertEquals(Mode.VEHICLE to ModeResolver.Source.SPEED, resolver.resolve(45_000))
        // Garage: an hour of Wi-Fi/cell fixes every 5 s, none with a speed.
        var t = 50_000L
        while (t < 60 * 60_000L) {
            resolver.onSpeed(null, t)
            t += 5_000
        }
        assertEquals(Mode.STILL to ModeResolver.Source.ACTIVITY_RECOGNITION, resolver.resolve(t))
    }

    @Test
    fun `a fresh STILL or WALKING transition ends the speed rule a minute after the last fast fix`() {
        val resolver = ModeResolver()
        resolver.onSpeed(15f, 0)
        resolver.onSpeed(15f, 5_000)
        resolver.onRecognized(Mode.STILL, atMs = 90_000) // parked; no more fixes at all
        assertEquals(Mode.STILL, resolver.resolve(90_000).first)

        // A stale pre-drive AR state does not override a drive in progress (a red light).
        val driving = ModeResolver()
        driving.onRecognized(Mode.WALKING, atMs = 0)
        driving.onSpeed(15f, 10_000)
        driving.onSpeed(15f, 15_000)
        assertEquals(Mode.VEHICLE, driving.resolve(100_000).first)
    }

    @Test
    fun `without activity recognition the phone's own stillness gives STILL`() {
        val resolver = ModeResolver()
        resolver.onSensorStill(true)
        assertEquals(Mode.STILL to ModeResolver.Source.SENSORS, resolver.resolve(0))
        resolver.onRecognized(Mode.WALKING, 0)
        assertEquals("activity recognition wins", Mode.WALKING, resolver.resolve(0).first)
        val noAr = ModeResolver()
        noAr.onSensorStill(true)
        noAr.onSensorStill(false)
        assertEquals(Mode.UNKNOWN, noAr.resolve(0).first)
    }

    @Test
    fun `stillness needs minutes of a calm accelerometer and ends with one jolt`() {
        val monitor = StillnessMonitor()
        var t = 0L
        fun feed(seconds: Int, wobble: (Int) -> Float) {
            repeat(seconds * 25) { i ->
                monitor.onAccel(t, 0.1f, wobble(i), 9.80665f)
                t += 40_000_000L
            }
        }
        feed(140) { i -> if (i % 2 == 0) 0.05f else -0.05f } // a phone on a table: tiny noise
        assertFalse("not yet: 2.5 min needed", monitor.isStill)
        feed(30) { i -> if (i % 2 == 0) 0.05f else -0.05f }
        assertTrue(monitor.isStill)
        val changed = monitor.onAccel(t, 0f, 0f, 15f) // picked up
        assertTrue(changed)
        assertFalse(monitor.isStill)

        val walking = StillnessMonitor()
        var u = 0L
        repeat(200 * 25) { i ->
            walking.onAccel(u, 0f, 0f, 9.81f + (if (i % 12 < 6) 2.5f else -2.5f))
            u += 40_000_000L
        }
        assertFalse(walking.isStill)
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
    fun `walking switches to GNSS when balanced fixes stop coming, and keeps it while it helps`() {
        // E2E on the emulator: one ±100 m network fix, then nothing for 4+ min while the route ran.
        val m = WalkingFixMonitor()
        m.setActive(true, 0)
        m.onFix(1_000, 100f)
        assertFalse(m.wantsGnss(60_000))
        assertTrue("no fix for 75 s: probe with GNSS", m.wantsGnss(76_000))
        assertEquals(WalkingFixMonitor.State.PROBING, m.state)
        m.onFix(90_000, 6f)
        assertEquals(WalkingFixMonitor.State.GNSS, m.state)
        assertTrue(m.wantsGnss(90_000 + 4 * 60_000))
        assertFalse("then balanced is tried again", m.wantsGnss(90_000 + 5 * 60_000))

        val plan = SensingPlans.location(Mode.WALKING, alerting = false, batterySaver = false, gnss = true)
        assertEquals(LocationPlan.PRIORITY_HIGH_ACCURACY, plan.priority)
        assertEquals("same cadence as balanced", 30_000L, plan.intervalMs)
        assertEquals(
            "never for UNKNOWN (may be a phone lying indoors) or STILL",
            LocationPlan.PRIORITY_BALANCED_POWER_ACCURACY,
            SensingPlans.location(Mode.UNKNOWN, alerting = false, batterySaver = false, gnss = true).priority,
        )
        assertEquals(LocationPlan.PRIORITY_PASSIVE, SensingPlans.location(Mode.STILL, alerting = false, batterySaver = false, gnss = true).priority)
    }

    @Test
    fun `coarse walking fixes probe GNSS, and a probe that finds nothing better backs off`() {
        val m = WalkingFixMonitor()
        m.setActive(true, 0)
        m.onFix(10_000, 150f)
        assertFalse("one coarse fix is not enough", m.wantsGnss(10_000))
        m.onFix(40_000, 120f)
        assertTrue(m.wantsGnss(40_000))
        // Indoors: GNSS gives nothing good within 2 min.
        m.onFix(70_000, 90f)
        assertFalse(m.wantsGnss(40_000 + 120_000))
        assertEquals(WalkingFixMonitor.State.BACKOFF, m.state)
        assertFalse("backing off for 10 min", m.wantsGnss(40_000 + 120_000 + 9 * 60_000))
        assertFalse(m.wantsGnss(40_000 + 120_000 + 10 * 60_000))
        assertEquals(WalkingFixMonitor.State.BALANCED, m.state)
        // Good balanced fixes never escalate; leaving the walking tier resets everything.
        m.onFix(900_000, 20f)
        m.onFix(930_000, 25f)
        assertFalse(m.wantsGnss(960_000))
        m.setActive(false, 970_000)
        assertFalse(m.wantsGnss(2_000_000))
    }

    @Test
    fun `family alerts are polled every 2 min while still, between the 5-min heartbeats`() {
        val policy = HeartbeatPolicy()
        assertEquals(120_000L, policy.alertPollIntervalMs(Mode.STILL, batterySaver = false))
        assertEquals(240_000L, policy.alertPollIntervalMs(Mode.UNKNOWN, batterySaver = true))
        assertEquals("moving: the heartbeat itself is often enough", 60_000L, policy.alertPollIntervalMs(Mode.WALKING, batterySaver = false))
        assertEquals(30_000L, policy.alertPollIntervalMs(Mode.VEHICLE, batterySaver = false))
    }

    @Test
    fun `possible-accident tier is limited by a time budget, not by the previous candidate`() {
        val governor = PossibleAccidentGovernor(holdMs = 30_000, budgetWindowMs = 600_000, budgetMs = 150_000)
        assertEquals(PossibleAccidentGovernor.Decision.ENTER, governor.onCandidate(0))
        assertTrue(governor.isHolding(10_000))
        assertEquals(PossibleAccidentGovernor.Decision.ALREADY_HOLDING, governor.onCandidate(10_000))
        assertFalse(governor.isHolding(31_000))
        // A minute later another isolated candidate still gets the full rate (the old rule refused it for 2 min).
        assertEquals(PossibleAccidentGovernor.Decision.ENTER, governor.onCandidate(60_000))
        governor.endHold(90_000)
        // Three more full holds use up the 150 s budget of the last 10 minutes...
        assertEquals(PossibleAccidentGovernor.Decision.ENTER, governor.onCandidate(120_000))
        governor.endHold(150_000)
        assertEquals(PossibleAccidentGovernor.Decision.ENTER, governor.onCandidate(180_000))
        governor.endHold(210_000)
        assertEquals(120_000L, governor.usedMs(220_000))
        // ...an entry needs 10 s of budget left: 120 s used + 10 s ≤ 150 s, so one more, then none.
        assertEquals(PossibleAccidentGovernor.Decision.ENTER, governor.onCandidate(240_000))
        governor.endHold(270_000)
        assertEquals(PossibleAccidentGovernor.Decision.RATE_LIMITED, governor.onCandidate(300_000))
        // Once the first holds slide out of the window there is budget again.
        assertEquals(PossibleAccidentGovernor.Decision.ENTER, governor.onCandidate(640_000))
    }

    @Test
    fun `false triggers rejected early cost only their own seconds - the real impact after them escalates`() {
        // Emulator E2E: phone_drop and sit_down candidates, then a pedestrian impact about a minute later.
        val governor = PossibleAccidentGovernor()
        assertEquals(PossibleAccidentGovernor.Decision.ENTER, governor.onCandidate(nowMs = 1_000, candidateAtMs = 800))
        governor.endHold(13_000) // phone drop rejected
        assertEquals(PossibleAccidentGovernor.Decision.ENTER, governor.onCandidate(nowMs = 41_000, candidateAtMs = 40_800))
        governor.endHold(53_000) // sitting down rejected
        assertEquals(PossibleAccidentGovernor.Decision.ENTER, governor.onCandidate(nowMs = 81_000, candidateAtMs = 80_800))
        assertEquals(24_000L, governor.usedMs(81_000))
    }

    @Test
    fun `a candidate that reaches us late is not escalated`() {
        val governor = PossibleAccidentGovernor()
        // Screen off: the batch with the candidate arrived 20 s after its peak; its verdict is due now.
        assertEquals(PossibleAccidentGovernor.Decision.TOO_OLD, governor.onCandidate(nowMs = 100_000, candidateAtMs = 80_000))
        assertEquals(PossibleAccidentGovernor.Decision.ENTER, governor.onCandidate(nowMs = 101_000, candidateAtMs = 99_500))
        // In a vehicle 1 s fixes still help the speed after a crash for longer.
        val vehicle = PossibleAccidentGovernor()
        assertEquals(
            PossibleAccidentGovernor.Decision.ENTER,
            vehicle.onCandidate(nowMs = 100_000, candidateAtMs = 92_000, maxAgeMs = PossibleAccidentGovernor.VEHICLE_MAX_AGE_MS),
        )
    }

    @Test
    fun `running strides and stairs do not escalate, an isolated impact does`() {
        val governor = PossibleAccidentGovernor()
        // A run: a candidate every second (sensor time), delivered promptly.
        val decisions = (0 until 60).map { i -> governor.onCandidate(nowMs = 1_000L * i + 200, candidateAtMs = 1_000L * i) }
        assertEquals("only the first stride gets through, once", 1, decisions.count { it == PossibleAccidentGovernor.Decision.ENTER })
        assertEquals(PossibleAccidentGovernor.Decision.GAIT, decisions.last())
        governor.endHold(10_000)
        assertFalse(governor.isHolding(10_001))
        // Long after the run ended, a lone impact.
        assertEquals(PossibleAccidentGovernor.Decision.ENTER, governor.onCandidate(nowMs = 600_200, candidateAtMs = 600_000))

        // Stairs: three step candidates within 4 s before the next one keep it batched.
        val stairs = PossibleAccidentGovernor()
        listOf(0L, 1_200L, 2_400L).forEach { stairs.onCandidate(nowMs = it + 100, candidateAtMs = it) }
        stairs.endHold(3_000)
        assertEquals(PossibleAccidentGovernor.Decision.GAIT, stairs.onCandidate(nowMs = 3_700, candidateAtMs = 3_600))
    }
}
