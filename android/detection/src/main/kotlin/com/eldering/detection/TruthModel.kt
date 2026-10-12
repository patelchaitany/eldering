package com.eldering.detection

import java.util.Random
import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** 3×3 rotation helpers on row-major DoubleArray(9). */
internal object Rot3 {
    val IDENTITY: DoubleArray = doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)

    fun axisAngle(axis: DoubleArray, angle: Double): DoubleArray {
        val n = sqrt(axis[0] * axis[0] + axis[1] * axis[1] + axis[2] * axis[2])
        val x = axis[0] / n
        val y = axis[1] / n
        val z = axis[2] / n
        val c = cos(angle)
        val s = sin(angle)
        val k = 1 - c
        return doubleArrayOf(
            c + x * x * k, x * y * k - z * s, x * z * k + y * s,
            y * x * k + z * s, c + y * y * k, y * z * k - x * s,
            z * x * k - y * s, z * y * k + x * s, c + z * z * k,
        )
    }

    fun mul(a: DoubleArray, b: DoubleArray): DoubleArray {
        val r = DoubleArray(9)
        for (i in 0..2) for (j in 0..2) r[i * 3 + j] = a[i * 3] * b[j] + a[i * 3 + 1] * b[3 + j] + a[i * 3 + 2] * b[6 + j]
        return r
    }

    fun transpose(a: DoubleArray): DoubleArray = doubleArrayOf(a[0], a[3], a[6], a[1], a[4], a[7], a[2], a[5], a[8])

    /** Rotation (phone → world) that maps the phone-frame gravity direction [g] onto world up (0, 0, 1). */
    fun fromGravity(g: DoubleArray): DoubleArray {
        val n = sqrt(g[0] * g[0] + g[1] * g[1] + g[2] * g[2])
        val gx = g[0] / n
        val gy = g[1] / n
        val gz = g[2] / n
        // v = g × up
        val vx = gy
        val vy = -gx
        val s = sqrt(vx * vx + vy * vy)
        if (s < 1e-9) return if (gz > 0) IDENTITY.copyOf() else axisAngle(doubleArrayOf(1.0, 0.0, 0.0), PI)
        return axisAngle(doubleArrayOf(vx / s, vy / s, 0.0), atan2(s, gz))
    }

    /** Uniformly random rotation (normalised Gaussian quaternion). */
    fun random(rng: Random): DoubleArray {
        var w: Double
        var x: Double
        var y: Double
        var z: Double
        var n: Double
        do {
            w = rng.nextGaussian()
            x = rng.nextGaussian()
            y = rng.nextGaussian()
            z = rng.nextGaussian()
            n = sqrt(w * w + x * x + y * y + z * z)
        } while (n < 1e-6)
        w /= n
        x /= n
        y /= n
        z /= n
        return doubleArrayOf(
            1 - 2 * (y * y + z * z), 2 * (x * y - z * w), 2 * (x * z + y * w),
            2 * (x * y + z * w), 1 - 2 * (x * x + z * z), 2 * (y * z - x * w),
            2 * (x * z - y * w), 2 * (y * z + x * w), 1 - 2 * (x * x + y * y),
        )
    }
}

/** Carrying positions: gravity direction in the carrier (phone-as-carried) frame (research §5.1). */
internal object Carry {
    val POCKET = doubleArrayOf(0.0, 0.98, 0.17) // upright thigh pocket, ~10° tilt
    val HAND = doubleArrayOf(0.0, 0.77, 0.64) // screen to face, ~40° from vertical
    val MOUNT = doubleArrayOf(0.0, 0.94, 0.34) // dash mount
    val FLAT = doubleArrayOf(0.0, 0.0, 1.0)
    val UP = doubleArrayOf(0.0, 0.0, 1.0)
    val X = doubleArrayOf(1.0, 0.0, 0.0)
    val Y = doubleArrayOf(0.0, 1.0, 0.0)
}

/**
 * Ground-truth motion on a 1 kHz grid (research §5.1). World frame z is up; the specific force is
 * `f_w = a_dyn + (0, 0, 1) g`, so free fall is `a_dyn = (0, 0, −1)`. The carrier attitude is
 * `R(t) = R0 · Π Rot(u_k, θ_k(t)) · Rot(x, swing(t))` with raised-cosine rotation profiles. Units: g, s, rad.
 */
internal class Truth(val durationS: Double, carrierGravity: DoubleArray, val rng: Random) {
    val n: Int = (durationS * FS).toInt()
    val dynW: Array<DoubleArray> = Array(3) { DoubleArray(n) }
    val extraP: Array<DoubleArray> = Array(3) { DoubleArray(n) }
    val swing: DoubleArray = DoubleArray(n)
    private val r0 = Rot3.fromGravity(carrierGravity)
    private val segments = ArrayList<Segment>()
    val speeds: MutableList<Pair<Double, Double>> = ArrayList()
    val modeChanges: MutableList<Pair<Double, Mode>> = ArrayList()
    /** (time s, metres the phone moved down) steps for the optional barometer. */
    val heightDrops: MutableList<Pair<Double, Double>> = ArrayList()

    private class Segment(val t0: Double, val dur: Double, val angle: Double, val axis: DoubleArray) {
        fun theta(t: Double): Double {
            if (t <= t0) return 0.0
            if (t >= t0 + dur) return angle
            val s = (t - t0) / dur
            return angle * (s - sin(2 * PI * s) / (2 * PI))
        }
    }

    fun time(i: Int): Double = i.toDouble() / FS

    /** Grid indices with a <= t < b. */
    fun idx(a: Double, b: Double): IntRange {
        var i = max(0, floor(a * FS).toInt() - 1)
        while (i < n && time(i) < a) i++
        var j = min(n, max(i, ceil(b * FS).toInt() + 1))
        while (j > i && time(j - 1) >= b) j--
        return i until j
    }

    private fun unit(v: DoubleArray): DoubleArray {
        val l = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])
        return doubleArrayOf(v[0] / l, v[1] / l, v[2] / l)
    }

    /** Half-sine pulse of [amp] g over [dur] s along a world axis. */
    fun halfSineWorld(t0: Double, dur: Double, amp: Double, axis: DoubleArray) = halfSine(dynW, t0, dur, amp, axis)

    /** Half-sine pulse of [amp] g over [dur] s along a carrier-frame axis. */
    fun halfSinePhone(t0: Double, dur: Double, amp: Double, axis: DoubleArray) = halfSine(extraP, t0, dur, amp, axis)

    private fun halfSine(target: Array<DoubleArray>, t0: Double, dur: Double, amp: Double, axis: DoubleArray) {
        val u = unit(axis)
        for (i in idx(t0, t0 + dur)) {
            val v = amp * sin(PI * (time(i) - t0) / dur)
            for (k in 0..2) target[k][i] += v * u[k]
        }
    }

    /** Total specific force zero (true free fall) over [a, b). */
    fun freeFall(a: Double, b: Double) {
        for (i in idx(a, b)) {
            dynW[0][i] = 0.0
            dynW[1][i] = 0.0
            dynW[2][i] = -1.0
        }
    }

    /** Adds a constant world-frame acceleration over [a, b). */
    fun addWorld(a: Double, b: Double, v: DoubleArray) {
        for (i in idx(a, b)) for (k in 0..2) dynW[k][i] += v[k]
    }

    /** Raised-cosine rotation by [angleRad] about a carrier axis over [dur] s. */
    fun rotate(t0: Double, dur: Double, angleRad: Double, axis: DoubleArray = Carry.X) {
        segments.add(Segment(t0, dur, angleRad, unit(axis)))
    }

    /** Rotates about [axis] so that the carrier-frame gravity ends nearest [target]; returns the angle (rad). */
    fun orientTo(t0: Double, dur: Double, target: DoubleArray, axis: DoubleArray = Carry.X): Double {
        val tu = unit(target)
        val start = attitude(t0, includeSwing = false)
        val u = unit(axis)
        var bestErr = Double.MAX_VALUE
        var bestAng = 0.0
        for (deg in -360..360) {
            val ang = Math.toRadians(deg.toDouble())
            val r = Rot3.mul(start, Rot3.axisAngle(u, ang))
            // carrier-frame gravity = Rᵀ·(0,0,1) = third row of R
            val err = acos((r[6] * tu[0] + r[7] * tu[1] + r[8] * tu[2]).coerceIn(-1.0, 1.0))
            if (err < bestErr - 1e-9 || (kotlin.math.abs(err - bestErr) < 1e-9 && kotlin.math.abs(ang) < kotlin.math.abs(bestAng))) {
                bestErr = err
                bestAng = ang
            }
        }
        rotate(t0, dur, bestAng, u)
        return bestAng
    }

    /**
     * Gait (research §5.1): vertical body oscillation, heel strikes along (0.15, 0.1, 1) alternating full / [alt]
     * amplitude (pocket leg / other leg), an unloading dip before each strike, and a thigh swing about carrier x.
     */
    fun gait(a: Double, b: Double, cadence: Double, peakG: Double, troughG: Double, swingRad: Double, strikeMs: Double = 30.0, alt: Double = 0.6) {
        val base = (1 - troughG) * 0.5
        val fade = min(0.25, (b - a) / 2) // legs do not snap still: taper the swing over the last 0.25 s
        for (i in idx(a, b)) {
            val tt = time(i) - a
            dynW[2][i] += -base * cos(2 * PI * cadence * tt) * 0.6
            val left = b - time(i)
            val taper = if (left >= fade) 1.0 else 0.5 - 0.5 * cos(PI * left / fade)
            swing[i] += taper * swingRad * sin(2 * PI * (cadence / 2) * tt)
        }
        var k = 0
        var ts = a
        while (ts < b - 0.05) {
            val full = k % 2 == 0
            halfSineWorld(ts, strikeMs / 1000, if (full) peakG - 1.0 else (peakG - 1.0) * alt, doubleArrayOf(0.15, 0.1, 1.0))
            halfSineWorld(ts - 0.12, 0.10, -(1 - troughG) * (if (full) 1.0 else 0.6), Carry.UP)
            ts += 1 / cadence
            k++
        }
    }

    /** White noise of [sigma] g per carrier axis (motion, not sensor noise). */
    fun noise(a: Double, b: Double, sigma: Double) {
        for (i in idx(a, b)) for (k in 0..2) extraP[k][i] += rng.nextGaussian() * sigma
    }

    /** Sinusoid on a carrier axis (absolute time phase). */
    fun sine(a: Double, b: Double, freqHz: Double, amp: Double, axis: DoubleArray = Carry.UP) {
        for (i in idx(a, b)) {
            val v = amp * sin(2 * PI * freqHz * time(i))
            for (k in 0..2) extraP[k][i] += v * axis[k]
        }
    }

    /** Exponentially decaying ring along a world axis starting at [a]. */
    fun ringWorld(a: Double, b: Double, amp: Double, tau: Double, freqHz: Double, axis: DoubleArray) {
        val u = unit(axis)
        for (i in idx(a, b)) {
            val tt = time(i) - a
            val v = amp * exp(-tt / tau) * sin(2 * PI * freqHz * tt)
            for (k in 0..2) dynW[k][i] += v * u[k]
        }
    }

    /** Centripetal reading of a tumbling phone (ω² r on carrier y). */
    fun centripetal(a: Double, b: Double, omega: Double, r: Double = 0.03) {
        for (i in idx(a, b)) extraP[1][i] += omega * omega * r / G0
    }

    /** Body at rest: tiny tremor plus breathing (research §5.1 `stillBody`). */
    fun stillBody(a: Double, b: Double) {
        noise(a, b, 0.003)
        sine(a, b, 0.25, 0.005, doubleArrayOf(0.0, 0.6, 0.8))
    }

    fun speed(t: Double, v: Double) {
        speeds.add(t to v)
    }

    /** 1 Hz speed fixes over [a, b) from [v], with optional Gaussian error (clamped at 0). */
    fun speeds(a: Double, b: Double, sigma: Double = 0.0, v: (Double) -> Double) {
        var t = a
        while (t < b) {
            val noisy = if (sigma > 0) max(0.0, v(t) + rng.nextGaussian() * sigma) else v(t)
            speeds.add(t to noisy)
            t += 1.0
        }
    }

    fun mode(t: Double, m: Mode) {
        modeChanges.add(t to m)
    }

    fun heightDrop(t: Double, metres: Double) {
        heightDrops.add(t to metres)
    }

    private fun attitude(t: Double, includeSwing: Boolean, swingAngle: Double = 0.0): DoubleArray {
        var r = r0
        for (s in segments) {
            val th = s.theta(t)
            if (th != 0.0) r = Rot3.mul(r, Rot3.axisAngle(s.axis, th))
        }
        if (includeSwing && swingAngle != 0.0) r = Rot3.mul(r, Rot3.axisAngle(Carry.X, swingAngle))
        return r
    }

    /**
     * Phone readings at 1 kHz in the sensor frame: accel (g, includes gravity) and gyro (rad/s). [yaw] rotates the
     * world-frame dynamics about the vertical (heading of the motion relative to the phone); [mount] is the
     * constant sensor → carrier rotation (how the phone sits in the pocket, hand or mount).
     */
    fun render(yaw: Double, mount: DoubleArray): RenderedTruth {
        val att = Array(n) { attitude(time(it), includeSwing = true, swingAngle = swing[it]) }
        val mT = Rot3.transpose(mount)
        val cy = cos(yaw)
        val sy = sin(yaw)
        val acc = Array(3) { DoubleArray(n) }
        val gyr = Array(3) { DoubleArray(n) }
        val dt = 1.0 / FS
        for (i in 0 until n) {
            val dx = dynW[0][i]
            val dy = dynW[1][i]
            val fx = cy * dx - sy * dy
            val fy = sy * dx + cy * dy
            val fz = dynW[2][i] + 1.0
            val r = att[i]
            // carrier frame: Rᵀ·f_w + extras
            val cx = r[0] * fx + r[3] * fy + r[6] * fz + extraP[0][i]
            val cyy = r[1] * fx + r[4] * fy + r[7] * fz + extraP[1][i]
            val cz = r[2] * fx + r[5] * fy + r[8] * fz + extraP[2][i]
            acc[0][i] = mT[0] * cx + mT[1] * cyy + mT[2] * cz
            acc[1][i] = mT[3] * cx + mT[4] * cyy + mT[5] * cz
            acc[2][i] = mT[6] * cx + mT[7] * cyy + mT[8] * cz
            // body rate: Ω = Rᵀ·dR/dt (skew), central differences
            val lo = att[max(0, i - 1)]
            val hi = att[min(n - 1, i + 1)]
            val span = (min(n - 1, i + 1) - max(0, i - 1)) * dt
            if (span > 0) {
                val d = DoubleArray(9) { (hi[it] - lo[it]) / span }
                val om = Rot3.mul(Rot3.transpose(r), d)
                val wx = 0.5 * (om[7] - om[5])
                val wy = 0.5 * (om[2] - om[6])
                val wz = 0.5 * (om[3] - om[1])
                gyr[0][i] = mT[0] * wx + mT[1] * wy + mT[2] * wz
                gyr[1][i] = mT[3] * wx + mT[4] * wy + mT[5] * wz
                gyr[2][i] = mT[6] * wx + mT[7] * wy + mT[8] * wz
            }
        }
        return RenderedTruth(n, acc, gyr, speeds.toList(), modeChanges.toList(), heightDrops.toList())
    }

    companion object {
        const val FS: Int = 1000
    }
}

/** Truth rendered into the sensor frame at 1 kHz, ready to be sampled at any rate and range. */
internal class RenderedTruth(
    val n: Int,
    val accelG: Array<DoubleArray>,
    val gyroRadS: Array<DoubleArray>,
    val speeds: List<Pair<Double, Double>>,
    val modeChanges: List<Pair<Double, Mode>>,
    val heightDrops: List<Pair<Double, Double>>,
) {
    val lastTimeS: Double get() = (n - 1).toDouble() / Truth.FS
    var eventS: Double = Double.NaN
}

/**
 * Sensor model (research §5.1 step 2): clip each axis at ±range, boxcar-average over one output period (the chip's
 * filter), add white noise, clip again; jittered timestamps with random drops and an optional gap.
 */
internal object SensorModel {
    const val GYRO_RANGE_RAD_S: Double = 34.9
    private const val GYRO_NOISE_RAD_S = 0.01
    private const val GYRO_TIME_OFFSET_S = 0.0004
    private const val PRESSURE_HZ = 10.0
    private const val PRESSURE_NOISE_HPA = 0.003
    private const val SEA_LEVEL_HPA = 1013.25
    /** hPa per metre of height near sea level (research §1.2). */
    const val HPA_PER_METRE: Double = 0.12

    fun timestamps(lastS: Double, rateHz: Int, jitterFraction: Double, dropProbability: Double, offsetS: Double, gap: ClosedFloatingPointRange<Double>?, rng: Random): DoubleArray {
        val count = floor(lastS * rateHz).toInt()
        val raw = DoubleArray(count) { it.toDouble() / rateHz + offsetS + rng.nextGaussian() * jitterFraction / rateHz }
        raw.sort()
        val out = ArrayList<Double>(count)
        for (t in raw) {
            val c = t.coerceIn(0.0, lastS)
            val dropped = rng.nextDouble() < dropProbability
            val inGap = gap != null && c >= gap.start && c < gap.endInclusive
            if (!dropped && !inGap) out.add(c)
        }
        return out.toDoubleArray()
    }

    /** Boxcar average of the clipped 1 kHz [signal] over the [periodS] before each timestamp. */
    fun boxcar(signal: DoubleArray, limit: Double, ts: DoubleArray, periodS: Double): DoubleArray {
        val n = signal.size
        val cum = DoubleArray(n)
        for (i in 1 until n) cum[i] = cum[i - 1] + signal[i - 1].coerceIn(-limit, limit) / Truth.FS
        fun at(t: Double): Double {
            val x = (t * Truth.FS).coerceIn(0.0, (n - 1).toDouble())
            val i = floor(x).toInt().coerceAtMost(n - 2)
            val f = x - i
            return cum[i] + (cum[i + 1] - cum[i]) * f
        }
        return DoubleArray(ts.size) {
            val hi = ts[it]
            val lo = max(hi - periodS, 0.0)
            (at(hi) - at(lo)) / max(hi - lo, 1e-9)
        }
    }

    fun accelSamples(r: RenderedTruth, ts: DoubleArray, rateHz: Int, rangeG: Double, noiseG: Double, rng: Random, startNanos: Long): List<SensorSample> {
        val axes = Array(3) { boxcar(r.accelG[it], rangeG, ts, 1.0 / rateHz) }
        val pinned = rangeG * (1 - 1e-6)
        return List(ts.size) { k ->
            // A saturated ADC outputs exactly full scale: no noise on a fully clipped averaging window.
            fun v(a: Int): Float {
                val x = axes[a][k]
                val g = if (kotlin.math.abs(x) >= pinned) kotlin.math.sign(x) * rangeG else (x + rng.nextGaussian() * noiseG).coerceIn(-rangeG, rangeG)
                return (g * G0).toFloat()
            }
            SensorSample(startNanos + Math.round(ts[k] * 1e9), SensorKind.ACCEL, v(0), v(1), v(2))
        }
    }

    fun gyroSamples(r: RenderedTruth, ts: DoubleArray, rateHz: Int, rng: Random, startNanos: Long): List<SensorSample> {
        val axes = Array(3) { boxcar(r.gyroRadS[it], GYRO_RANGE_RAD_S, ts, 1.0 / rateHz) }
        return List(ts.size) { k ->
            fun v(a: Int) = (axes[a][k] + rng.nextGaussian() * GYRO_NOISE_RAD_S).toFloat()
            SensorSample(startNanos + Math.round(ts[k] * 1e9), SensorKind.GYRO, v(0), v(1), v(2))
        }
    }

    fun gyroOffsetS(): Double = GYRO_TIME_OFFSET_S

    fun pressureSamples(r: RenderedTruth, rng: Random, startNanos: Long): List<SensorSample> {
        val out = ArrayList<SensorSample>()
        var k = 0
        while (k / PRESSURE_HZ < r.lastTimeS) {
            val t = k / PRESSURE_HZ
            var p = SEA_LEVEL_HPA + rng.nextGaussian() * PRESSURE_NOISE_HPA
            for ((ts, metres) in r.heightDrops) if (t >= ts) p += HPA_PER_METRE * metres
            out.add(SensorSample(startNanos + Math.round(t * 1e9), SensorKind.PRESSURE, p.toFloat(), 0f, 0f))
            k++
        }
        return out
    }
}
