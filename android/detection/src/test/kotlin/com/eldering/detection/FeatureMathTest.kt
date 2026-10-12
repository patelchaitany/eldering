package com.eldering.detection

import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Unit tests of every pure feature function (docs/research/detection-params.md §1). */
class FeatureMathTest {

    /** Uniform time axis from [from] (inclusive) with [n] samples at [hz]. */
    private fun times(from: Double, n: Int, hz: Double) = DoubleArray(n) { from + it / hz }

    @Nested
    inner class Basics {
        @Test
        fun `smv is the vector magnitude`() {
            assertEquals(1.0, FeatureMath.smv(0.0, 0.0, 1.0), 1e-12)
            assertEquals(sqrt(3.0), FeatureMath.smv(1.0, -1.0, 1.0), 1e-12)
        }

        @Test
        fun `capture factor follows cos(pi T over 2 x 80 ms) with a 0_7 floor`() {
            val t = 0.080
            assertEquals(0.707, FeatureMath.captureFactor(1.0 / 25, t, 0.7), 0.01)
            assertEquals(0.924, FeatureMath.captureFactor(1.0 / 50, t, 0.7), 0.01)
            assertEquals(0.981, FeatureMath.captureFactor(1.0 / 100, t, 0.7), 0.01)
            assertEquals(0.995, FeatureMath.captureFactor(1.0 / 200, t, 0.7), 0.01)
            assertEquals(0.7, FeatureMath.captureFactor(1.0 / 5, t, 0.7), 1e-12, "slower than T/2 hits the floor")
            assertEquals(1.0, FeatureMath.captureFactor(Double.NaN, t, 0.7))
        }

        @Test
        fun `median matches numpy for odd and even sizes`() {
            assertEquals(2.0, FeatureMath.median(doubleArrayOf(3.0, 1.0, 2.0)))
            assertEquals(2.5, FeatureMath.median(doubleArrayOf(4.0, 1.0, 2.0, 3.0)))
            assertTrue(FeatureMath.median(DoubleArray(0)).isNaN())
        }

        @Test
        fun `median period ignores a dropped sample`() {
            val t = doubleArrayOf(0.0, 0.02, 0.04, 0.08, 0.10, 0.12) // one 40 ms gap
            assertEquals(0.02, FeatureMath.medianPeriod(t, 0.0, 1.0), 1e-12)
            assertTrue(FeatureMath.medianPeriod(t, 0.5, 1.0).isNaN())
        }

        @Test
        fun `bounds, max, min and flagged counts respect window edges`() {
            val t = doubleArrayOf(-1.0, -0.5, 0.0, 0.5, 1.0)
            val v = doubleArrayOf(5.0, 1.0, 2.0, 7.0, 3.0)
            assertEquals(1, FeatureMath.lowerBound(t, -0.5))
            assertEquals(2, FeatureMath.upperBound(t, -0.5))
            assertEquals(7.0, FeatureMath.maxIn(t, v, -0.5, 0.5), "both ends inclusive")
            assertEquals(1.0, FeatureMath.minInHalfOpen(t, v, -0.5, 0.5), "upper end exclusive")
            assertEquals(2, FeatureMath.countFlagged(t, booleanArrayOf(true, true, false, true, false), -0.5, 1.0))
            assertTrue(FeatureMath.maxIn(t, v, 2.0, 3.0).isNaN())
            assertEquals(2, FeatureMath.nearestIndex(t, 0.1))
        }

        @Test
        fun `run around a sample covers the contiguous region above threshold`() {
            val v = doubleArrayOf(1.0, 2.5, 3.0, 2.2, 1.0, 2.6)
            assertEquals(1..3, FeatureMath.runAround(v, 2, 2.0))
            assertNull(FeatureMath.runAround(v, 0, 2.0))
            assertEquals(5..5, FeatureMath.runAround(v, 5, 2.0))
        }
    }

    @Nested
    inner class Impacts {
        private val t = times(-0.2, 121, 100.0) // 100 Hz, [-0.2, 1.0]

        private fun signal(vararg peaks: Pair<Double, Double>): DoubleArray = DoubleArray(t.size) { i ->
            var s = 1.0
            for ((at, amp) in peaks) {
                val d = (t[i] - at) / 0.02
                if (d > -1 && d < 1) s += amp * cos(d * PI / 2)
            }
            s
        }

        @Test
        fun `separate impacts with a deep valley count separately`() {
            val s = signal(0.0 to 3.0, 0.3 to 2.0, 0.8 to 1.5)
            assertEquals(3, FeatureMath.countImpacts(t, s, -0.2, 1.0, 1.8, 0.1, 0.5))
        }

        @Test
        fun `peaks closer than the separation merge`() {
            val s = signal(0.0 to 3.0, 0.06 to 2.5)
            assertEquals(1, FeatureMath.countImpacts(t, s, -0.2, 1.0, 1.8, 0.1, 0.5))
        }

        @Test
        fun `peaks without a deep enough valley merge`() {
            // Bumps riding on a 2.5 g plateau, 200 ms apart: the dips between them are only 0.4 g deep.
            val s = DoubleArray(t.size) { i -> if (t[i] in 0.0..0.4) 2.5 + 0.2 * cos(2 * PI * t[i] / 0.2) else 1.0 }
            assertEquals(1, FeatureMath.countImpacts(t, s, -0.2, 1.0, 1.8, 0.1, 0.5))
        }

        @Test
        fun `peaks below the threshold are ignored`() {
            assertEquals(0, FeatureMath.countImpacts(t, signal(0.0 to 0.7), -0.2, 1.0, 1.8, 0.1, 0.5))
        }
    }

    @Nested
    inner class Runs {
        private val t = times(-1.0, 101, 100.0)

        @Test
        fun `free-fall run duration is last minus first plus one period`() {
            val s = DoubleArray(t.size) { if (t[it] in -0.5..-0.3001) 0.2 else 1.0 } // 20 samples: -0.50 .. -0.31
            val runs = FeatureMath.runsAtOrBelow(t, s, -1.0, true, 0.0, false, 0.35, 0.1)
            assertEquals(1, runs.size)
            assertEquals(200.0, FeatureMath.longestMs(runs, 0.01), 1e-6)
        }

        @Test
        fun `a data gap splits a run`() {
            val tg = t.filter { it !in -0.45..-0.33 }.toDoubleArray() // 130 ms hole inside the run
            val s = DoubleArray(tg.size) { if (tg[it] in -0.6..-0.2) 0.2 else 1.0 }
            val whole = FeatureMath.runsAtOrBelow(tg, s, -1.0, true, 0.0, false, 0.35, 1.0)
            val split = FeatureMath.runsAtOrBelow(tg, s, -1.0, true, 0.0, false, 0.35, 0.1)
            assertEquals(1, whole.size)
            assertEquals(2, split.size)
            assertTrue(FeatureMath.longestMs(split, 0.01) < 200.0)
        }

        @Test
        fun `window bounds can be exclusive and a filter selects runs`() {
            val s = DoubleArray(t.size) { if (t[it] in 0.0..0.0 || t[it] in -0.2..-0.1) 0.1 else 1.0 }
            val runs = FeatureMath.runsAtOrBelow(t, s, -1.0, true, 0.0, false, 0.35, 0.1)
            assertEquals(1, runs.size, "the sample at t0 is excluded")
            assertEquals(0.0, FeatureMath.longestMs(runs, 0.01) { -it.end <= 0.05 }, "run ends 110 ms before t0")
            assertTrue(FeatureMath.longestMs(runs, 0.01) { -it.end <= 0.15 } > 0)
        }
    }

    @Nested
    inner class Rotation {
        private fun constantRate(w: DoubleArray, seconds: Double, hz: Double = 100.0): Array<DoubleArray> {
            val n = (seconds * hz).toInt() + 1
            return arrayOf(times(0.0, n, hz), DoubleArray(n) { w[0] }, DoubleArray(n) { w[1] }, DoubleArray(n) { w[2] })
        }

        @Test
        fun `constant rate integrates to rate times time`() {
            val (t, x, y, z) = constantRate(doubleArrayOf(0.0, 0.0, PI / 2), 1.0)
            assertEquals(90.0, FeatureMath.maxNetRotationDeg(t, x, y, z, 0.0, 1.0), 0.5)
            assertEquals(45.0, FeatureMath.maxNetRotationDeg(t, x, y, z, 0.0, 0.5), 0.5)
        }

        @Test
        fun `axis does not matter`() {
            val r = PI / 2 / sqrt(3.0)
            val (t, x, y, z) = constantRate(doubleArrayOf(r, -r, r), 1.0)
            assertEquals(90.0, FeatureMath.maxNetRotationDeg(t, x, y, z, 0.0, 1.0), 0.5)
        }

        @Test
        fun `swinging back and forth reports the maximum net angle, not the path length`() {
            val hz = 200.0
            val t = times(0.0, 401, hz) // 2 s
            val amp = Math.toRadians(20.0)
            val wx = DoubleArray(t.size) { amp * 2 * PI * cos(2 * PI * t[it]) } // angle = 20° sin(2πt)
            val zero = DoubleArray(t.size)
            assertEquals(20.0, FeatureMath.maxNetRotationDeg(t, wx, zero, zero, 0.0, 2.0), 0.5)
        }

        @Test
        fun `too few samples is absent`() {
            assertTrue(FeatureMath.maxNetRotationDeg(doubleArrayOf(0.0), doubleArrayOf(1.0), doubleArrayOf(0.0), doubleArrayOf(0.0), 0.0, 1.0).isNaN())
        }
    }

    @Nested
    inner class Orientation {
        @Test
        fun `angle between gravity vectors`() {
            assertEquals(90.0, FeatureMath.angleDeg(doubleArrayOf(0.0, 1.0, 0.0), doubleArrayOf(0.0, 0.0, 2.0)), 1e-9)
            assertEquals(0.0, FeatureMath.angleDeg(doubleArrayOf(0.0, 1.0, 0.0), doubleArrayOf(0.0, 3.0, 0.0)), 1e-6)
            assertTrue(FeatureMath.angleDeg(doubleArrayOf(0.0, 0.0, 0.0), doubleArrayOf(0.0, 1.0, 0.0)).isNaN())
        }

        @Test
        fun `mean vector uses a half-open window`() {
            val t = doubleArrayOf(0.0, 1.0, 2.0)
            val m = FeatureMath.meanVector(t, doubleArrayOf(1.0, 3.0, 100.0), doubleArrayOf(0.0, 0.0, 0.0), doubleArrayOf(2.0, 4.0, 0.0), 0.0, 2.0)!!
            assertEquals(2.0, m[0], 1e-12)
            assertEquals(3.0, m[2], 1e-12)
            assertNull(FeatureMath.meanVector(t, t, t, t, 5.0, 6.0))
        }

        @Test
        fun `horizontal ratio separates horizontal and vertical pushes`() {
            val up = doubleArrayOf(0.0, 0.0, 1.0)
            assertEquals(1.0, FeatureMath.horizontalRatio(doubleArrayOf(3.0, 0.0, 1.0), up), 1e-9)
            assertEquals(0.0, FeatureMath.horizontalRatio(doubleArrayOf(0.0, 0.0, 4.0), up), 1e-9)
            assertEquals(sin(PI / 4), FeatureMath.horizontalRatio(doubleArrayOf(2.0, 0.0, 3.0), up), 1e-9)
        }
    }

    @Nested
    inner class GridFeatures {
        @Test
        fun `interpolation is linear and clamps at the ends`() {
            val out = FeatureMath.interpolate(doubleArrayOf(0.0, 1.0), doubleArrayOf(0.0, 10.0), doubleArrayOf(-1.0, 0.25, 0.5, 2.0))
            assertEquals(listOf(0.0, 2.5, 5.0, 10.0), out.toList())
        }

        @Test
        fun `gradient is central inside and one-sided at the ends`() {
            val g = FeatureMath.gradient(doubleArrayOf(0.0, 1.0, 4.0, 9.0), 1.0)
            assertEquals(listOf(1.0, 2.0, 4.0, 5.0), g.toList())
        }

        @Test
        fun `grid helpers match numpy arange and tolerate rounding`() {
            val grid = FeatureMath.uniformGrid(-2.0, 10.0, 100)
            assertEquals(1200, grid.size)
            assertEquals(-2.0, grid.first())
            assertEquals(9.99, grid.last(), 1e-9)
            assertEquals(190..210, FeatureMath.gridRangeClosed(-2.0, 100, grid.size, -0.1, 0.1))
            assertEquals(400 until 500, FeatureMath.gridRange(-2.0, 100, grid.size, 2.0, 3.0))
        }

        @Test
        fun `standard deviation is the population std`() {
            assertEquals(1.0, FeatureMath.std(doubleArrayOf(1.0, 3.0, 1.0, 3.0), 0, 4), 1e-12)
            assertTrue(FeatureMath.std(doubleArrayOf(1.0), 1, 1).isNaN())
        }

        @Test
        fun `gait autocorrelation finds periodic gait and ignores noise or stillness`() {
            val hz = 100
            val n = 300
            val gait = DoubleArray(n) { 1.0 + 0.4 * sin(2 * PI * it / (0.55 * hz)) }
            assertTrue(FeatureMath.gaitAutocorr(gait, 0, n, hz, 0.25, 0.8, 0.08) > 0.9)
            val rng = java.util.Random(1)
            val noise = DoubleArray(n) { 1.0 + 0.2 * rng.nextGaussian() }
            assertTrue(FeatureMath.gaitAutocorr(noise, 0, n, hz, 0.25, 0.8, 0.08) < 0.4)
            val still = DoubleArray(n) { 1.0 + 0.01 * sin(it.toDouble()) }
            assertEquals(0.0, FeatureMath.gaitAutocorr(still, 0, n, hz, 0.25, 0.8, 0.08), "std below 0.08 g is no gait")
            assertEquals(0.0, FeatureMath.gaitAutocorr(gait, 0, 40, hz, 0.25, 0.8, 0.08), "too short")
        }

        @Test
        fun `trapezoid integrates over the inclusive index range`() {
            val t = doubleArrayOf(0.0, 0.1, 0.2, 0.3)
            val y = doubleArrayOf(0.0, 10.0, 10.0, 0.0)
            assertEquals(2.0, FeatureMath.trapezoid(t, y, 0, 3), 1e-12)
            assertEquals(1.0, FeatureMath.trapezoid(t, y, 1, 2), 1e-12)
        }
    }
}
