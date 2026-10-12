package com.eldering.detection

/** Events and diagnostics from one [TraceReplayer.run]. */
public data class ReplayResult(
    val trace: SyntheticTrace,
    val events: List<DetectionEvent>,
    val candidates: List<CandidateOutcome>,
)

/**
 * Feeds a [SyntheticTrace] (sensor samples plus the mode and speed timeline) into an [AccidentDetector].
 *
 * Mode and speed reports are delivered when their timestamp is reached. With `batchMs` > 0 sensor samples are
 * delivered the way Android batching does: every `batchMs` of sensor time, all accelerometer samples of the batch,
 * then all gyroscope samples, then pressure, after the mode and speed reports of that period. This exercises the
 * detector's timestamp-based alignment.
 *
 * Debug injection in the app: replay into a *dedicated* detector that shares the live config and listener, e.g.
 * `replay(trace, AccidentDetector(config, liveListener), startAtNanos = startForEndingAt(trace, elapsedRealtimeNanos()))`.
 * Replaying into the live instance would mix the trace's timeline with the real stream (one of the two would be
 * dropped as out of order, and the trace's modes would overwrite the live ones).
 */
public object TraceReplayer {

    /** Start time that makes [trace] end at [endNanos], so the replayed event lies in the recent past. */
    public fun startForEndingAt(trace: SyntheticTrace, endNanos: Long): Long = endNanos - (trace.endNanos - trace.startNanos)

    /**
     * Replays [trace] into [detector].
     *
     * @param startAtNanos sensor time the trace's start is mapped to (see [startForEndingAt]).
     * @param applyAccelRange call [AccidentDetector.setAccelMaxRange] with the trace's range first.
     * @param batchMs sensor batching period (0 = deliver everything in timestamp order).
     */
    public fun replay(
        trace: SyntheticTrace,
        detector: AccidentDetector,
        startAtNanos: Long = trace.startNanos,
        applyAccelRange: Boolean = true,
        batchMs: Long = 0,
    ) {
        require(batchMs >= 0) { "batchMs must be >= 0, was $batchMs" }
        val shift = startAtNanos - trace.startNanos
        if (applyAccelRange) detector.setAccelMaxRange(trace.accelMaxRangeMs2)
        val context = ArrayList<Pair<Long, () -> Unit>>(trace.modeChanges.size + trace.speedFixes.size)
        trace.modeChanges.forEach { m -> context.add(m.tNanos + shift to { detector.onMode(m.mode, m.tNanos + shift) }) }
        trace.speedFixes.forEach { f -> context.add(f.tNanos + shift to { detector.onSpeed(f.speedMps, f.tNanos + shift) }) }
        context.sortBy { it.first }
        var nextContext = 0
        fun deliverContextUpTo(t: Long) {
            while (nextContext < context.size && context[nextContext].first <= t) context[nextContext++].second()
        }

        val samples = if (shift == 0L) trace.samples else trace.samples.map { it.copy(tNanos = it.tNanos + shift) }
        if (batchMs == 0L) {
            for (s in samples) {
                deliverContextUpTo(s.tNanos)
                detector.onSample(s)
            }
        } else {
            val batchNs = batchMs * 1_000_000L
            var i = 0
            var batchEnd = startAtNanos + batchNs
            while (i < samples.size) {
                val batch = ArrayList<SensorSample>()
                while (i < samples.size && samples[i].tNanos < batchEnd) batch.add(samples[i++])
                deliverContextUpTo(batchEnd)
                for (kind in SensorKind.entries) batch.filter { it.kind == kind }.forEach(detector::onSample)
                batchEnd += batchNs
            }
        }
        deliverContextUpTo(Long.MAX_VALUE)
    }

    /** Replays [trace] into a fresh detector and collects what it reports. */
    public fun run(trace: SyntheticTrace, config: DetectorConfig = DetectorConfig(), batchMs: Long = 0): ReplayResult {
        val events = ArrayList<DetectionEvent>()
        val candidates = ArrayList<CandidateOutcome>()
        val detector = AccidentDetector(config) { events.add(it) }
        detector.candidateObserver = { candidates.add(it) }
        replay(trace, detector, batchMs = batchMs)
        return ReplayResult(trace, events, candidates)
    }
}
