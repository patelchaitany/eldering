package com.eldering.detection

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** Standard gravity, m/s². The detector works internally in g (research §0.1). */
internal const val G0: Double = 9.80665

/**
 * Pure feature functions used by [FeatureExtractor] (docs/research/detection-params.md §1).
 *
 * Conventions: times are seconds relative to the candidate peak t0 (so t0 = 0), accelerations are in g,
 * angular rates in rad/s. Arrays of times are sorted ascending. "Absent" results are `Double.NaN` so the
 * functions stay allocation-free and easy to test; [FeatureExtractor] maps NaN to null where it matters.
 */
internal object FeatureMath {

    /** Signal magnitude vector |a|. */
    fun smv(x: Double, y: Double, z: Double): Double = sqrt(x * x + y * y + z * z)

    /**
     * Fraction of a half-sine impact of duration [impactS] that a point sample at [periodS] is guaranteed to
     * capture: `max(floor, cos(π·period / (2·T)))` (research §2.1 and §4.2).
     */
    fun captureFactor(periodS: Double, impactS: Double, floor: Double): Double {
        if (!(periodS > 0.0) || !(impactS > 0.0)) return 1.0
        val arg = PI * periodS / (2.0 * impactS)
        return if (arg >= PI / 2) floor else max(floor, cos(arg))
    }

    /** First index i with t[i] >= x. */
    fun lowerBound(t: DoubleArray, x: Double): Int {
        var lo = 0
        var hi = t.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (t[mid] < x) lo = mid + 1 else hi = mid
        }
        return lo
    }

    /** First index i with t[i] > x. */
    fun upperBound(t: DoubleArray, x: Double): Int {
        var lo = 0
        var hi = t.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (t[mid] <= x) lo = mid + 1 else hi = mid
        }
        return lo
    }

    /** Median (mean of the two middle values for even sizes, like numpy); NaN when empty. */
    fun median(values: DoubleArray): Double {
        if (values.isEmpty()) return Double.NaN
        val s = values.copyOf()
        s.sort()
        val m = s.size / 2
        return if (s.size % 2 == 1) s[m] else 0.5 * (s[m - 1] + s[m])
    }

    /** Median spacing of the samples whose times lie in [from, to]; NaN with fewer than two samples. */
    fun medianPeriod(t: DoubleArray, from: Double, to: Double): Double {
        val a = lowerBound(t, from)
        val b = upperBound(t, to)
        if (b - a < 2) return Double.NaN
        return median(DoubleArray(b - a - 1) { t[a + it + 1] - t[a + it] })
    }

    /**
     * Time each sample stands for: half the distance to its neighbours (one-sided at the ends), capped at [maxGapS]
     * so a data gap does not inflate durations. Equals the period for uniform sampling and follows rate changes.
     */
    fun sampleDurations(t: DoubleArray, maxGapS: Double): DoubleArray {
        val n = t.size
        if (n < 2) return DoubleArray(n) { 0.0 }
        return DoubleArray(n) { i ->
            val d = when (i) {
                0 -> t[1] - t[0]
                n - 1 -> t[n - 1] - t[n - 2]
                else -> 0.5 * (t[i + 1] - t[i - 1])
            }
            min(d, maxGapS)
        }
    }

    /** Max of v over samples with t in [from, to]; NaN if none. */
    fun maxIn(t: DoubleArray, v: DoubleArray, from: Double, to: Double): Double {
        var best = Double.NaN
        for (i in lowerBound(t, from) until upperBound(t, to)) if (best.isNaN() || v[i] > best) best = v[i]
        return best
    }

    /** Min of v over samples with t in [from, to); NaN if none. */
    fun minInHalfOpen(t: DoubleArray, v: DoubleArray, from: Double, to: Double): Double {
        var best = Double.NaN
        for (i in lowerBound(t, from) until lowerBound(t, to)) if (best.isNaN() || v[i] < best) best = v[i]
        return best
    }

    /** Number of flagged samples with t in [from, to]. */
    fun countFlagged(t: DoubleArray, flags: BooleanArray, from: Double, to: Double): Int {
        var n = 0
        for (i in lowerBound(t, from) until upperBound(t, to)) if (flags[i]) n++
        return n
    }

    /** Index of the sample closest to time [x] (the earliest one on ties); -1 if [t] is empty. */
    fun nearestIndex(t: DoubleArray, x: Double): Int {
        if (t.isEmpty()) return -1
        val i = lowerBound(t, x)
        if (i == 0) return 0
        if (i == t.size) return t.size - 1
        return if (x - t[i - 1] <= t[i] - x) i - 1 else i
    }

    /** The contiguous index run with v >= [threshold] that contains [i0], or null if v[i0] < threshold. */
    fun runAround(v: DoubleArray, i0: Int, threshold: Double): IntRange? {
        if (i0 !in v.indices || v[i0] < threshold) return null
        var i = i0
        var j = i0
        while (i > 0 && v[i - 1] >= threshold) i--
        while (j < v.size - 1 && v[j + 1] >= threshold) j++
        return i..j
    }

    /**
     * Number of distinct impacts (research §1.2 "Impacts in 1 s"): local maxima of [s] >= [thresholdG] with t in
     * [from, to] (the first and last sample of the window are never peaks). A peak closer than [minSeparationS] to
     * the previous one, or not separated from it by a valley at least [valleyDropG] below the smaller of the two,
     * is merged into it (the larger survives).
     */
    fun countImpacts(
        t: DoubleArray,
        s: DoubleArray,
        from: Double,
        to: Double,
        thresholdG: Double,
        minSeparationS: Double,
        valleyDropG: Double,
    ): Int {
        val a = lowerBound(t, from)
        val b = upperBound(t, to) // exclusive
        val peaks = ArrayList<Int>()
        for (k in (a + 1) until (b - 1)) {
            if (s[k] < thresholdG || s[k] < s[k - 1] || s[k] < s[k + 1]) continue
            if (peaks.isNotEmpty()) {
                val last = peaks[peaks.size - 1]
                var valley = Double.POSITIVE_INFINITY
                for (m in (last + 1) until k) valley = min(valley, s[m])
                val tooClose = t[k] - t[last] < minSeparationS
                val shallow = valley > min(s[k], s[last]) - valleyDropG
                if (tooClose || shallow) {
                    if (s[k] > s[last]) peaks[peaks.size - 1] = k
                    continue
                }
            }
            peaks.add(k)
        }
        return peaks.size
    }

    /** A run of consecutive samples; [start] and [end] are the times of its first and last sample. */
    data class Run(val start: Double, val end: Double) {
        /** Duration as `last − first + period`, in ms (research §1.2). */
        fun durationMs(periodS: Double): Double = (end - start + periodS) * 1000.0
    }

    /**
     * Runs of consecutive samples with v <= [threshold] among the samples whose time lies inside the window
     * (bounds inclusive or exclusive as given). A spacing larger than [maxGapS] splits a run, so a run never spans
     * a data gap (research §4.2).
     */
    fun runsAtOrBelow(
        t: DoubleArray,
        v: DoubleArray,
        from: Double,
        fromInclusive: Boolean,
        to: Double,
        toInclusive: Boolean,
        threshold: Double,
        maxGapS: Double,
    ): List<Run> {
        val a = if (fromInclusive) lowerBound(t, from) else upperBound(t, from)
        val b = if (toInclusive) upperBound(t, to) else lowerBound(t, to)
        val out = ArrayList<Run>()
        var start = -1
        var prev = -1
        for (i in a until b) {
            val gap = prev >= 0 && t[i] - t[prev] > maxGapS
            if (v[i] <= threshold) {
                if (start >= 0 && gap) {
                    out.add(Run(t[start], t[prev]))
                    start = -1
                }
                if (start < 0) start = i
            } else if (start >= 0) {
                out.add(Run(t[start], t[prev]))
                start = -1
            }
            prev = i
        }
        if (start >= 0) out.add(Run(t[start], t[prev]))
        return out
    }

    /** Longest of [runs] in ms (0 if none), counting only runs accepted by [accept]. */
    inline fun longestMs(runs: List<Run>, periodS: Double, accept: (Run) -> Boolean = { true }): Double {
        var best = 0.0
        for (r in runs) if (accept(r)) best = max(best, r.durationMs(periodS))
        return best
    }

    /**
     * Maximum net rotation angle (degrees) reached from the start of [from, to], integrating raw gyro samples as a
     * quaternion with the trapezoid rate between samples (research §1.3). NaN with fewer than two samples.
     * Net rotation, unlike ∫|ω|dt, ignores swinging back and forth.
     */
    fun maxNetRotationDeg(
        t: DoubleArray,
        wx: DoubleArray,
        wy: DoubleArray,
        wz: DoubleArray,
        from: Double,
        to: Double,
    ): Double {
        val a = lowerBound(t, from)
        val b = upperBound(t, to)
        if (b - a < 2) return Double.NaN
        var qw = 1.0
        var qx = 0.0
        var qy = 0.0
        var qz = 0.0
        var best = 0.0
        for (i in (a + 1) until b) {
            val dt = t[i] - t[i - 1]
            val ox = 0.5 * (wx[i] + wx[i - 1])
            val oy = 0.5 * (wy[i] + wy[i - 1])
            val oz = 0.5 * (wz[i] + wz[i - 1])
            val n = sqrt(ox * ox + oy * oy + oz * oz)
            val ang = n * dt
            if (ang > 1e-12) {
                val s = sin(ang / 2) / n
                val c = cos(ang / 2)
                val dx = ox * s
                val dy = oy * s
                val dz = oz * s
                val nw = qw * c - qx * dx - qy * dy - qz * dz
                val nx = qw * dx + qx * c + qy * dz - qz * dy
                val ny = qw * dy - qx * dz + qy * c + qz * dx
                val nz = qw * dz + qx * dy - qy * dx + qz * c
                val norm = sqrt(nw * nw + nx * nx + ny * ny + nz * nz)
                qw = nw / norm
                qx = nx / norm
                qy = ny / norm
                qz = nz / norm
            }
            best = max(best, Math.toDegrees(2 * acos(min(1.0, abs(qw)))))
        }
        return best
    }

    /** Mean vector of samples with t in [from, to); null if none. */
    fun meanVector(t: DoubleArray, x: DoubleArray, y: DoubleArray, z: DoubleArray, from: Double, to: Double): DoubleArray? {
        val a = lowerBound(t, from)
        val b = lowerBound(t, to)
        if (b <= a) return null
        var sx = 0.0
        var sy = 0.0
        var sz = 0.0
        for (i in a until b) {
            sx += x[i]
            sy += y[i]
            sz += z[i]
        }
        val n = (b - a).toDouble()
        return doubleArrayOf(sx / n, sy / n, sz / n)
    }

    fun norm(v: DoubleArray): Double = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])

    /** Unit vector, or null for a (near-)zero vector. */
    fun unit(v: DoubleArray): DoubleArray? {
        val n = norm(v)
        return if (n < 1e-9) null else doubleArrayOf(v[0] / n, v[1] / n, v[2] / n)
    }

    /** Angle between two vectors in degrees; NaN if either is zero. */
    fun angleDeg(a: DoubleArray, b: DoubleArray): Double {
        val na = norm(a)
        val nb = norm(b)
        if (na < 1e-12 || nb < 1e-12) return Double.NaN
        val c = (a[0] * b[0] + a[1] * b[1] + a[2] * b[2]) / (na * nb)
        return Math.toDegrees(acos(c.coerceIn(-1.0, 1.0)))
    }

    /** Linear interpolation of (t, v) at [x] values (ascending), clamped to the end values like `numpy.interp`. */
    fun interpolate(t: DoubleArray, v: DoubleArray, x: DoubleArray): DoubleArray {
        val out = DoubleArray(x.size)
        if (t.isEmpty()) return out.also { it.fill(Double.NaN) }
        var j = 0
        for (k in x.indices) {
            val xk = x[k]
            if (xk <= t[0]) {
                out[k] = v[0]
                continue
            }
            if (xk >= t[t.size - 1]) {
                out[k] = v[t.size - 1]
                continue
            }
            while (j < t.size - 2 && t[j + 1] < xk) j++
            val t0 = t[j]
            val t1 = t[j + 1]
            out[k] = if (t1 > t0) v[j] + (v[j + 1] - v[j]) * (xk - t0) / (t1 - t0) else v[j + 1]
        }
        return out
    }

    /** Derivative with central differences inside and one-sided differences at the ends (`numpy.gradient`). */
    fun gradient(v: DoubleArray, dx: Double): DoubleArray {
        val n = v.size
        val out = DoubleArray(n)
        if (n < 2) return out
        out[0] = (v[1] - v[0]) / dx
        out[n - 1] = (v[n - 1] - v[n - 2]) / dx
        for (i in 1 until n - 1) out[i] = (v[i + 1] - v[i - 1]) / (2 * dx)
        return out
    }

    /** Population standard deviation of v[from until to]; NaN if empty. */
    fun std(v: DoubleArray, from: Int, to: Int): Double {
        if (to <= from) return Double.NaN
        var mean = 0.0
        for (i in from until to) mean += v[i]
        mean /= (to - from)
        var ss = 0.0
        for (i in from until to) {
            val d = v[i] - mean
            ss += d * d
        }
        return sqrt(ss / (to - from))
    }

    /**
     * Gait periodicity (research §1.3 `gaitAutocorr`): the max Pearson correlation between x[from..to) and itself
     * shifted by a lag in [loS, hiS] on a [gridHz] grid. Returns 0 when the segment is shorter than [minLen] samples
     * or its std is below [minStd] (no gait).
     */
    fun gaitAutocorr(x: DoubleArray, from: Int, to: Int, gridHz: Int, loS: Double, hiS: Double, minStd: Double, minLen: Int = 50): Double {
        val len = to - from
        if (len < minLen) return 0.0
        if (std(x, from, to) < minStd) return 0.0
        var best = 0.0
        val loLag = Math.round(loS * gridHz).toInt().coerceAtLeast(1)
        val hiLag = Math.round(hiS * gridHz).toInt()
        for (lag in loLag..hiLag) {
            val n = len - lag
            if (n <= 10) break
            var sa = 0.0
            var sb = 0.0
            var saa = 0.0
            var sbb = 0.0
            var sab = 0.0
            for (i in 0 until n) {
                val a = x[from + i]
                val b = x[from + i + lag]
                sa += a
                sb += b
                saa += a * a
                sbb += b * b
                sab += a * b
            }
            val cov = sab / n - (sa / n) * (sb / n)
            val va = saa / n - (sa / n) * (sa / n)
            val vb = sbb / n - (sb / n) * (sb / n)
            if (va > 1e-12 && vb > 1e-12) best = max(best, cov / sqrt(va * vb))
        }
        return best
    }

    /**
     * Share of the dynamic acceleration d = a − ĝ that is horizontal (perpendicular to the gravity unit vector
     * [gUnit]); a is in g. 0 for a purely vertical push, 1 for a purely horizontal one (research §1.2).
     */
    fun horizontalRatio(a: DoubleArray, gUnit: DoubleArray): Double {
        val dx = a[0] - gUnit[0]
        val dy = a[1] - gUnit[1]
        val dz = a[2] - gUnit[2]
        val along = dx * gUnit[0] + dy * gUnit[1] + dz * gUnit[2]
        val hx = dx - along * gUnit[0]
        val hy = dy - along * gUnit[1]
        val hz = dz - along * gUnit[2]
        val dn = sqrt(dx * dx + dy * dy + dz * dz)
        return sqrt(hx * hx + hy * hy + hz * hz) / max(1e-6, dn)
    }

    /** Trapezoid integral of y over t for indices [from, to] inclusive. */
    fun trapezoid(t: DoubleArray, y: DoubleArray, from: Int, to: Int): Double {
        var sum = 0.0
        for (i in (from + 1)..to) sum += 0.5 * (y[i] + y[i - 1]) * (t[i] - t[i - 1])
        return sum
    }

    /** Uniform grid `start + k/hz` for k = 0 until the first value >= end (like `numpy.arange`). */
    fun uniformGrid(start: Double, end: Double, hz: Int): DoubleArray {
        val n = max(0, ceil((end - start) * hz - 1e-9).toInt())
        return DoubleArray(n) { start + it.toDouble() / hz }
    }

    /** Index range of grid points (grid = start + k/hz) within [from, to) using a small tolerance. */
    fun gridRange(gridStart: Double, hz: Int, size: Int, from: Double, to: Double): IntRange {
        val a = max(0, ceil((from - gridStart) * hz - 1e-6).toInt())
        val b = min(size, ceil((to - gridStart) * hz - 1e-6).toInt())
        return a until b
    }

    /** Index range of grid points within the closed interval [from, to] using a small tolerance. */
    fun gridRangeClosed(gridStart: Double, hz: Int, size: Int, from: Double, to: Double): IntRange {
        val a = max(0, ceil((from - gridStart) * hz - 1e-6).toInt())
        val b = min(size - 1, floor((to - gridStart) * hz + 1e-6).toInt())
        return a..b
    }
}
