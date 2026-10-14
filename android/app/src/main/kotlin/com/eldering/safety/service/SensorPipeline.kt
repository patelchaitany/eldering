package com.eldering.safety.service

import android.annotation.SuppressLint
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener2
import android.hardware.SensorManager
import android.hardware.TriggerEvent
import android.hardware.TriggerEventListener
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
 * wakelock and is called on location callbacks, alarms and significant motion (docs/research/android-background.md
 * §4.3).
 *
 * Reconfiguration is **flush-then-swap**: [apply] first flushes the current registration and only unregisters it
 * once every sensor reported `onFlushCompleted` (or after [DRAIN_TIMEOUT_MS]). Unregistering straight away would
 * drop whatever the FIFOs and the event socket still hold — at a tier change that is exactly the post-impact data
 * of the candidate that caused it.
 *
 * In the STILL tier the one-shot significant-motion sensor (a wake-up sensor) is armed and re-armed: it drains the
 * FIFO when the phone starts moving and tells [onSignificantMotion]. Where the phone has a wake-up accelerometer,
 * STILL uses it with a report latency of half its FIFO depth, so the FIFO wakes the CPU before it wraps.
 */
class SensorPipeline(
    private val sm: SensorManager,
    pm: PowerManager,
    private val handler: Handler,
    private val onSample: (SensorSample) -> Unit,
    /** Significant motion fired (detector thread). */
    private val onSignificantMotion: () -> Unit = {},
) : SensorEventListener2 {

    private val drainLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "eldering:SensorDrain").apply { setReferenceCounted(false) }
    private val accel: Sensor? = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val accelWakeUp: Sensor? = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER, true)
        ?.takeIf { it.isWakeUpSensor && it.fifoMaxEventCount > 0 && it !== accel }
    private val gyro: Sensor? = sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private val pressure: Sensor? = sm.getDefaultSensor(Sensor.TYPE_PRESSURE)
    private val sigMotion: Sensor? = sm.getDefaultSensor(Sensor.TYPE_SIGNIFICANT_MOTION)

    val hasAccel: Boolean get() = accel != null
    val hasGyro: Boolean get() = gyro != null
    val hasPressure: Boolean get() = pressure != null
    val hasSignificantMotion: Boolean get() = sigMotion != null

    /** STILL registers a wake-up accelerometer (its FIFO wakes the CPU before it wraps: no drain alarm needed). */
    val hasWakeUpAccel: Boolean get() = accelWakeUp != null

    /** Accelerometer full-scale range in m/s² (`Sensor.getMaximumRange()`), 0 if unknown. */
    val accelMaxRange: Float get() = accel?.maximumRange ?: 0f

    /**
     * How often the STILL tier needs a drain when nothing else wakes the CPU (half the accelerometer FIFO depth at
     * 25 Hz; see [SensingPlans.stillDrainIntervalMs]).
     */
    val stillDrainIntervalMs: Long = SensingPlans.stillDrainIntervalMs(fifoEvents(accel))

    /** The registered configuration (the previous one until a pending swap completes). */
    @Volatile
    var config: SensingConfig? = null
        private set

    /** STILL is registered on a wake-up accelerometer (its FIFO wakes the CPU: no drain alarm needed). */
    @Volatile
    var usesWakeUpAccel: Boolean = false
        private set

    @Volatile
    var significantMotionArmed: Boolean = false
        private set

    private val counts = mapOf(
        SensorKind.ACCEL to AtomicLong(),
        SensorKind.GYRO to AtomicLong(),
        SensorKind.PRESSURE to AtomicLong(),
    )
    private val meters = mapOf(SensorKind.ACCEL to RateMeter(), SensorKind.GYRO to RateMeter(), SensorKind.PRESSURE to RateMeter())
    private val flushes = AtomicLong()
    private val swaps = AtomicLong()
    private val sigMotionTriggers = AtomicLong()
    private var registered = 0

    // Flush bookkeeping (handler thread only).
    private var flushOutstanding = 0
    private var followUpFlush = false
    private var pendingConfig: SensingConfig? = null

    /** Last ~30 s of accelerometer and gyroscope samples, for the false-alarm event window. Detector thread only. */
    val recent = RecentSamples(capacityPerKind = 6_000)

    private val sigListener = object : TriggerEventListener() {
        override fun onTrigger(event: TriggerEvent) {
            // Delivered on the main looper. Hold the CPU until the detector thread has started the drain.
            drainLock.acquire(DRAIN_TIMEOUT_MS)
            handler.post { onSignificantMotionTriggered() }
        }
    }

    /**
     * Applies [cfg]. With nothing registered it takes effect at once; otherwise the current registration is
     * flushed first and swapped when the flush completed (see the class comment). Call on the handler thread.
     */
    fun apply(cfg: SensingConfig) {
        if (cfg == (pendingConfig ?: config)) return
        if (pendingConfig != null && cfg == config) {
            pendingConfig = null // back to what is registered: nothing to swap
            followUpFlush = false
            return
        }
        if (registered == 0) {
            pendingConfig = null
            swap(cfg)
            return
        }
        pendingConfig = cfg
        if (flushOutstanding > 0) {
            // The flush in flight only covers events up to its own request: flush once more before swapping.
            followUpFlush = true
        } else {
            startFlush()
        }
    }

    /**
     * Delivers what the sensor FIFOs hold now (location callback, alarm, significant motion, possible accident).
     * Call on the handler thread. At most one flush is in flight; its wakelock is released when every registered
     * sensor reported [onFlushCompleted], or after [DRAIN_TIMEOUT_MS].
     */
    fun drainNow() {
        if (registered == 0) {
            if (drainLock.isHeld) drainLock.release()
            return
        }
        if (flushOutstanding > 0) return // the flush in flight delivers the FIFO contents too
        startFlush()
    }

    @SuppressLint("WakelockTimeout") // bounded by flushTimeout, posted on this thread right away
    private fun startFlush() {
        // acquire(timeout) would post its release to the main looper from this thread; under load that contends
        // with input dispatch (seen as an ANR on the emulator). Keep the timeout on our own thread instead.
        drainLock.acquire()
        handler.removeCallbacks(flushTimeout)
        handler.postDelayed(flushTimeout, DRAIN_TIMEOUT_MS)
        flushOutstanding = registered
        flushes.incrementAndGet()
        if (!sm.flush(this)) onFlushDone()
    }

    private val flushTimeout: Runnable = Runnable { onFlushDone() }

    override fun onFlushCompleted(sensor: Sensor) {
        if (flushOutstanding <= 0) return // late completion of a registration that was already swapped out
        if (--flushOutstanding == 0) onFlushDone()
    }

    /** Everything up to the flush request was delivered: swap a pending config, then let the CPU sleep. */
    private fun onFlushDone() {
        handler.removeCallbacks(flushTimeout)
        flushOutstanding = 0
        if (followUpFlush && registered > 0) {
            followUpFlush = false
            startFlush()
            return
        }
        followUpFlush = false
        pendingConfig?.let { next ->
            pendingConfig = null
            swap(next)
        }
        if (drainLock.isHeld) drainLock.release()
    }

    /** Unregisters and registers with [cfg] (the old registration was flushed, or there was none). */
    private fun swap(cfg: SensingConfig) {
        sm.unregisterListener(this)
        registered = 0
        val still = cfg.tier == SensingConfig.Tier.STILL
        val wakeAccel = if (still) accelWakeUp else null
        val accelSensor = wakeAccel ?: accel
        val accelLatencyUs = if (wakeAccel != null) wakeUpLatencyUs(wakeAccel, cfg) else cfg.maxLatencyUs
        accelSensor?.let { if (sm.registerListener(this, it, cfg.accelPeriodUs, accelLatencyUs, handler)) registered++ }
        if (cfg.gyroPeriodUs > 0) gyro?.let { if (sm.registerListener(this, it, cfg.gyroPeriodUs, cfg.maxLatencyUs, handler)) registered++ }
        if (cfg.pressurePeriodUs > 0) pressure?.let { if (sm.registerListener(this, it, cfg.pressurePeriodUs, cfg.maxLatencyUs, handler)) registered++ }
        usesWakeUpAccel = wakeAccel != null && registered > 0
        armSignificantMotion(still)
        meters.values.forEach(RateMeter::reset)
        swaps.incrementAndGet()
        config = cfg
    }

    /** Half the wake-up FIFO's depth (never less than the tier's own latency). */
    private fun wakeUpLatencyUs(sensor: Sensor, cfg: SensingConfig): Int {
        val ms = SensingPlans.stillDrainIntervalMs(fifoEvents(sensor), cfg.accelHz)
        return maxOf(cfg.maxLatencyUs.toLong(), ms * 1_000L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    private fun armSignificantMotion(armed: Boolean) {
        val sensor = sigMotion ?: return
        if (armed) {
            if (!significantMotionArmed) significantMotionArmed = sm.requestTriggerSensor(sigListener, sensor)
        } else if (significantMotionArmed) {
            sm.cancelTriggerSensor(sigListener, sensor)
            significantMotionArmed = false
        }
    }

    private fun onSignificantMotionTriggered() {
        sigMotionTriggers.incrementAndGet()
        significantMotionArmed = false // one-shot
        if (config?.tier == SensingConfig.Tier.STILL && pendingConfig == null) armSignificantMotion(true)
        drainNow()
        onSignificantMotion()
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
        armSignificantMotion(false)
        registered = 0
        config = null
        pendingConfig = null
        followUpFlush = false
        onFlushDone()
    }

    fun samplesReceived(kind: SensorKind): Long = counts.getValue(kind).get()

    /** Measured delivery rate in Hz over the last samples (NaN until enough arrived). */
    fun measuredHz(kind: SensorKind): Double = meters.getValue(kind).hz

    val flushCount: Long get() = flushes.get()

    fun describeSensors(): String = listOfNotNull(
        accel?.let { "accel=${it.name} range=${fmt(it.maximumRange)}m/s² fifo=${it.fifoMaxEventCount}/${it.fifoReservedEventCount} wake=${it.isWakeUpSensor}" },
        "accelWakeUp=${accelWakeUp?.let { "${it.name} fifo=${it.fifoMaxEventCount}" } ?: "absent"}",
        gyro?.let { "gyro=${it.name} fifo=${it.fifoMaxEventCount}" },
        pressure?.let { "pressure=${it.name}" } ?: "pressure=absent",
        "sigMotion=${if (sigMotion == null) "absent" else if (significantMotionArmed) "armed" else "idle"} triggers=${sigMotionTriggers.get()}",
        "stillDrain=${stillDrainIntervalMs / 1000}s swaps=${swaps.get()}",
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

        /** FIFO slots reserved for [sensor] (else its share of the shared FIFO); 0 if it does not batch. */
        private fun fifoEvents(sensor: Sensor?): Int = sensor?.let { if (it.fifoReservedEventCount > 0) it.fifoReservedEventCount else it.fifoMaxEventCount } ?: 0
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
