package com.eldering.detection

import org.junit.jupiter.api.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Window feature extraction on hand-built windows with known answers: a phone held upright falls freely for
 * 300 ms while rotating 90° about x, hits the floor (6 g, 40 ms) and lies flat and still.
 */
class FeatureExtractorTest {
    private val cfg = DetectorConfig()
    private val hz = 100.0

    private class Built(val window: CandidateWindow)

    private fun build(
        withGyro: Boolean = true,
        gapS: ClosedFloatingPointRange<Double>? = null,
        speeds: List<Pair<Double, Double>> = emptyList(),
        pressure: List<Pair<Double, Double>> = emptyList(),
        getsUp: Boolean = false,
        rangeG: Double = 8.0,
    ): Built {
        val t = (0 until 1300).map { -2.5 + it / hz }.filter { gapS == null || it !in gapS }.toDoubleArray()
        val ax = DoubleArray(t.size)
        val ay = DoubleArray(t.size)
        val az = DoubleArray(t.size)
        for ((i, ti) in t.withIndex()) {
            when {
                ti < -0.3 -> ay[i] = 1.0 // upright
                ti < 0.0 -> Unit // free fall: 0 g
                ti < 0.04 -> az[i] = 1.0 + 5.0 * sin(PI * (ti + 0.005) / 0.05) // floor impact on the (now vertical) z axis
                getsUp && ti >= 4.0 -> { // picked up again and carried upright with a gait
                    ay[i] = 1.0 + 0.3 * sin(2 * PI * 1.8 * ti)
                }
                else -> az[i] = 1.0 // lying flat
            }
            ax[i] = (ax[i]).coerceIn(-rangeG, rangeG)
            ay[i] = ay[i].coerceIn(-rangeG, rangeG)
            az[i] = az[i].coerceIn(-rangeG, rangeG)
        }
        val gt = if (withGyro) DoubleArray(301) { -2.0 + it / 100.0 } else DoubleArray(0)
        // 90° about x during the free fall (raised-cosine rate)
        val gx = DoubleArray(gt.size) { i ->
            val s = (gt[i] + 0.3) / 0.3
            if (s in 0.0..1.0) (PI / 2) / 0.3 * (1 - cos(2 * PI * s)) else 0.0
        }
        val zeros = DoubleArray(gt.size)
        return Built(
            CandidateWindow(
                accelT = t, ax = ax, ay = ay, az = az,
                gyroT = gt, gx = gx, gy = zeros, gz = zeros,
                pressureT = pressure.map { it.first }.toDoubleArray(), pressure = pressure.map { it.second }.toDoubleArray(),
                speedT = speeds.map { it.first }.toDoubleArray(), speed = speeds.map { it.second }.toDoubleArray(),
                postWindowS = 10.0, rangeG = rangeG,
            ),
        )
    }

    @Test
    fun `impact, free fall, rotation, posture and stillness are measured`() {
        val f = FeatureExtractor.extract(build().window, cfg)
        assertEquals(100.0, f.rateHz, 0.5)
        assertEquals(6.0, f.peakG, 0.15)
        assertEquals(0.0, f.saturatedMs)
        assertTrue(f.aboveTwoGMs in 20.0..40.0, "above 2 g ${f.aboveTwoGMs}")
        assertEquals(1, f.impacts1s)
        assertEquals(300.0, f.freeFallMs, 10.0)
        assertEquals(300.0, f.trueFreeFallMs, 10.0)
        assertEquals(0.0, f.minSmvPreG, 1e-9)
        assertEquals(0.0, f.airborneAfterMs)
        assertEquals(90.0, f.orientationChangeDeg!!, 0.5)
        assertEquals(90.0, f.rotationDeg!!, 2.0)
        assertEquals(90.0, f.fastRotationDeg!!, 2.0)
        assertEquals(90.0, f.preRotationDeg!!, 2.0)
        assertEquals(0.0, f.strikeRotationDeg!!, 1.0)
        assertEquals(0.0, f.baselineRotationDeg!!, 1.0)
        assertTrue(f.jerkPeakGps > 100.0, "jerk ${f.jerkPeakGps}")
        assertEquals(1.0, f.stillFraction)
        assertEquals(0.0, f.restNoiseG!!, 1e-6)
        assertFalse(f.recovered)
        assertFalse(f.handHeldBefore, "upright like in a trouser pocket")
        assertTrue(f.vertVelMps < -2.0, "about −g·0.3 s while falling: ${f.vertVelMps}")
        assertTrue(f.dvLowerBoundMps > 0.5)
        assertEquals(0, f.gapCount)
        assertNull(f.speedBeforeMps)
        assertNull(f.speedAfterMps)
        assertNull(f.pressureDeltaHpa)
    }

    @Test
    fun `missing gyro leaves rotation features absent and the map omits them`() {
        val f = FeatureExtractor.extract(build(withGyro = false).window, cfg)
        assertNull(f.rotationDeg)
        assertNull(f.fastRotationDeg)
        assertNull(f.preRotationDeg)
        val map = f.toMap()
        assertFalse(FeatureKeys.ROTATION_DEG in map)
        assertFalse(FeatureKeys.PRESSURE_DELTA_HPA in map)
        assertFalse(FeatureKeys.SPEED_BEFORE in map)
        for (key in listOf(FeatureKeys.PEAK_G, FeatureKeys.IMPACTS_1S, FeatureKeys.FREEFALL_MS, FeatureKeys.ORIENTATION_CHANGE_DEG, FeatureKeys.STILLNESS_VAR)) {
            assertTrue(key in map, "$key present")
        }
    }

    @Test
    fun `speeds and pressure use their research windows`() {
        val speeds = listOf(-20.0 to 99.0, -10.0 to 12.0, -5.0 to 14.0, -1.0 to 13.0, -0.2 to 50.0, 2.0 to 40.0, 4.0 to 0.5, 9.0 to 1.0)
        val pressure = (0 until 130).map { -6.0 + it * 0.1 }.map { it to if (it < 0.5) 1000.0 else 1000.078 }
        val f = FeatureExtractor.extract(build(speeds = speeds, pressure = pressure).window, cfg)
        assertEquals(13.0, f.speedBeforeMps!!, 1e-9, "median of fixes in [−15 s, −0.5 s]")
        assertEquals(1.0, f.speedAfterMps!!, 1e-9, "max of fixes in [3 s, W]")
        assertEquals(0.078, f.pressureDeltaHpa!!, 1e-6)
    }

    @Test
    fun `a data gap is counted and never extends the free-fall run`() {
        val f = FeatureExtractor.extract(build(gapS = -0.25..-0.1).window, cfg)
        assertEquals(1, f.gapCount)
        assertTrue(f.freeFallMs < 200.0, "run split by the gap: ${f.freeFallMs}")
    }

    @Test
    fun `getting up and walking away counts as recovered`() {
        val f = FeatureExtractor.extract(build(getsUp = true).window, cfg)
        assertTrue(f.recovered)
    }

    @Test
    fun `clipping is measured as saturated time`() {
        val f = FeatureExtractor.extract(build(rangeG = 4.0).window, cfg)
        assertTrue(f.saturatedMs >= 10.0, "sat ${f.saturatedMs}")
        assertNotNull(f.orientationChangeDeg)
    }
}
