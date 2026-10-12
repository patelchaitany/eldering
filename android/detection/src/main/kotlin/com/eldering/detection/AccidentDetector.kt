package com.eldering.detection

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Staged-fusion accident detector (SPEC ":detection"; parameters in docs/research/detection-params.md):
 *
 * 1. **Trigger** (per accelerometer sample): SMV ≥ the mode's trigger threshold, scaled for the sampling rate and
 *    capped below the range, or any axis near saturation. In VEHICLE a second sample above threshold within 30 ms
 *    is required (debounce of single-sample spikes).
 * 2. **Candidate**: t0 = the SMV maximum within 500 ms of the trigger ([possibleAccidentListener] fires here).
 * 3. **Window analysis** once t0 + W has been sampled (W = 10 s, 15 s in vehicle context): features over
 *    [t0 − 2 s, t0 + W] from the ring buffers ([FeatureExtractor]).
 * 4. **Scoring** VEHICLE_CRASH → PEDESTRIAN_IMPACT → FALL with mode thresholds and **suppression** (phone drop,
 *    toss, quick pickup, impulse, continued driving, gait) ([EventRules]).
 * 5. **Emit** the first kind that passes, then a cooldown; a rejected candidate re-arms the trigger 300 ms after t0.
 *
 * Additions to the research pipeline, found with the synthetic matrix (see [DetectorConfig] for the knobs):
 * - A candidate followed within 3.5 s by a clearly larger impact is evaluated after it (its posture and stillness
 *   windows would otherwise describe the later event, e.g. a running stride just before a fall).
 * - A candidate waits for gyro/barometer batches that arrive after the accelerometer batch.
 *
 * Detection stays armed in every mode; the mode only changes thresholds. Gyroscope and pressure are optional.
 * Timestamps must share the `elapsedRealtimeNanos` time base (`SensorEvent.timestamp`, `Location.elapsedRealtimeNanos`);
 * events are emitted with a delay of about W after the impact, in sensor time.
 *
 * Known limits (synthetic validation only; no real accident data exists): accelerometers below ±4 g (the Android CDD
 * minimum) cannot separate the kinds; 25 Hz is too slow for the CYCLING rules; a fall preceded by ≥ 200 ms of true
 * free fall with the phone *not* upright in a pocket beforehand is treated as a dropped phone (research §5.4); a hard
 * "plop" onto a chair is reported as a FALL and a gentle fall onto the knees is missed (research §5.4); vehicle
 * crashes below ≈16 km/h are ignored by design.
 *
 * Threading: not thread-safe. Make every call from one thread (the sensor handler thread), and do not call back into
 * the detector from its callbacks. Per sensor kind, samples must arrive in timestamp order (duplicates and reordered
 * samples are dropped); accelerometer, gyroscope, mode and speed may interleave in any order, e.g. as separate sensor
 * batches. [listener], [candidateObserver] and [possibleAccidentListener] are invoked synchronously on the calling
 * thread.
 */
public class AccidentDetector(
    public val config: DetectorConfig = DetectorConfig(),
    public val listener: (DetectionEvent) -> Unit,
) {
    /**
     * Optional diagnostics hook, called for every analysed candidate (emitted or rejected), e.g. to shadow-log
     * rejected pedestrian-impact scores (research §2.3) or to collect false-alarm training data.
     */
    public var candidateObserver: ((CandidateOutcome) -> Unit)? = null

    /**
     * Optional "possible accident" hook (SPEC battery tiers): called with the candidate's peak time as soon as the
     * cheap trigger has found a candidate, about W before any verdict, so the app can raise the sensor rate and GPS
     * accuracy. It fires for every candidate, which can be every stride while running; rate-limit in the app.
     */
    public var possibleAccidentListener: ((tNanos: Long) -> Unit)? = null

    private val rules = EventRules(config)
    private val retentionNs =
        ms(config.preWindowMs + config.maxPostWindowMs + config.supersedeWindowMs + config.candidateSearchMs + BUFFER_SLACK_MS)
    private val accel = SampleRing(1024, MAX_RING_CAPACITY)
    private val gyro = SampleRing(1024, MAX_RING_CAPACITY)
    private val pressure = SampleRing(64, 4096)
    private val modes = ModeHistory()
    private val speeds = SpeedHistory()
    private val range = RangeTracker(config)
    private val rate = RateEstimator(config.rateEstimateSamples)

    // Trigger scan state: the next accelerometer sample to test, and the open 500 ms search for the peak.
    private var scanSeq = 0L
    private var searchOpen = false
    private var searchStartSeq = 0L
    private var searchStartNs = 0L
    private val pending = ArrayDeque<Candidate>()
    private var cooldownUntilNs = Long.MIN_VALUE

    private class Candidate(val t0Ns: Long, val mode: Mode, val vehicleContext: Boolean, val postWindowMs: Long) {
        /** Impulse above 1 g around t0 (g·s), computed on first use. */
        var impulse: Double = Double.NaN

        /** Earlier candidates waiting for this one's verdict (see [DetectorConfig.supersedeWindowMs]). */
        val deferred: MutableList<Candidate> = ArrayList()
    }

    /** Most recently reported movement mode (analysis looks modes up by timestamp instead). */
    internal var mode: Mode = Mode.UNKNOWN
        private set

    /** Last valid GPS speed in m/s, or null if none has been reported. */
    internal var lastSpeedMps: Float? = null
        private set

    internal var lastSpeedAtNanos: Long = Long.MIN_VALUE
        private set

    /** Accelerometer full-scale range set by the app, m/s² (null until [setAccelMaxRange]). */
    internal val accelMaxRangeMs2: Float?
        get() = range.configuredG?.let { (it * G0).toFloat() }

    /** Effective full-scale range in g after auto-raise / clip inference. */
    internal val effectiveRangeG: Double get() = range.rangeG

    // Diagnostics for tests (memory bounds).
    internal val bufferedAccelSamples: Int get() = accel.size
    internal val bufferedGyroSamples: Int get() = gyro.size
    internal val pendingCandidates: Int get() = pending.size
    internal val contextEntries: Int get() = modes.size + speeds.size

    /** Feeds one sensor reading. Non-finite readings and out-of-order timestamps are dropped. */
    public fun onSample(s: SensorSample) {
        when (s.kind) {
            SensorKind.ACCEL -> onAccel(s)
            SensorKind.GYRO -> {
                if (!s.x.isFinite() || !s.y.isFinite() || !s.z.isFinite()) return
                if (!gyro.isEmpty() && s.tNanos <= gyro.lastT()) return
                gyro.add(s.tNanos, s.x, s.y, s.z)
                gyro.evictBefore(s.tNanos - retentionNs)
                if (pending.isNotEmpty()) evaluateReadyCandidates()
            }
            SensorKind.PRESSURE -> {
                if (!s.x.isFinite() || s.x <= 0f) return
                if (!pressure.isEmpty() && s.tNanos <= pressure.lastT()) return
                pressure.add(s.tNanos, s.x, 0f, 0f)
                pressure.evictBefore(s.tNanos - retentionNs)
                if (pending.isNotEmpty()) evaluateReadyCandidates()
            }
        }
    }

    /** Reports a movement-mode change at sensor time [tNanos]; it applies to candidates whose peak is later. */
    public fun onMode(mode: Mode, tNanos: Long) {
        modes.add(tNanos, mode)
        this.mode = mode
    }

    /**
     * Reports a GPS speed sample. Non-finite or negative values (no speed fix) are ignored rather than
     * thrown, because this runs inside the always-on safety service.
     */
    public fun onSpeed(speedMps: Float, tNanos: Long) {
        if (!speedMps.isFinite() || speedMps < 0f) return
        speeds.add(tNanos, speedMps)
        lastSpeedMps = speedMps
        lastSpeedAtNanos = tNanos
    }

    /**
     * Sets the accelerometer full-scale range (Android `Sensor.getMaximumRange()`, m/s²) so that clipped samples
     * are recognised and thresholds stay reachable on ±2/4 g sensors. Until called, ±[DetectorConfig.defaultAccelRangeG]
     * is assumed; readings beyond the range raise it, and a clip plateau below it lowers it.
     *
     * @throws IllegalArgumentException if [ms2] is not a finite positive number.
     */
    public fun setAccelMaxRange(ms2: Float) {
        require(ms2.isFinite() && ms2 > 0f) { "Accelerometer range must be a finite positive m/s² value, was $ms2" }
        range.configure(ms2)
    }

    // ------------------------------------------------------------------ pipeline

    private fun onAccel(s: SensorSample) {
        if (!s.x.isFinite() || !s.y.isFinite() || !s.z.isFinite()) return
        if (!accel.isEmpty() && s.tNanos <= accel.lastT()) return
        range.observe(s.x, s.y, s.z)
        accel.add(s.tNanos, (s.x / G0).toFloat(), (s.y / G0).toFloat(), (s.z / G0).toFloat())
        rate.add(s.tNanos)
        if (scanSeq < accel.firstSeq) scanSeq = accel.firstSeq
        advanceScan()
        evaluateReadyCandidates()
        prune(s.tNanos)
    }

    /** Runs the trigger scan and peak search as far as the buffered samples allow. */
    private fun advanceScan() {
        val latest = accel.lastT()
        while (true) {
            if (searchOpen) {
                if (latest < searchStartNs + ms(config.candidateSearchMs)) return
                closeSearch()
                continue
            }
            if (!scanForTrigger(latest)) return
        }
    }

    /** Scans from [scanSeq]; returns true if a trigger opened a search, false when out of data. */
    private fun scanForTrigger(latest: Long): Boolean {
        while (scanSeq < accel.endSeq) {
            val seq = scanSeq
            val t = accel.t(seq)
            if (t < cooldownUntilNs) {
                scanSeq++
                continue
            }
            when (triggerAt(seq, latest)) {
                Trigger.YES -> {
                    searchOpen = true
                    searchStartSeq = seq
                    searchStartNs = t
                    return true
                }
                Trigger.NO -> scanSeq++
                Trigger.WAIT -> return false
            }
        }
        return false
    }

    private enum class Trigger { YES, NO, WAIT }

    private fun triggerAt(seq: Long, latest: Long): Trigger {
        val t = accel.t(seq)
        val mode = modes.at(t)
        val threshold = effectiveG(config.thresholdsFor(mode).triggerG, captureFactorNow())
        if (isSaturated(seq)) return Trigger.YES
        if (smv(seq) < threshold) return Trigger.NO
        if (mode != Mode.VEHICLE) return Trigger.YES
        // VEHICLE debounce: a second sample above threshold within 30 ms (1.5 periods at low rates).
        val periodNs = rate.periodS().let { if (it.isNaN()) 0.0 else it * 1e9 }
        val windowNs = max(ms(config.vehicleDebounceMs).toDouble(), 1.5 * periodNs).toLong()
        var k = seq + 1
        while (k < accel.endSeq && accel.t(k) <= t + windowNs) {
            if (smv(k) >= threshold) return Trigger.YES
            k++
        }
        return if (latest <= t + windowNs) Trigger.WAIT else Trigger.NO
    }

    /** Ends the 500 ms peak search: t0 = earliest maximum SMV in [trigger, trigger + 500 ms]. */
    private fun closeSearch() {
        searchOpen = false
        val end = searchStartNs + ms(config.candidateSearchMs)
        var best = searchStartSeq.coerceAtLeast(accel.firstSeq)
        var bestS = -1.0
        var k = best
        while (k < accel.endSeq && accel.t(k) <= end) {
            val v = smv(k)
            if (v > bestS) {
                bestS = v
                best = k
            }
            k++
        }
        val t0 = accel.t(best)
        scanSeq = accel.lowerBound(t0 + ms(config.candidateRearmMs))
        if (t0 < cooldownUntilNs) return
        val vehicle = vehicleContext(t0)
        val post = if (vehicle) config.vehiclePostWindowMs else config.postWindowMs
        pending.addLast(Candidate(t0, modes.at(t0), vehicle, post))
        possibleAccidentListener?.invoke(t0)
    }

    private fun evaluateReadyCandidates() {
        if (accel.isEmpty()) return
        val latest = accel.lastT()
        while (pending.isNotEmpty()) {
            val c = pending.first()
            if (!isReady(c, latest)) return
            pending.removeFirst()
            val successor = supersedingCandidate(c)
            if (successor != null) {
                successor.deferred.add(c)
                continue
            }
            resolve(c)
        }
    }

    /**
     * The accelerometer has reached t0 + W, and every optional stream that was running around the event (gyro,
     * barometer) has delivered the part of the window it contributes, or [DetectorConfig.auxStreamWaitMs] has passed.
     */
    private fun isReady(c: Candidate, latestAccel: Long): Boolean {
        val due = c.t0Ns + ms(c.postWindowMs)
        if (latestAccel < due) return false
        if (latestAccel >= due + ms(config.auxStreamWaitMs)) return true
        return caughtUp(gyro, c.t0Ns, ms(GYRO_TO_MS + WINDOW_MARGIN_MS)) && caughtUp(pressure, c.t0Ns, ms(PRESSURE_DELTA_TO_MS))
    }

    private fun caughtUp(ring: SampleRing, t0: Long, neededAfterT0: Long): Boolean {
        if (ring.isEmpty()) return true
        val last = ring.lastT()
        return last < t0 - ms(config.preWindowMs) || last >= t0 + neededAfterT0
    }

    /** The largest later candidate within the supersede window whose impulse clearly exceeds [c]'s, if any. */
    private fun supersedingCandidate(c: Candidate): Candidate? {
        val horizon = c.t0Ns + ms(config.supersedeWindowMs)
        val own = impulseOf(c) * config.supersedeRatio
        var best: Candidate? = null
        for (other in pending) {
            if (other.t0Ns > horizon) break
            if (impulseOf(other) > own && (best == null || impulseOf(other) > impulseOf(best))) best = other
        }
        return best
    }

    /** Σ max(0, SMV − 1 g)·Δt over [t0 − 0.1 s, t0 + 0.2 s], g·s: impact size that tolerates clipping. */
    private fun impulseOf(c: Candidate): Double {
        if (!c.impulse.isNaN()) return c.impulse
        var sum = 0.0
        var k = accel.lowerBound(c.t0Ns - ms(IMPULSE_BEFORE_MS))
        val end = accel.upperBound(c.t0Ns + ms(IMPULSE_AFTER_MS))
        while (k + 1 < end) {
            sum += max(0.0, smv(k) - 1.0) * (accel.t(k + 1) - accel.t(k)) / 1e9
            k++
        }
        c.impulse = sum
        return sum
    }

    /** Evaluates [c]; if it does not emit, the candidates it superseded get their turn (oldest first). */
    private fun resolve(c: Candidate) {
        val emitted = if (c.t0Ns < cooldownUntilNs) {
            report(c, null, Rejection.COOLDOWN, emptyMap())
            false
        } else {
            evaluate(c)
        }
        if (c.deferred.isEmpty()) return
        val waiting = c.deferred.sortedBy { it.t0Ns }
        c.deferred.clear()
        for (d in waiting) if (emitted) drop(d) else resolve(d)
    }

    /** Discards [c] and everything deferred to it: the event they belong to has been emitted. */
    private fun drop(c: Candidate) {
        report(c, null, Rejection.COOLDOWN, emptyMap())
        c.deferred.forEach(::drop)
        c.deferred.clear()
    }

    /** Analyses one candidate; returns true if it emitted an event. */
    private fun evaluate(c: Candidate): Boolean {
        val window = cutWindow(c)
        if (window == null) {
            report(c, null, Rejection.INSUFFICIENT_DATA, emptyMap())
            return false
        }
        val features = FeatureExtractor.extract(window, config)
        val ctx = RuleContext(c.mode, c.vehicleContext, captureFactor(1.0 / features.rateHz))
        val decision = rules.classify(features, ctx)
        val map = features.toMap()
        map[FeatureKeys.MODE] = c.mode.ordinal.toFloat()
        map[FeatureKeys.VEHICLE_CONTEXT] = if (c.vehicleContext) 1f else 0f
        decision.crashPoints?.let { map[FeatureKeys.CRASH_SCORE] = it / 100f }
        decision.strikePoints?.let { map[FeatureKeys.STRIKE_SCORE] = it / 100f }
        decision.fallPoints?.let { map[FeatureKeys.FALL_SCORE] = it / 100f }
        for ((name, pts) in decision.parts) {
            val key = FeatureKeys.CONFIDENCE_PART_PREFIX + name
            map[key] = (map[key] ?: 0f) + pts / 100f
        }
        val kind = decision.kind
        if (kind == null) {
            report(c, null, decision.rejection, map)
            return false
        }
        val event = DetectionEvent(kind, decision.confidence, c.t0Ns, map.toMap(), decision.reason)
        cooldownUntilNs = c.t0Ns + ms(config.cooldownMs)
        if (searchOpen && searchStartNs < cooldownUntilNs) searchOpen = false
        report(c, event, null, event.features)
        listener(event)
        return true
    }

    private fun report(c: Candidate, event: DetectionEvent?, rejection: Rejection?, features: Map<String, Float>) {
        val observer = candidateObserver ?: return
        observer(
            CandidateOutcome(
                t0Nanos = c.t0Ns,
                mode = c.mode,
                kind = event?.kind,
                confidence = event?.confidence ?: 0f,
                verdict = event?.reason ?: rejection?.tag ?: "",
                features = features.toMap(),
            ),
        )
    }

    /** Copies the candidate's data out of the ring buffers; null if the pre-event window is not buffered. */
    private fun cutWindow(c: Candidate): CandidateWindow? {
        val t0 = c.t0Ns
        val preNs = ms(config.preWindowMs)
        val postNs = ms(c.postWindowMs)
        if (accel.isEmpty() || accel.firstT() > t0 - preNs) return null
        val a0 = accel.lowerBound(t0 - preNs - ms(WINDOW_MARGIN_MS)).coerceAtLeast(accel.firstSeq)
        val a1 = accel.upperBound(t0 + postNs)
        val n = (a1 - a0).toInt()
        if (n < 2) return null
        val at = DoubleArray(n) { (accel.t(a0 + it) - t0) / 1e9 }
        val ax = DoubleArray(n) { accel.x(a0 + it).toDouble() }
        val ay = DoubleArray(n) { accel.y(a0 + it).toDouble() }
        val az = DoubleArray(n) { accel.z(a0 + it).toDouble() }

        val g0 = gyro.lowerBound(t0 - ms(GYRO_FROM_MS + WINDOW_MARGIN_MS))
        val g1 = gyro.upperBound(t0 + ms(GYRO_TO_MS + WINDOW_MARGIN_MS))
        val gn = (g1 - g0).toInt().coerceAtLeast(0)
        val gt = DoubleArray(gn) { (gyro.t(g0 + it) - t0) / 1e9 }
        val gx = DoubleArray(gn) { gyro.x(g0 + it).toDouble() }
        val gy = DoubleArray(gn) { gyro.y(g0 + it).toDouble() }
        val gz = DoubleArray(gn) { gyro.z(g0 + it).toDouble() }

        val p0 = pressure.lowerBound(t0 - ms(PRESSURE_FROM_MS))
        val p1 = pressure.upperBound(t0 + ms(PRESSURE_TO_MS))
        val pn = (p1 - p0).toInt().coerceAtLeast(0)
        val pt = DoubleArray(pn) { (pressure.t(p0 + it) - t0) / 1e9 }
        val pv = DoubleArray(pn) { pressure.x(p0 + it).toDouble() }

        val (st, sv) = speeds.window(t0 - ms(SPEED_BEFORE_MS), t0 + postNs, t0)
        return CandidateWindow(at, ax, ay, az, gt, gx, gy, gz, pt, pv, st, sv, c.postWindowMs / 1000.0, range.rangeG)
    }

    /** VEHICLE reported within the hold time, or a fast GPS speed in it (research §0.3 / §2.2). */
    private fun vehicleContext(t0: Long): Boolean {
        val from = t0 - ms(config.vehicleHoldMs)
        if (modes.wasInEffect(Mode.VEHICLE, from, t0)) return true
        if (config.cyclingSpeedIsNotVehicle && modes.at(t0) == Mode.CYCLING) return false
        return speeds.anyAtLeast(config.speedImpliesVehicleMps, from, t0)
    }

    private fun prune(latest: Long) {
        accel.evictBefore(latest - retentionNs)
        if (scanSeq < accel.firstSeq) scanSeq = accel.firstSeq
        val horizon = latest - retentionNs - ms(config.vehicleHoldMs)
        modes.pruneBefore(horizon)
        speeds.pruneBefore(horizon)
    }

    // ------------------------------------------------------------------ helpers

    private fun smv(seq: Long): Double = FeatureMath.smv(accel.x(seq).toDouble(), accel.y(seq).toDouble(), accel.z(seq).toDouble())

    private fun isSaturated(seq: Long): Boolean {
        val limit = config.saturationFraction * range.rangeG
        return abs(accel.x(seq)) >= limit || abs(accel.y(seq)) >= limit || abs(accel.z(seq)) >= limit
    }

    private fun captureFactor(periodS: Double): Double =
        FeatureMath.captureFactor(periodS, config.bodyImpactMinMs / 1000.0, config.minCaptureFactor.toDouble())

    private fun captureFactorNow(): Double = rate.periodS().let { if (it.isNaN()) 1.0 else captureFactor(it) }

    /** `eff(x) = min(x·cf, headroom·range)` (research §2.1). */
    private fun effectiveG(g: Float, captureFactor: Double): Double = min(g * captureFactor, config.rangeHeadroom * range.rangeG)

    private companion object {
        const val MAX_RING_CAPACITY = 1 shl 14 // 41 s at 400 Hz; retention needs ~20 s
        const val BUFFER_SLACK_MS = 2_000L
        const val WINDOW_MARGIN_MS = 100L
        const val GYRO_FROM_MS = 2_000L
        const val GYRO_TO_MS = 1_000L
        const val PRESSURE_FROM_MS = 3_500L
        const val PRESSURE_TO_MS = 5_500L
        const val PRESSURE_DELTA_TO_MS = 5_000L
        const val SPEED_BEFORE_MS = 15_000L
        const val IMPULSE_BEFORE_MS = 100L
        const val IMPULSE_AFTER_MS = 200L

        fun ms(v: Long): Long = v * 1_000_000L
    }
}
