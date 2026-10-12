package com.eldering.detection

import kotlin.math.abs
import kotlin.math.max

/**
 * Ring buffer of timestamped 3-axis samples on primitive arrays (no per-sample allocation). Samples are addressed by
 * a monotonically increasing sequence number; the buffer grows by doubling up to [maxCapacity] and then overwrites
 * the oldest sample. Timestamps must be appended in increasing order.
 */
internal class SampleRing(initialCapacity: Int, private val maxCapacity: Int) {
    private var capacity = Integer.highestOneBit(max(16, initialCapacity) - 1) shl 1
    private var ts = LongArray(capacity)
    private var xs = FloatArray(capacity)
    private var ys = FloatArray(capacity)
    private var zs = FloatArray(capacity)

    /** Sequence number of the oldest retained sample. */
    var firstSeq: Long = 0
        private set

    /** Sequence number one past the newest sample. */
    var endSeq: Long = 0
        private set

    val size: Int get() = (endSeq - firstSeq).toInt()

    fun isEmpty(): Boolean = endSeq == firstSeq

    private fun slot(seq: Long): Int = (seq and (capacity - 1).toLong()).toInt()

    fun add(t: Long, x: Float, y: Float, z: Float) {
        if (size == capacity) {
            if (capacity < maxCapacity) grow() else firstSeq++
        }
        val i = slot(endSeq)
        ts[i] = t
        xs[i] = x
        ys[i] = y
        zs[i] = z
        endSeq++
    }

    private fun grow() {
        val newCap = capacity * 2
        val nt = LongArray(newCap)
        val nx = FloatArray(newCap)
        val ny = FloatArray(newCap)
        val nz = FloatArray(newCap)
        for (seq in firstSeq until endSeq) {
            val from = slot(seq)
            val to = (seq and (newCap - 1).toLong()).toInt()
            nt[to] = ts[from]
            nx[to] = xs[from]
            ny[to] = ys[from]
            nz[to] = zs[from]
        }
        capacity = newCap
        ts = nt
        xs = nx
        ys = ny
        zs = nz
    }

    fun t(seq: Long): Long = ts[slot(seq)]
    fun x(seq: Long): Float = xs[slot(seq)]
    fun y(seq: Long): Float = ys[slot(seq)]
    fun z(seq: Long): Float = zs[slot(seq)]

    fun lastT(): Long = ts[slot(endSeq - 1)]

    fun firstT(): Long = ts[slot(firstSeq)]

    /** Drops samples with timestamps before [tMin]. */
    fun evictBefore(tMin: Long) {
        while (endSeq > firstSeq && ts[slot(firstSeq)] < tMin) firstSeq++
    }

    /** First sequence number with t >= [t] (or [endSeq]). */
    fun lowerBound(t: Long): Long {
        var lo = firstSeq
        var hi = endSeq
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (ts[slot(mid)] < t) lo = mid + 1 else hi = mid
        }
        return lo
    }

    /** First sequence number with t > [t] (or [endSeq]). */
    fun upperBound(t: Long): Long {
        var lo = firstSeq
        var hi = endSeq
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (ts[slot(mid)] <= t) lo = mid + 1 else hi = mid
        }
        return lo
    }
}

/** Mode reports sorted by timestamp, so lookups work even when sensor batches arrive after the mode change. */
internal class ModeHistory {
    private val times = ArrayList<Long>()
    private val modes = ArrayList<Mode>()

    val size: Int get() = times.size

    fun add(t: Long, mode: Mode) {
        var i = times.size
        while (i > 0 && times[i - 1] > t) i--
        times.add(i, t)
        modes.add(i, mode)
    }

    /** Mode in effect at [t] (the last report at or before it); UNKNOWN before the first report. */
    fun at(t: Long): Mode {
        val i = lastIndexAtOrBefore(t)
        return if (i < 0) Mode.UNKNOWN else modes[i]
    }

    /** True if [mode] was in effect at any time in [from, to]. */
    fun wasInEffect(mode: Mode, from: Long, to: Long): Boolean {
        if (at(from) == mode) return true
        for (i in times.indices) if (times[i] in (from + 1)..to && modes[i] == mode) return true
        return false
    }

    /** Forgets reports older than [t], keeping the one in effect at [t]. */
    fun pruneBefore(t: Long) {
        val keepFrom = lastIndexAtOrBefore(t)
        if (keepFrom > 0) {
            times.subList(0, keepFrom).clear()
            modes.subList(0, keepFrom).clear()
        }
    }

    private fun lastIndexAtOrBefore(t: Long): Int {
        var lo = 0
        var hi = times.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (times[mid] <= t) lo = mid + 1 else hi = mid
        }
        return lo - 1
    }
}

/** GPS speed fixes sorted by timestamp. */
internal class SpeedHistory {
    private val times = ArrayList<Long>()
    private val speeds = ArrayList<Float>()

    val size: Int get() = times.size

    fun add(t: Long, v: Float) {
        var i = times.size
        while (i > 0 && times[i - 1] > t) i--
        times.add(i, t)
        speeds.add(i, v)
    }

    /** True if any fix in [from, to] is at least [v]. */
    fun anyAtLeast(v: Float, from: Long, to: Long): Boolean {
        for (i in times.indices) if (times[i] in from..to && speeds[i] >= v) return true
        return false
    }

    /** Fixes in [from, to] as (seconds relative to [origin], m/s) arrays. */
    fun window(from: Long, to: Long, origin: Long): Pair<DoubleArray, DoubleArray> {
        val idx = times.indices.filter { times[it] in from..to }
        return DoubleArray(idx.size) { (times[idx[it]] - origin) / 1e9 } to DoubleArray(idx.size) { speeds[idx[it]].toDouble() }
    }

    fun pruneBefore(t: Long) {
        var n = 0
        while (n < times.size && times[n] < t) n++
        if (n > 0) {
            times.subList(0, n).clear()
            speeds.subList(0, n).clear()
        }
    }
}

/**
 * Effective accelerometer full-scale range (research §4.1): the value from `Sensor.getMaximumRange()`, raised to the
 * next standard bin if a reading exceeds it, and lowered to an observed clip plateau (several consecutive identical
 * |axis| readings ≥ 1.9 g near a standard bin) when the sensor clips below its reported range.
 */
internal class RangeTracker(private val cfg: DetectorConfig) {
    /** Range given by the app, in g (null until set). */
    var configuredG: Double? = null
        private set

    var rangeG: Double = cfg.defaultAccelRangeG.toDouble()
        private set

    private val lastAbs = FloatArray(3)
    private val repeats = IntArray(3)

    fun configure(rangeMs2: Float) {
        configuredG = rangeMs2 / G0
        rangeG = rangeMs2 / G0
        repeats.fill(0)
    }

    /** Observes one raw reading in m/s². */
    fun observe(x: Float, y: Float, z: Float) {
        observeAxis(0, x)
        observeAxis(1, y)
        observeAxis(2, z)
    }

    private fun observeAxis(axis: Int, v: Float) {
        val a = abs(v)
        val g = a / G0
        if (g > rangeG * 1.02) {
            rangeG = STANDARD_RANGES_G.firstOrNull { it >= g } ?: (g * 1.05)
            repeats.fill(0)
        }
        if (g >= cfg.plateauMinG && a == lastAbs[axis]) repeats[axis]++ else repeats[axis] = 1
        lastAbs[axis] = a
        if (repeats[axis] >= cfg.plateauSamples && g < cfg.saturationFraction * rangeG && nearStandardBin(g)) {
            rangeG = g
        }
    }

    private fun nearStandardBin(g: Double): Boolean = STANDARD_RANGES_G.any { abs(g - it) <= 0.1 * it }

    private companion object {
        val STANDARD_RANGES_G = doubleArrayOf(2.0, 4.0, 8.0, 16.0, 32.0, 64.0)
    }
}

/** Running median of the last N sample spacings (research §4.2: the requested rate is only a hint). */
internal class RateEstimator(private val window: Int) {
    private val deltas = LongArray(window)
    private var count = 0
    private var next = 0
    private var sinceRecompute = 0
    private var cachedPeriodNs = 0L
    private var lastT = Long.MIN_VALUE

    fun add(t: Long) {
        if (lastT != Long.MIN_VALUE) {
            deltas[next] = t - lastT
            next = (next + 1) % window
            if (count < window) count++
            sinceRecompute++
        }
        lastT = t
    }

    /** Median period in seconds, or NaN before two samples were seen. */
    fun periodS(): Double {
        if (count == 0) return Double.NaN
        if (cachedPeriodNs == 0L || sinceRecompute >= max(1, window / 4)) {
            val s = deltas.copyOf(count)
            s.sort()
            cachedPeriodNs = s[count / 2]
            sinceRecompute = 0
        }
        return cachedPeriodNs / 1e9
    }
}
