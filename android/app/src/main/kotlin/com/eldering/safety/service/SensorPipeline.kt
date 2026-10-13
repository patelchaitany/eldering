package com.eldering.safety.service

import android.annotation.SuppressLint
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener2
import android.hardware.SensorManager
import android.os.Handler
import android.os.PowerManager
import com.eldering.detection.SensorKind
import com.eldering.detection.SensorSample
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

/**
 * Registers accelerometer, gyroscope and (optional) barometer listeners per [SensingConfig] and turns their
 * events into [SensorSample]s on [handler]'s thread (the detector thread).
 *
 * Batched (non-wake-up) sensors only deliver when the CPU is awake; [drainNow] flushes the FIFOs under a short
 * wakelock and is called on every location callback (docs/research/android-background.md §4.3).
 */
class SensorPipeline(
    private val sm: SensorManager,
    pm: PowerManager,
    private val handler: Handler,
    private val onSample: (SensorSample) -> Unit,
) : SensorEventListener2 {

    private val drainLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "eldering:SensorDrain").apply { setReferenceCounted(false) }
    private val accel: Sensor? = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val gyro: Sensor? = sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private val pressure: Sensor? = sm.getDefaultSensor(Sensor.TYPE_PRESSURE)

    val hasAccel: Boolean get() = accel != null
    val hasGyro: Boolean get() = gyro != null
    val hasPressure: Boolean get() = pressure != null

    /** Accelerometer full-scale range in m/s² (`Sensor.getMaximumRange()`), 0 if unknown. */
    val accelMaxRange: Float get() = accel?.maximumRange ?: 0f

    @Volatile
    var config: SensingConfig? = null
        private set

    private val counts = mapOf(
        SensorKind.ACCEL to AtomicLong(),
        SensorKind.GYRO to AtomicLong(),
        SensorKind.PRESSURE to AtomicLong(),
    )
    private val meters = mapOf(SensorKind.ACCEL to RateMeter(), SensorKind.GYRO to RateMeter(), SensorKind.PRESSURE to RateMeter())
    private val flushes = AtomicLong()
    private var registered = 0
    private var pendingFlushes = 0

    /** Last ~30 s of accelerometer and gyroscope samples, for the false-alarm event window. Detector thread only. */
    val recent = RecentSamples(capacityPerKind = 6_000)

    /** Applies [cfg] (unregister, then register with the new rates). Call on the handler thread. */
    fun apply(cfg: SensingConfig) {
        if (cfg == config) return
        sm.unregisterListener(this)
        registered = 0
        accel?.let { if (sm.registerListener(this, it, cfg.accelPeriodUs, cfg.maxLatencyUs, handler)) registered++ }
        if (cfg.gyroPeriodUs > 0) gyro?.let { if (sm.registerListener(this, it, cfg.gyroPeriodUs, cfg.maxLatencyUs, handler)) registered++ }
        if (cfg.pressurePeriodUs > 0) pressure?.let { if (sm.registerListener(this, it, cfg.pressurePeriodUs, cfg.maxLatencyUs, handler)) registered++ }
        meters.values.forEach(RateMeter::reset)
        config = cfg
    }

    /**
     * Delivers what the sensor FIFOs hold now (location callback, possible accident). Call on the handler thread.
     * At most one drain is in flight; its wakelock is released by [onFlushCompleted] or after [DRAIN_TIMEOUT_MS].
     */
    @SuppressLint("WakelockTimeout") // bounded by releaseDrain, posted on this thread right away
    fun drainNow() {
        if (registered == 0 || pendingFlushes > 0) return
        // acquire(timeout) would post its release to the main looper from this thread; under load that contends
        // with input dispatch (seen as an ANR on the emulator). Keep the timeout on our own thread instead.
        drainLock.acquire()
        handler.postDelayed(releaseDrain, DRAIN_TIMEOUT_MS)
        pendingFlushes = registered
        flushes.incrementAndGet()
        if (!sm.flush(this)) finishDrain()
    }

    private val releaseDrain: Runnable = Runnable { finishDrain() }

    private fun finishDrain() {
        handler.removeCallbacks(releaseDrain)
        pendingFlushes = 0
        if (drainLock.isHeld) drainLock.release()
    }

    override fun onFlushCompleted(sensor: Sensor) {
        if (--pendingFlushes <= 0) finishDrain()
    }

    override fun onSensorChanged(e: SensorEvent) {
        val kind = when (e.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> SensorKind.ACCEL
            Sensor.TYPE_GYROSCOPE -> SensorKind.GYRO
            Sensor.TYPE_PRESSURE -> SensorKind.PRESSURE
            else -> return
        }
        counts.getValue(kind).incrementAndGet()
        meters.getValue(kind).add(e.timestamp)
        // e.timestamp is on the elapsedRealtimeNanos() base, like Location.elapsedRealtimeNanos.
        val sample = if (kind == SensorKind.PRESSURE) {
            SensorSample(e.timestamp, kind, e.values[0], 0f, 0f)
        } else {
            SensorSample(e.timestamp, kind, e.values[0], e.values[1], e.values[2])
        }
        onSample(sample)
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit

    fun stop() {
        sm.unregisterListener(this)
        registered = 0
        config = null
        finishDrain()
    }

    fun samplesReceived(kind: SensorKind): Long = counts.getValue(kind).get()

    /** Measured delivery rate in Hz over the last samples (NaN until enough arrived). */
    fun measuredHz(kind: SensorKind): Double = meters.getValue(kind).hz

    val flushCount: Long get() = flushes.get()

    fun describeSensors(): String = listOfNotNull(
        accel?.let { "accel=${it.name} range=${fmt(it.maximumRange)}m/s² fifo=${it.fifoMaxEventCount}/${it.fifoReservedEventCount} wake=${it.isWakeUpSensor}" },
        gyro?.let { "gyro=${it.name} fifo=${it.fifoMaxEventCount}" },
        pressure?.let { "pressure=${it.name}" } ?: "pressure=absent",
    ).joinToString(", ")

    private fun fmt(v: Float) = String.format(Locale.ROOT, "%.1f", v)

    /** Rate over the last [WINDOW] timestamps. Written on the sensor thread, read from anywhere. */
    private class RateMeter {
        private val ts = LongArray(WINDOW)
        private var n = 0
        private var i = 0

        @Volatile
        var hz: Double = Double.NaN
            private set

        fun add(t: Long) {
            ts[i] = t
            i = (i + 1) % WINDOW
            if (n < WINDOW) n++
            if (n >= 8 && i % 8 == 0) {
                val newest = ts[(i - 1 + WINDOW) % WINDOW]
                val oldest = ts[(i - n + WINDOW) % WINDOW]
                val span = newest - oldest
                hz = if (span > 0) (n - 1) * 1e9 / span else Double.NaN
            }
        }

        fun reset() {
            n = 0
            i = 0
            hz = Double.NaN
        }

        companion object {
            const val WINDOW = 64
        }
    }

    companion object {
        const val DRAIN_TIMEOUT_MS = 3_000L
    }
}

/**
 * Ring buffer of recent accelerometer and gyroscope samples (primitive arrays, no allocation per sample).
 * Not thread-safe: use it on the detector thread only.
 */
class RecentSamples(private val capacityPerKind: Int) {
    private class Ring(cap: Int) {
        val t = LongArray(cap)
        val x = FloatArray(cap)
        val y = FloatArray(cap)
        val z = FloatArray(cap)
        var size = 0
        var head = 0 // next write index

        fun add(s: SensorSample) {
            t[head] = s.tNanos
            x[head] = s.x
            y[head] = s.y
            z[head] = s.z
            head = (head + 1) % t.size
            if (size < t.size) size++
        }

        inline fun forEachIn(from: Long, to: Long, action: (Long, Float, Float, Float) -> Unit) {
            val start = (head - size + t.size) % t.size
            for (k in 0 until size) {
                val idx = (start + k) % t.size
                val ts = t[idx]
                if (ts in from..to) action(ts, x[idx], y[idx], z[idx])
            }
        }
    }

    private val accel = Ring(capacityPerKind)
    private val gyro = Ring(capacityPerKind)

    fun add(s: SensorSample) {
        when (s.kind) {
            SensorKind.ACCEL -> accel.add(s)
            SensorKind.GYRO -> gyro.add(s)
            SensorKind.PRESSURE -> Unit
        }
    }

    /**
     * The samples in [fromNanos]..[toNanos] as compact JSON for `DetectionEventRecord.windowJson`:
     * `{"t0_ns":…,"accel":[[dt_ms,x,y,z],…],"gyro":[…]}` (dt relative to [t0Nanos]; m/s² and rad/s, 3 decimals),
     * each list thinned to at most [maxPerKind] samples.
     */
    fun windowJson(t0Nanos: Long, fromNanos: Long, toNanos: Long, maxPerKind: Int = 1_500): String {
        fun encode(ring: Ring): String {
            var count = 0
            ring.forEachIn(fromNanos, toNanos) { _, _, _, _ -> count++ }
            val stride = maxOf(1, (count + maxPerKind - 1) / maxPerKind)
            val sb = StringBuilder(count / stride * 32 + 2).append('[')
            var k = 0
            var first = true
            ring.forEachIn(fromNanos, toNanos) { t, x, y, z ->
                if (k++ % stride == 0) {
                    if (!first) sb.append(',')
                    first = false
                    sb.append('[').append((t - t0Nanos) / 1_000_000L).append(',')
                        .append(r3(x)).append(',').append(r3(y)).append(',').append(r3(z)).append(']')
                }
            }
            return sb.append(']').toString()
        }
        return """{"t0_ns":$t0Nanos,"accel":${encode(accel)},"gyro":${encode(gyro)}}"""
    }

    private fun r3(v: Float): String = if (v.isFinite()) String.format(Locale.ROOT, "%.3f", v) else "0"
}
