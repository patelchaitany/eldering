package com.eldering.detection

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.max

/**
 * Raw data of one candidate, cut from the ring buffers. Times are seconds relative to the candidate peak t0;
 * acceleration in g, gyro in rad/s, pressure in hPa, speed in m/s. Gyro, pressure and speed arrays may be empty.
 */
internal class CandidateWindow(
    val accelT: DoubleArray,
    val ax: DoubleArray,
    val ay: DoubleArray,
    val az: DoubleArray,
    val gyroT: DoubleArray,
    val gx: DoubleArray,
    val gy: DoubleArray,
    val gz: DoubleArray,
    val pressureT: DoubleArray,
    val pressure: DoubleArray,
    val speedT: DoubleArray,
    val speed: DoubleArray,
    /** Post-event analysis length W in seconds (10 s, 15 s in vehicle context). */
    val postWindowS: Double,
    /** Effective accelerometer full-scale range in g. */
    val rangeG: Double,
)

/** Features of one candidate window (docs/research/detection-params.md §1.2). Nullable = input unavailable. */
internal data class WindowFeatures(
    val rateHz: Double,
    val accelRangeG: Double,
    val gapCount: Int,
    val peakG: Double,
    val saturatedMs: Double,
    val aboveTwoGMs: Double,
    val impacts1s: Int,
    val impacts15s: Int,
    val freeFallMs: Double,
    val minSmvPreG: Double,
    val trueFreeFallMs: Double,
    val airborneAfterMs: Double,
    val bounceAirborneMs: Double,
    val vertVelMps: Double,
    val jerkPeakGps: Double,
    val rotationDeg: Double?,
    val fastRotationDeg: Double?,
    val preRotationDeg: Double?,
    val strikeRotationDeg: Double?,
    /** Max net rotation in any 0.5 s window of [t0−2 s, t0−0.5 s]: what the ongoing activity (gait swing) produces. */
    val baselineRotationDeg: Double?,
    val orientationChangeDeg: Double?,
    val handHeldBefore: Boolean,
    val horizRatio: Double,
    val dvLowerBoundMps: Double,
    val stillFraction: Double,
    val restNoiseG: Double?,
    val recovered: Boolean,
    val gaitPre: Double,
    val gaitPost: Double,
    val speedBeforeMps: Double?,
    val speedAfterMps: Double?,
    val pressureDeltaHpa: Double?,
) {
    /** Feature map with [FeatureKeys] names; unavailable inputs are omitted. */
    fun toMap(): MutableMap<String, Float> {
        val m = LinkedHashMap<String, Float>()
        m[FeatureKeys.PEAK_G] = peakG.toFloat()
        m[FeatureKeys.SATURATED_MS] = saturatedMs.toFloat()
        m[FeatureKeys.ABOVE_2G_MS] = aboveTwoGMs.toFloat()
        m[FeatureKeys.IMPACTS_1S] = impacts1s.toFloat()
        m[FeatureKeys.IMPACTS_1_5S] = impacts15s.toFloat()
        m[FeatureKeys.FREEFALL_MS] = freeFallMs.toFloat()
        m[FeatureKeys.MIN_SMV_PRE_G] = minSmvPreG.toFloat()
        m[FeatureKeys.TRUE_FREEFALL_MS] = trueFreeFallMs.toFloat()
        m[FeatureKeys.AIRBORNE_AFTER_MS] = airborneAfterMs.toFloat()
        m[FeatureKeys.BOUNCE_AIRBORNE_MS] = bounceAirborneMs.toFloat()
        m[FeatureKeys.VERTICAL_VELOCITY_MPS] = vertVelMps.toFloat()
        m[FeatureKeys.JERK_PEAK_GPS] = jerkPeakGps.toFloat()
        rotationDeg?.let { m[FeatureKeys.ROTATION_DEG] = it.toFloat() }
        fastRotationDeg?.let { m[FeatureKeys.FAST_ROTATION_DEG] = it.toFloat() }
        preRotationDeg?.let { m[FeatureKeys.PRE_ROTATION_DEG] = it.toFloat() }
        strikeRotationDeg?.let { m[FeatureKeys.STRIKE_ROTATION_DEG] = it.toFloat() }
        baselineRotationDeg?.let { m[FeatureKeys.BASELINE_ROTATION_DEG] = it.toFloat() }
        orientationChangeDeg?.let { m[FeatureKeys.ORIENTATION_CHANGE_DEG] = it.toFloat() }
        m[FeatureKeys.HAND_HELD_BEFORE] = if (handHeldBefore) 1f else 0f
        m[FeatureKeys.HORIZONTAL_RATIO] = horizRatio.toFloat()
        m[FeatureKeys.DV_LOWER_BOUND_MPS] = dvLowerBoundMps.toFloat()
        m[FeatureKeys.STILL_FRACTION] = stillFraction.toFloat()
        restNoiseG?.let {
            m[FeatureKeys.REST_NOISE_G] = it.toFloat()
            m[FeatureKeys.STILLNESS_VAR] = (it * it).toFloat()
        }
        m[FeatureKeys.RECOVERED] = if (recovered) 1f else 0f
        m[FeatureKeys.GAIT_PRE] = gaitPre.toFloat()
        m[FeatureKeys.GAIT_POST] = gaitPost.toFloat()
        speedBeforeMps?.let { m[FeatureKeys.SPEED_BEFORE] = it.toFloat() }
        speedAfterMps?.let { m[FeatureKeys.SPEED_AFTER] = it.toFloat() }
        pressureDeltaHpa?.let { m[FeatureKeys.PRESSURE_DELTA_HPA] = it.toFloat() }
        m[FeatureKeys.RATE_HZ] = rateHz.toFloat()
        m[FeatureKeys.ACCEL_RANGE_G] = accelRangeG.toFloat()
        m[FeatureKeys.GAP_COUNT] = gapCount.toFloat()
        return m
    }
}

/**
 * Windowed feature extraction (research §1). Peaks, saturation, free-fall runs and durations use raw samples;
 * jerk, stillness, autocorrelation and recovery use SMV linearly interpolated onto a uniform grid
 * ([DetectorConfig.gridHz]), which also absorbs jitter and mid-window rate changes.
 */
internal object FeatureExtractor {

    private const val PRE_GRAVITY_FROM_S = -2.0
    private const val PRE_GRAVITY_TO_S = -0.5
    private const val POST_GRAVITY_FROM_S = 1.5
    private const val POST_GRAVITY_TO_S = 3.5
    private const val IMPACT_GRAVITY_FROM_S = -0.3
    private const val IMPACT_GRAVITY_TO_S = -0.05

    fun extract(w: CandidateWindow, cfg: DetectorConfig): WindowFeatures {
        val t = w.accelT
        val n = t.size
        require(n >= 2) { "candidate window needs at least two accelerometer samples" }
        val s = DoubleArray(n) { FeatureMath.smv(w.ax[it], w.ay[it], w.az[it]) }
        val satLimit = cfg.saturationFraction * w.rangeG
        val sat = BooleanArray(n) { abs(w.ax[it]) >= satLimit || abs(w.ay[it]) >= satLimit || abs(w.az[it]) >= satLimit }

        // Sampling period of the impact: measured before t0, because the app raises the rate after the trigger.
        var period = FeatureMath.medianPeriod(t, -1.0, 0.0)
        if (period.isNaN() || period <= 0.0) period = FeatureMath.medianPeriod(t, t[0], t[n - 1])
        if (period.isNaN() || period <= 0.0) period = 0.01
        val maxGap = max(3 * period, cfg.maxGapMs / 1000.0)
        val duration = FeatureMath.sampleDurations(t, maxGap)

        // Gaps (research §4.2): counted, never spanned by runs, and stillness windows that overlap them are skipped.
        val gapStarts = ArrayList<Double>()
        val gapEnds = ArrayList<Double>()
        for (i in 1 until n) {
            if (t[i] - t[i - 1] > maxGap) {
                gapStarts.add(t[i - 1])
                gapEnds.add(t[i])
            }
        }

        val i0 = FeatureMath.nearestIndex(t, 0.0)
        val peak = FeatureMath.maxIn(t, s, -0.2, 0.3).let { if (it.isNaN()) s[i0] else it }
        var satMs = 0.0
        for (i in FeatureMath.lowerBound(t, -0.2) until FeatureMath.upperBound(t, 0.5)) if (sat[i]) satMs += duration[i] * 1000.0
        val aboveRun = FeatureMath.runAround(s, i0, cfg.aboveG.toDouble())
        val aboveTwoGMs = aboveRun?.sumOf { duration[it] * 1000.0 } ?: 0.0

        val minSep = cfg.impactMinSeparationMs / 1000.0
        val impactG = cfg.impactCountG.toDouble()
        val valley = cfg.impactValleyDropG.toDouble()
        val impacts1s = FeatureMath.countImpacts(t, s, -0.2, 1.0, impactG, minSep, valley)
        val impacts15s = FeatureMath.countImpacts(t, s, -0.05, 1.5, impactG, minSep, valley)

        // Free fall before the impact and airborne phases after it.
        val partialRuns = FeatureMath.runsAtOrBelow(t, s, -1.0, true, 0.0, false, cfg.partialFreeFallG.toDouble(), maxGap)
        val freeFallMs = FeatureMath.longestMs(partialRuns, period)
        val minPre = FeatureMath.minInHalfOpen(t, s, -1.0, 0.0).let { if (it.isNaN()) 1.0 else it }
        val trueRuns = FeatureMath.runsAtOrBelow(t, s, -1.0, true, 0.0, false, cfg.trueFreeFallG.toDouble(), maxGap)
        val maxLead = cfg.trueFreeFallMaxLeadMs / 1000.0
        val trueFreeFallMs = FeatureMath.longestMs(trueRuns, period) { -it.end <= maxLead + 1e-9 }
        val postRuns = FeatureMath.runsAtOrBelow(t, s, 0.05, false, 1.0, true, cfg.trueFreeFallG.toDouble(), maxGap)
        val airborneMs = FeatureMath.longestMs(postRuns, period)
        val bounceEnd = cfg.bounceWindowMs / 1000.0
        val bounceMs = FeatureMath.longestMs(postRuns, period) { it.end < bounceEnd }
        val vertVel = verticalVelocity(t, s, partialRuns)

        // Uniform grid over [−pre, W).
        val gridHz = cfg.gridHz
        val gridStart = -cfg.preWindowMs / 1000.0
        val grid = FeatureMath.uniformGrid(gridStart, w.postWindowS, gridHz)
        val sg = FeatureMath.interpolate(t, s, grid)
        val jerk = FeatureMath.gradient(sg, 1.0 / gridHz)
        var jerkPeak = 0.0
        for (k in FeatureMath.gridRangeClosed(gridStart, gridHz, grid.size, -0.1, 0.1)) jerkPeak = max(jerkPeak, abs(jerk[k]))

        // Gravity direction before / after / just before the impact.
        val gPre = FeatureMath.meanVector(t, w.ax, w.ay, w.az, PRE_GRAVITY_FROM_S, PRE_GRAVITY_TO_S)
        val gPost = FeatureMath.meanVector(t, w.ax, w.ay, w.az, POST_GRAVITY_FROM_S, POST_GRAVITY_TO_S)
        val orientChange = if (gPre != null && gPost != null) FeatureMath.angleDeg(gPre, gPost).takeIf { !it.isNaN() } else null
        val gPreUnit = gPre?.let { FeatureMath.unit(it) }
        val handHeld = gPreUnit != null && Math.toDegrees(acos(abs(gPreUnit[1]).coerceAtMost(1.0))) >= cfg.handHeldTiltDeg
        val gImpUnit = FeatureMath.meanVector(t, w.ax, w.ay, w.az, IMPACT_GRAVITY_FROM_S, IMPACT_GRAVITY_TO_S)
            ?.let { FeatureMath.unit(it) } ?: gPreUnit ?: doubleArrayOf(0.0, 0.0, 1.0)
        val horiz = FeatureMath.horizontalRatio(doubleArrayOf(w.ax[i0], w.ay[i0], w.az[i0]), gImpUnit)
        val dv = deltaVLowerBound(w, t, s, i0, gPreUnit ?: gImpUnit, cfg.dvRegionG.toDouble(), period)

        // Gyro rotation (only where the gyro data covers the interval).
        val rot = GyroRotation(w, cfg)
        val rotation = rot.netDeg(-1.0, 0.5)
        var fastRotation: Double? = null
        for (k in 0..10) {
            val start = -1.0 + 0.1 * k
            rot.netDeg(start, start + 0.5)?.let { fastRotation = max(fastRotation ?: 0.0, it) }
        }
        val preRotation = rot.netDeg(-0.5, 0.0)
        val strikeRotation = rot.netDeg(0.0, 1.0)
        var baselineRotation: Double? = null
        for (k in 0..10) {
            val start = -2.0 + 0.1 * k
            rot.netDeg(start, start + 0.5)?.let { baselineRotation = max(baselineRotation ?: 0.0, it) }
        }

        // Post-event stillness: 1 s windows stepped by 0.25 s over [2 s, W].
        val stds = ArrayList<Double>()
        var ws = 2.0
        while (ws <= w.postWindowS - 1.0 + 1e-9) {
            val we = ws + 1.0
            var overlapsGap = false
            for (g in gapStarts.indices) if (gapStarts[g] < we && gapEnds[g] > ws) overlapsGap = true
            if (!overlapsGap) {
                val r = FeatureMath.gridRange(gridStart, gridHz, grid.size, ws, we)
                if (!r.isEmpty()) stds.add(FeatureMath.std(sg, r.first, r.last + 1))
            }
            ws += 0.25
        }
        val stillFraction = if (stds.isEmpty()) 0.0 else stds.count { it <= cfg.stillStdG }.toDouble() / stds.size
        val restNoise = if (stds.isEmpty()) null else FeatureMath.median(stds.toDoubleArray())
        val maxStd = stds.maxOrNull() ?: 0.0

        // Recovery: gait resumed, or the phone was picked up (orientation moved from its resting direction).
        val minStd = cfg.gaitMinStdG.toDouble()
        val longPost = FeatureMath.gridRange(gridStart, gridHz, grid.size, 2.0, w.postWindowS)
        val gaitResumed = FeatureMath.gaitAutocorr(sg, longPost.first, longPost.last + 1, gridHz, 0.35, 1.3, minStd) >= cfg.gaitAutocorrMin
        val picked = pickedUp(w, grid, t, gridStart, gridHz, cfg) && maxStd >= cfg.pickupStdG
        val recovered = gaitResumed || picked
        val preGait = FeatureMath.gridRange(gridStart, gridHz, grid.size, -2.0, -0.3)
        val postGait = FeatureMath.gridRange(gridStart, gridHz, grid.size, 0.5, 3.5)
        val gaitPre = FeatureMath.gaitAutocorr(sg, preGait.first, preGait.last + 1, gridHz, 0.25, 0.8, minStd)
        val gaitPost = FeatureMath.gaitAutocorr(sg, postGait.first, postGait.last + 1, gridHz, 0.25, 0.8, minStd)

        return WindowFeatures(
            rateHz = 1.0 / period,
            accelRangeG = w.rangeG,
            gapCount = gapStarts.size,
            peakG = peak,
            saturatedMs = satMs,
            aboveTwoGMs = aboveTwoGMs,
            impacts1s = impacts1s,
            impacts15s = impacts15s,
            freeFallMs = freeFallMs,
            minSmvPreG = minPre,
            trueFreeFallMs = trueFreeFallMs,
            airborneAfterMs = airborneMs,
            bounceAirborneMs = bounceMs,
            vertVelMps = vertVel,
            jerkPeakGps = jerkPeak,
            rotationDeg = rotation,
            fastRotationDeg = fastRotation,
            preRotationDeg = preRotation,
            strikeRotationDeg = strikeRotation,
            baselineRotationDeg = baselineRotation,
            orientationChangeDeg = orientChange,
            handHeldBefore = handHeld,
            horizRatio = horiz,
            dvLowerBoundMps = dv,
            stillFraction = stillFraction,
            restNoiseG = restNoise,
            recovered = recovered,
            gaitPre = gaitPre,
            gaitPost = gaitPost,
            speedBeforeMps = medianSpeed(w, -15.0, -0.5),
            speedAfterMps = maxSpeed(w, 3.0, w.postWindowS),
            pressureDeltaHpa = pressureDelta(w),
        )
    }

    /** g0·∫(SMV − 1)dt from the start of the longest pre-impact free-fall run to t0 (log only). */
    private fun verticalVelocity(t: DoubleArray, s: DoubleArray, runs: List<FeatureMath.Run>): Double {
        val run = runs.maxByOrNull { it.end - it.start } ?: return 0.0
        val a = FeatureMath.lowerBound(t, run.start)
        val b = FeatureMath.lowerBound(t, 0.0)
        if (b - a < 1) return 0.0
        val y = DoubleArray(t.size) { (s[it] - 1.0) * G0 }
        return FeatureMath.trapezoid(t, y, a, b)
    }

    /** Δv lower bound: trapezoid ∫|a − ĝ|·g0 dt over the contiguous region SMV ≥ [regionG] around t0. */
    private fun deltaVLowerBound(w: CandidateWindow, t: DoubleArray, s: DoubleArray, i0: Int, g: DoubleArray, regionG: Double, period: Double): Double {
        fun dyn(i: Int): Double {
            val dx = w.ax[i] - g[0]
            val dy = w.ay[i] - g[1]
            val dz = w.az[i] - g[2]
            return FeatureMath.smv(dx, dy, dz) * G0
        }
        val run = FeatureMath.runAround(s, i0, regionG) ?: return dyn(i0) * period
        if (run.first == run.last) return dyn(i0) * period
        val y = DoubleArray(t.size)
        for (i in run) y[i] = dyn(i)
        return FeatureMath.trapezoid(t, y, run.first, run.last)
    }

    /** Pickup test (research §1.2 "Recovered"): resting direction vs the last 2 s differ by ≥ pickupOrientDeg. */
    private fun pickedUp(w: CandidateWindow, grid: DoubleArray, t: DoubleArray, gridStart: Double, gridHz: Int, cfg: DetectorConfig): Boolean {
        val gx = FeatureMath.interpolate(t, w.ax, grid)
        val gy = FeatureMath.interpolate(t, w.ay, grid)
        val gz = FeatureMath.interpolate(t, w.az, grid)
        val rest = FeatureMath.gridRange(gridStart, gridHz, grid.size, POST_GRAVITY_FROM_S, POST_GRAVITY_TO_S)
        val late = FeatureMath.gridRange(gridStart, gridHz, grid.size, w.postWindowS - 2.0, w.postWindowS)
        if (rest.isEmpty() || late.isEmpty()) return false
        fun mean(r: IntRange): DoubleArray {
            var x = 0.0
            var y = 0.0
            var z = 0.0
            for (k in r) {
                x += gx[k]
                y += gy[k]
                z += gz[k]
            }
            val c = (r.last - r.first + 1).toDouble()
            return doubleArrayOf(x / c, y / c, z / c)
        }
        val angle = FeatureMath.angleDeg(mean(rest), mean(late))
        return !angle.isNaN() && angle >= cfg.pickupOrientDeg
    }

    private fun medianSpeed(w: CandidateWindow, from: Double, to: Double): Double? {
        val a = FeatureMath.lowerBound(w.speedT, from)
        val b = FeatureMath.upperBound(w.speedT, to)
        if (b <= a) return null
        return FeatureMath.median(w.speed.copyOfRange(a, b))
    }

    private fun maxSpeed(w: CandidateWindow, from: Double, to: Double): Double? {
        val a = FeatureMath.lowerBound(w.speedT, from)
        val b = FeatureMath.upperBound(w.speedT, to)
        if (b <= a) return null
        var best = w.speed[a]
        for (i in a until b) best = max(best, w.speed[i])
        return best
    }

    /** median p[t0+2, t0+5) − median p[t0−3, t0−0.5); null without samples in both windows (research §6). */
    private fun pressureDelta(w: CandidateWindow): Double? {
        fun medianIn(from: Double, to: Double): Double {
            val a = FeatureMath.lowerBound(w.pressureT, from)
            val b = FeatureMath.lowerBound(w.pressureT, to)
            return if (b > a) FeatureMath.median(w.pressure.copyOfRange(a, b)) else Double.NaN
        }
        val delta = medianIn(2.0, 5.0) - medianIn(-3.0, -0.5)
        return if (delta.isNaN()) null else delta
    }

    /**
     * Net-rotation queries over the gyro samples of a window. An interval is answered only when the gyro data
     * actually covers it (a gyro that was off, or switched on only after the trigger, yields null rather than an
     * underestimate).
     */
    private class GyroRotation(private val w: CandidateWindow, cfg: DetectorConfig) {
        private val tolerance: Double

        init {
            val p = FeatureMath.medianPeriod(w.gyroT, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY)
            tolerance = max(if (p.isNaN()) 0.0 else 3 * p, cfg.gyroCoverageToleranceMs / 1000.0)
        }

        fun netDeg(from: Double, to: Double): Double? {
            val t = w.gyroT
            if (t.size < 2) return null
            val a = FeatureMath.lowerBound(t, from)
            val b = FeatureMath.upperBound(t, to)
            if (b - a < 2) return null
            if (t[a] - from > tolerance || to - t[b - 1] > tolerance) return null
            val deg = FeatureMath.maxNetRotationDeg(t, w.gx, w.gy, w.gz, from, to)
            return if (deg.isNaN()) null else deg
        }
    }
}
