package com.eldering.detection

import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Scoring and suppression rules (research §2.3, §3) on hand-set feature vectors. */
class EventRulesTest {
    private val cfg = DetectorConfig()
    private val rules = EventRules(cfg)
    private val walking = RuleContext(Mode.WALKING, vehicleContext = false, captureFactor = 1.0)
    private val vehicle = RuleContext(Mode.VEHICLE, vehicleContext = true, captureFactor = 1.0)

    /** A clear fall: 4 g impact after 180 ms of free fall, 88° fast rotation, lying still. */
    private val fall = WindowFeatures(
        rateHz = 100.0, accelRangeG = 8.0, gapCount = 0, peakG = 4.0, saturatedMs = 0.0, aboveTwoGMs = 40.0,
        impacts1s = 2, impacts15s = 2, freeFallMs = 180.0, minSmvPreG = 0.4, trueFreeFallMs = 0.0, airborneAfterMs = 0.0,
        bounceAirborneMs = 0.0, vertVelMps = -1.0, jerkPeakGps = 130.0, rotationDeg = 71.0, fastRotationDeg = 88.0,
        preRotationDeg = 88.0, strikeRotationDeg = 1.0, baselineRotationDeg = 35.0, orientationChangeDeg = 82.0,
        handHeldBefore = false, horizRatio = 0.6, dvLowerBoundMps = 0.7, stillFraction = 1.0, restNoiseG = 0.004,
        recovered = false, gaitPre = 0.7, gaitPost = 0.0, speedBeforeMps = 1.4, speedAfterMps = 0.0, pressureDeltaHpa = null,
    )

    /** A pedestrian strike: 12 g mostly horizontal hit, then 175° of rotation, three impacts, lying still. */
    private val strike = fall.copy(
        peakG = 12.0, saturatedMs = 20.0, aboveTwoGMs = 60.0, impacts15s = 3, freeFallMs = 40.0, preRotationDeg = 12.0,
        strikeRotationDeg = 175.0, horizRatio = 0.96, dvLowerBoundMps = 3.5, orientationChangeDeg = 90.0,
    )

    /** A 50 km/h frontal crash with the car stopped afterwards. */
    private val crash = fall.copy(
        peakG = 16.3, saturatedMs = 40.0, aboveTwoGMs = 120.0, freeFallMs = 0.0, rotationDeg = 25.0, fastRotationDeg = 25.0,
        preRotationDeg = 1.0, strikeRotationDeg = 24.0, orientationChangeDeg = 8.0, dvLowerBoundMps = 13.0,
        speedBeforeMps = 13.8, speedAfterMps = 0.0, gaitPre = 0.0,
    )

    private fun sum(d: Decision) = d.parts.sumOf { it.second }

    @Nested
    inner class Fall {
        @Test
        fun `clear fall emits with full confidence and parts that add up`() {
            val d = rules.classify(fall, walking)
            assertEquals(EventKind.FALL, d.kind)
            assertEquals(1f, d.confidence)
            assertEquals(d.points, sum(d))
            assertTrue(d.reason.startsWith("Fall: 4.0 g impact"), d.reason)
        }

        @Test
        fun `recovery, continued gait, phone drop and toss suppress it`() {
            assertEquals(Rejection.RECOVERED, rules.classify(fall.copy(recovered = true), walking).rejection)
            assertEquals(Rejection.GAIT_CONTINUES, rules.classify(fall.copy(gaitPost = 0.6), walking).rejection)
            val drop = fall.copy(trueFreeFallMs = 400.0, aboveTwoGMs = 20.0)
            assertEquals(Rejection.PHONE_DROP, rules.classify(drop, walking).rejection)
            val toss = fall.copy(airborneAfterMs = 300.0, peakG = 3.0)
            assertEquals(Rejection.TOSS, rules.classify(toss, walking).rejection)
        }

        @Test
        fun `a pocket phone in a true free fall with a long body impact is not a phone drop`() {
            // Ladder fall: 500 ms true free fall, but a 60 ms body impact, no bounce, phone upright (pocket) before.
            val ladder = fall.copy(trueFreeFallMs = 500.0, aboveTwoGMs = 60.0)
            assertEquals(EventKind.FALL, rules.classify(ladder, walking).kind)
        }

        @Test
        fun `sit-down like candidates fail the violence vote`() {
            val sit = fall.copy(peakG = 2.0, freeFallMs = 0.0, fastRotationDeg = 62.0, jerkPeakGps = 23.0, preRotationDeg = 60.0)
            assertEquals(Rejection.VIOLENCE_BELOW_2, rules.classify(sit, walking).rejection)
        }

        @Test
        fun `posture must change or the body must lie still`() {
            val upright = fall.copy(orientationChangeDeg = 10.0, stillFraction = 0.2)
            assertEquals(Rejection.NO_POSTURE_CHANGE, rules.classify(upright, walking).rejection)
            assertEquals(EventKind.FALL, rules.classify(upright.copy(stillFraction = 1.0), walking).kind, "bike crash: still, little tilt")
        }

        @Test
        fun `peak below the mode's fall minimum is ignored`() {
            assertEquals(Rejection.BELOW_FALL_MIN, rules.classify(fall.copy(peakG = 1.5), walking).rejection)
            val running = RuleContext(Mode.RUNNING, vehicleContext = false, captureFactor = 1.0)
            assertEquals(Rejection.BELOW_FALL_MIN, rules.classify(fall.copy(peakG = 3.2), running).rejection)
        }

        @Test
        fun `in a moving vehicle a fall needs 3 g and a posture change`() {
            val bus = RuleContext(Mode.VEHICLE, vehicleContext = true, captureFactor = 1.0)
            val onBus = fall.copy(speedBeforeMps = 8.0, speedAfterMps = 8.0, aboveTwoGMs = 30.0, dvLowerBoundMps = 0.5)
            assertEquals(EventKind.FALL, rules.classify(onBus, bus).kind)
            assertEquals(Rejection.BELOW_FALL_MIN, rules.classify(onBus.copy(peakG = 2.8), bus).rejection)
            assertEquals(Rejection.NO_POSTURE_CHANGE, rules.classify(onBus.copy(orientationChangeDeg = 20.0), bus).rejection)
        }

        @Test
        fun `without a gyroscope the orientation change is not a violence vote`() {
            val noGyro = fall.copy(rotationDeg = null, fastRotationDeg = null, preRotationDeg = null, strikeRotationDeg = null, baselineRotationDeg = null)
            val sitNoGyro = noGyro.copy(peakG = 2.5, freeFallMs = 0.0, jerkPeakGps = 23.0)
            assertEquals(Rejection.VIOLENCE_BELOW_2, rules.classify(sitNoGyro, walking).rejection)
            val research = EventRules(DetectorConfig(noGyroOrientationVote = true))
            assertEquals(EventKind.FALL, research.classify(sitNoGyro, walking).kind, "research behaviour double counts posture")
            assertEquals(EventKind.FALL, rules.classify(noGyro, walking).kind)
        }

        @Test
        fun `pressure is a bonus of at most 0_1 and never required`() {
            val modest = fall.copy(peakG = 2.6, freeFallMs = 0.0, rotationDeg = 10.0, orientationChangeDeg = 40.0)
            val without = rules.classify(modest, walking)
            val with = rules.classify(modest.copy(pressureDeltaHpa = 0.078), walking)
            assertEquals(EventKind.FALL, without.kind)
            assertEquals(EventKind.FALL, with.kind)
            assertEquals(10, with.points - without.points)
            assertEquals(without.points, rules.classify(modest.copy(pressureDeltaHpa = -0.078), walking).points, "phone higher: no bonus")
        }
    }

    @Nested
    inner class Strike {
        @Test
        fun `horizontal hit followed by rotation is a pedestrian impact`() {
            val d = rules.classify(strike, walking)
            assertEquals(EventKind.PEDESTRIAN_IMPACT, d.kind)
            assertEquals(1f, d.confidence)
            assertTrue(d.reason.startsWith("Pedestrian impact"), d.reason)
            assertEquals(d.points, sum(d))
        }

        @Test
        fun `rotation before the impact makes it a fall`() {
            assertEquals(EventKind.FALL, rules.classify(strike.copy(preRotationDeg = 70.0, baselineRotationDeg = 35.0), walking).kind)
        }

        @Test
        fun `gait swing before the hit is tolerated only with a large rotation after it`() {
            val swing = strike.copy(preRotationDeg = 38.0, baselineRotationDeg = 40.0)
            assertEquals(EventKind.PEDESTRIAN_IMPACT, rules.classify(swing, walking).kind)
            assertEquals(EventKind.FALL, rules.classify(swing.copy(strikeRotationDeg = 30.0), walking).kind)
            assertEquals(EventKind.FALL, rules.classify(swing.copy(baselineRotationDeg = 5.0), walking).kind)
        }

        @Test
        fun `without a gyroscope a pre-impact free fall rules out a strike`() {
            val noGyro = strike.copy(rotationDeg = null, fastRotationDeg = null, preRotationDeg = null, strikeRotationDeg = null, baselineRotationDeg = null)
            assertEquals(EventKind.PEDESTRIAN_IMPACT, rules.classify(noGyro, walking).kind)
            assertEquals(EventKind.FALL, rules.classify(noGyro.copy(freeFallMs = 500.0), walking).kind, "ladder fall")
        }

        @Test
        fun `recovery costs 0_25 and is off while moving in a vehicle`() {
            val bumped = strike.copy(stillFraction = 0.0, orientationChangeDeg = 10.0, horizRatio = 0.5, dvLowerBoundMps = 1.0)
            assertEquals(70, rules.classify(bumped, walking).strikePoints)
            val walkedAway = rules.classify(bumped.copy(recovered = true), walking)
            assertNull(walkedAway.kind)
            assertEquals(45, walkedAway.strikePoints)
            val inCar = vehicle.copy()
            val d = rules.classify(strike.copy(speedBeforeMps = 10.0, speedAfterMps = 10.0), inCar)
            assertNull(d.strikePoints, "no strike scoring while moving in a vehicle")
        }

        @Test
        fun `a weak hit is not a strike`() {
            assertEquals(EventKind.FALL, rules.classify(strike.copy(peakG = 4.0, saturatedMs = 0.0), walking).kind)
        }
    }

    @Nested
    inner class Crash {
        @Test
        fun `crash with the car stopped afterwards emits`() {
            val d = rules.classify(crash, vehicle)
            assertEquals(EventKind.VEHICLE_CRASH, d.kind)
            assertEquals(1f, d.confidence)
            assertTrue(d.reason.contains("speed 13.8 → 0.0 m/s"), d.reason)
            assertEquals(d.points, sum(d))
        }

        @Test
        fun `car that keeps driving is suppressed`() {
            assertEquals(Rejection.CONTINUED_DRIVING, rules.classify(crash.copy(speedAfterMps = 9.0), vehicle).rejection)
            assertEquals(Rejection.CONTINUED_DRIVING, rules.classify(crash.copy(speedBeforeMps = null, speedAfterMps = 6.0), vehicle).rejection)
        }

        @Test
        fun `no speed after costs 0_15 and caps confidence at 0_7`() {
            val tunnel = rules.classify(crash.copy(speedAfterMps = null), vehicle)
            assertEquals(EventKind.VEHICLE_CRASH, tunnel.kind)
            assertEquals(0.6f, tunnel.confidence)
            val strong = rules.classify(crash.copy(speedAfterMps = null, rotationDeg = 50.0, pressureDeltaHpa = 0.8), vehicle)
            assertEquals(0.7f, strong.confidence)
            assertEquals(strong.points, sum(strong))
        }

        @Test
        fun `needs vehicle context, speed and energy`() {
            assertNull(rules.classify(crash, walking.copy(mode = Mode.STILL)).crashPoints, "no vehicle context")
            assertNull(rules.classify(crash.copy(speedBeforeMps = 3.0), vehicle).crashPoints, "slower than 15 km/h")
            val slide = crash.copy(peakG = 7.0, saturatedMs = 0.0, aboveTwoGMs = 20.0, dvLowerBoundMps = 0.5)
            assertNull(rules.classify(slide, vehicle).crashPoints, "a phone hitting the floor has no crash energy")
        }

        @Test
        fun `cabin pressure spike is a bonus only`() {
            val lowConf = crash.copy(speedAfterMps = 2.0, dvLowerBoundMps = 2.5, saturatedMs = 10.0, peakG = 6.0, impacts1s = 1)
            val without = rules.classify(lowConf, vehicle)
            val with = rules.classify(lowConf.copy(pressureDeltaHpa = 0.8), vehicle)
            assertEquals(50, without.crashPoints)
            assertEquals(60, with.crashPoints)
            assertEquals(EventKind.VEHICLE_CRASH, with.kind)
        }
    }

    @Nested
    inner class Global {
        @Test
        fun `short impulse without posture change is ignored for every kind`() {
            val slam = crash.copy(aboveTwoGMs = 10.0, orientationChangeDeg = 2.0, rotationDeg = 3.0, minSmvPreG = 0.95)
            assertEquals(Rejection.IMPULSE_ONLY, rules.classify(slam, vehicle).rejection)
            assertEquals(Rejection.IMPULSE_ONLY, rules.classify(slam, walking).rejection)
        }

        @Test
        fun `thresholds scale with the sampling rate and stay below the range`() {
            val light = fall.copy(peakG = 1.45, saturatedMs = 0.0)
            assertEquals(Rejection.BELOW_FALL_MIN, rules.classify(light, walking).rejection)
            val at25Hz = walking.copy(captureFactor = 0.707)
            assertTrue(rules.classify(light, at25Hz).rejection != Rejection.BELOW_FALL_MIN, "1.8 g × 0.707 = 1.27 g at 25 Hz")
            val twoG = strike.copy(accelRangeG = 2.0, peakG = 1.85, saturatedMs = 0.0)
            assertEquals(EventKind.PEDESTRIAN_IMPACT, rules.classify(twoG, walking).kind, "5 g gate capped at 0.9 × 2 g")
        }
    }
}
