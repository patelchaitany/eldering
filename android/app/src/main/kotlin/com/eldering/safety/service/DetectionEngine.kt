package com.eldering.safety.service

import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.util.Log
import com.eldering.detection.AccidentDetector
import com.eldering.detection.CandidateOutcome
import com.eldering.detection.DetectionEvent
import com.eldering.detection.DetectorConfig
import com.eldering.detection.Mode
import com.eldering.detection.SensorKind
import com.eldering.detection.SensorSample
import com.eldering.detection.SyntheticTrace
import com.eldering.detection.SyntheticTraces
import com.eldering.detection.TraceSpec
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.android.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The live detection pipeline: one [AccidentDetector] fed by [SensorPipeline] on a dedicated thread (the detector
 * is not thread-safe, so every call into it is made on [handler]'s thread).
 *
 * [onEvent], [onPossibleAccident], [onCandidatesSettled], [onSignificantMotion] and [onStillness] are invoked on
 * that thread; hand work off quickly.
 *
 * Debug injection ([inject]) replays a `SyntheticTraces` scenario **through this live detector**: for the
 * duration of the replay the trace replaces the real sensor stream (real samples are counted and dropped), its
 * timestamps are mapped onto the current `elapsedRealtimeNanos` clock and, at the default pace 1.0, delivered in
 * real time in 50 ms batches — so the event is detected, timed and alerted exactly like a real one. Afterwards the
 * live movement mode and the sensor's range are re-applied.
 */
class DetectionEngine(
    sensorManager: SensorManager,
    powerManager: PowerManager,
    private val detectorConfig: DetectorConfig = DetectorConfig(),
    private val verboseCandidates: Boolean = false,
    private val onEvent: (event: DetectionEvent, mode: Mode, windowJson: String) -> Unit,
    /** The cheap trigger found a candidate peaking at [tNanos] (sensor time). */
    private val onPossibleAccident: (tNanos: Long) -> Unit,
    /**
     * A verdict was reported and no candidate is waiting any more (end a possible-accident hold early).
     * [emitted]: one of those verdicts was an accident (the alert takes over the high rate).
     */
    private val onCandidatesSettled: (emitted: Boolean) -> Unit = {},
    /** The significant-motion sensor fired (STILL tier). */
    private val onSignificantMotion: () -> Unit = {},
    /** Accelerometer-only stillness changed (see [StillnessMonitor]; used when Activity Recognition is missing). */
    private val onStillness: (still: Boolean) -> Unit = {},
) {
    private val thread = HandlerThread("eldering-detector", Process.THREAD_PRIORITY_DEFAULT).apply { start() }
    val handler: Handler = Handler(thread.looper)
    private val scope = CoroutineScope(SupervisorJob() + handler.asCoroutineDispatcher("eldering-detector"))

    val pipeline = SensorPipeline(sensorManager, powerManager, handler, ::onLiveSample, onSignificantMotion = ::onMotion)

    private val stillness = StillnessMonitor(stillStdG = detectorConfig.stillStdG.toDouble())
    private var verdictSeen = false
    private var emittedSinceSettle = false

    private val detector = AccidentDetector(detectorConfig) { event -> emit(event) }.also { d ->
        d.candidateObserver = ::onCandidate
        d.possibleAccidentListener = { t -> onPossibleAccident(t) }
    }

    @Volatile
    var mode: Mode = Mode.UNKNOWN
        private set

    private var lastAccelT = Long.MIN_VALUE
    private val candidates = AtomicLong()
    private val events = AtomicLong()
    private val gated = AtomicLong()
    private val injectedSamples = AtomicLong()

    @Volatile
    var lastCandidate: String? = null
        private set

    @Volatile
    var lastEvent: String? = null
        private set

    @Volatile
    var injection: InjectionStatus? = null
        private set

    private var injectJob: Job? = null
    private var gateUntilNanos = Long.MIN_VALUE

    data class InjectRequest(
        val scenario: String,
        /** Report this mode instead of the trace's own mode timeline. */
        val mode: Mode? = null,
        /** Report this GPS speed (m/s) once per second instead of the trace's speed fixes. */
        val speedMps: Float? = null,
        /** Replay speed: 1 = real time (default), 2 = twice as fast, 0 = all at once. */
        val pace: Double = 1.0,
        val seed: Long = 0L,
    )

    data class InjectionStatus(
        val scenario: String,
        val state: String,
        val startedAtElapsedMs: Long,
        val durationMs: Long,
        val samples: Int,
    )

    /** Starts sensing with [cfg] in [initialMode]. */
    fun start(cfg: SensingConfig, initialMode: Mode) = post {
        val range = pipeline.accelMaxRange
        if (range > 0f) detector.setAccelMaxRange(range)
        setModeLocked(initialMode)
        pipeline.apply(cfg)
    }

    fun applySensing(cfg: SensingConfig) = post { pipeline.apply(cfg) }

    fun setMode(newMode: Mode) = post { if (newMode != mode) setModeLocked(newMode) }

    private fun setModeLocked(newMode: Mode) {
        mode = newMode
        // While a trace is being replayed its own timeline owns the detector; the live mode is re-applied after.
        if (injectJob?.isActive != true) detector.onMode(newMode, SystemClock.elapsedRealtimeNanos())
    }

    /** A GPS speed at fix time [elapsedNanos]. */
    fun onSpeed(speedMps: Float, elapsedNanos: Long) = post {
        if (injectJob?.isActive != true) detector.onSpeed(speedMps, elapsedNanos)
    }

    fun drainNow() = post { pipeline.drainNow() }

    fun inject(request: InjectRequest) = post {
        injectJob?.cancel()
        val trace = try {
            SyntheticTraces.generate(request.scenario, TraceSpec(seed = request.seed))
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "DEBUG_INJECT: unknown scenario '${request.scenario}'. Known: ${SyntheticTraces.ids.joinToString()}")
            return@post
        }
        injectJob = scope.launch { replay(trace, request) }
    }

    fun stop() {
        handler.post {
            injectJob?.cancel()
            pipeline.stop()
            scope.cancel()
            thread.quitSafely()
        }
    }

    // ------------------------------------------------------------------ live stream

    private fun onLiveSample(s: SensorSample) {
        if (s.kind == SensorKind.ACCEL && stillness.onAccel(s.tNanos, s.x, s.y, s.z)) onStillness(stillness.isStill)
        if (SystemClock.elapsedRealtimeNanos() < gateUntilNanos || injectJob?.isActive == true) {
            gated.incrementAndGet()
            return
        }
        feed(s)
    }

    private fun feed(s: SensorSample) {
        pipeline.recent.add(s)
        if (s.kind == SensorKind.ACCEL) lastAccelT = max(lastAccelT, s.tNanos)
        detector.onSample(s)
        if (verdictSeen) {
            verdictSeen = false
            if (detector.pendingCandidates == 0) {
                onCandidatesSettled(emittedSinceSettle)
                emittedSinceSettle = false
            }
        }
    }

    private fun onMotion() {
        val wasStill = stillness.isStill
        stillness.reset()
        if (wasStill) onStillness(false)
        onSignificantMotion()
    }

    /** Accelerometer-only stillness right now (detector thread state, read anywhere for status). */
    val sensorStill: Boolean get() = stillness.isStill

    private fun emit(event: DetectionEvent) {
        emittedSinceSettle = true
        events.incrementAndGet()
        lastEvent = "${event.kind} conf=${"%.2f".format(event.confidence)} reason=${event.reason}"
        Log.i(TAG, "Detection: $lastEvent")
        val window = pipeline.recent.windowJson(
            t0Nanos = event.triggeredAtNanos,
            fromNanos = event.triggeredAtNanos - WINDOW_BEFORE_NS,
            toNanos = event.triggeredAtNanos + WINDOW_AFTER_NS,
        )
        onEvent(event, mode, window)
    }

    private fun onCandidate(c: CandidateOutcome) {
        verdictSeen = true
        candidates.incrementAndGet()
        lastCandidate = "mode=${c.mode} kind=${c.kind ?: "-"} verdict=${c.verdict}"
        if (verboseCandidates) Log.i(TAG, "Candidate: $lastCandidate conf=${"%.2f".format(c.confidence)}")
    }

    // ------------------------------------------------------------------ debug injection

    private suspend fun replay(trace: SyntheticTrace, req: InjectRequest) {
        val nowNs = SystemClock.elapsedRealtimeNanos()
        val base = max(nowNs, if (lastAccelT == Long.MIN_VALUE) nowNs else lastAccelT + 1_000_000L)
        val shift = base - trace.startNanos
        val durationNs = trace.endNanos - trace.startNanos
        val pace = req.pace.takeIf { it.isFinite() && it >= 0.0 } ?: 1.0

        // Timeline of everything the trace delivers, shifted onto the live clock.
        val timeline = ArrayList<Pair<Long, () -> Unit>>(trace.samples.size + 64)
        if (req.mode != null) {
            timeline.add(base to { detector.onMode(req.mode, base) })
        } else {
            trace.modeChanges.forEach { m -> timeline.add(m.tNanos + shift to { detector.onMode(m.mode, m.tNanos + shift) }) }
        }
        if (req.speedMps != null) {
            var t = 0L
            while (t <= durationNs) {
                val at = base + t
                timeline.add(at to { detector.onSpeed(req.speedMps, at) })
                t += 1_000_000_000L
            }
        } else {
            trace.speedFixes.forEach { f -> timeline.add(f.tNanos + shift to { detector.onSpeed(f.speedMps, f.tNanos + shift) }) }
        }
        trace.samples.forEach { s ->
            val shifted = s.copy(tNanos = s.tNanos + shift)
            timeline.add(shifted.tNanos to {
                injectedSamples.incrementAndGet()
                feed(shifted)
            })
        }
        timeline.sortBy { it.first }

        val startedAtMs = SystemClock.elapsedRealtime()
        injection = InjectionStatus(trace.scenario.id, "running", startedAtMs, durationNs / 1_000_000L, trace.samples.size)
        Log.i(
            TAG,
            "DEBUG_INJECT ${trace.scenario.id}: ${trace.samples.size} samples, ${durationNs / 1_000_000L} ms at ${trace.rateHz} Hz, " +
                "mode=${req.mode ?: "trace"}, speed=${req.speedMps ?: "trace"}, pace=$pace, expected=${trace.scenario.expected.ifEmpty { "none" }}",
        )
        detector.setAccelMaxRange(trace.accelMaxRangeMs2)
        try {
            var i = 0
            while (i < timeline.size) {
                val dueNs = if (pace == 0.0) Long.MIN_VALUE else base + ((timeline[i].first - base) / pace).toLong()
                val waitMs = (dueNs - SystemClock.elapsedRealtimeNanos()) / 1_000_000L
                if (waitMs > 0) delay(minOf(waitMs, BATCH_MS))
                val nowDelivery = SystemClock.elapsedRealtimeNanos()
                // Deliver everything that is due, like one sensor batch.
                while (i < timeline.size && (pace == 0.0 || base + ((timeline[i].first - base) / pace).toLong() <= nowDelivery)) {
                    timeline[i].second()
                    i++
                }
            }
            injection = injection?.copy(state = "done")
        } finally {
            val lastT = timeline.lastOrNull()?.first ?: base
            // Real samples older than the trace's last timestamp would be dropped as out of order anyway.
            gateUntilNanos = lastT
            val range = pipeline.accelMaxRange
            if (range > 0f) detector.setAccelMaxRange(range)
            detector.onMode(mode, lastT + 1)
            if (injection?.state == "running") injection = injection?.copy(state = "cancelled")
            Log.i(TAG, "DEBUG_INJECT ${trace.scenario.id}: ${injection?.state}; live mode $mode re-applied")
        }
    }

    // ------------------------------------------------------------------ status

    fun statusLines(): List<String> {
        val cfg = pipeline.config
        return listOf(
            "detector: mode=$mode candidates=${candidates.get()} events=${events.get()} lastCandidate=${lastCandidate ?: "-"} lastEvent=${lastEvent ?: "-"}",
            "sensors: ${pipeline.describeSensors()}",
            "plan: ${cfg?.let { "tier=${it.tier} accel=${it.accelHz}Hz gyro=${it.gyroHz}Hz pressure=${it.pressureHz}Hz maxLatency=${it.maxLatencyUs / 1000}ms" } ?: "not registered"}",
            "samples: accel=${pipeline.samplesReceived(SensorKind.ACCEL)} (${hz(pipeline.measuredHz(SensorKind.ACCEL))}) " +
                "gyro=${pipeline.samplesReceived(SensorKind.GYRO)} (${hz(pipeline.measuredHz(SensorKind.GYRO))}) " +
                "pressure=${pipeline.samplesReceived(SensorKind.PRESSURE)} (${hz(pipeline.measuredHz(SensorKind.PRESSURE))}) " +
                "flushes=${pipeline.flushCount} gatedDuringInjection=${gated.get()} injected=${injectedSamples.get()}",
            "injection: ${injection?.let { "${it.scenario} ${it.state} ${it.samples} samples ${it.durationMs} ms" } ?: "none"}",
        )
    }

    private fun hz(v: Double) = if (v.isNaN()) "-" else "%.1f Hz".format(v)

    private inline fun post(crossinline block: () -> Unit) {
        handler.post { block() }
    }

    companion object {
        private const val TAG = "EldDetector"
        private const val BATCH_MS = 50L
        private const val WINDOW_BEFORE_NS = 2_000_000_000L
        private const val WINDOW_AFTER_NS = 15_000_000_000L
    }
}
