package com.eldering.detection

import java.util.Locale

/** One cell of the robustness matrix. */
data class MatrixCase(
    val scenario: Scenario,
    val rateHz: Int,
    val rangeG: Float,
    val seed: Long,
    val withGyro: Boolean = true,
    val withPressure: Boolean? = null,
    val arbitraryMounting: Boolean = false,
    val batchMs: Long = 0,
) {
    val label: String
        get() = buildString {
            append(scenario.id).append(" @").append(rateHz).append("Hz ±").append(rangeG.toInt()).append("g seed=").append(seed)
            if (!withGyro) append(" no-gyro")
            if (withPressure == true) append(" +baro")
            if (arbitraryMounting) append(" any-orientation")
            if (batchMs > 0) append(" batch=").append(batchMs).append("ms")
        }
}

/** Outcome of one matrix cell (keeps only events and diagnostics, not the trace, so large matrices fit in memory). */
data class MatrixResult(val case: MatrixCase, val events: List<DetectionEvent>, val candidates: List<CandidateOutcome>, val eventAtNanos: Long?, val startNanos: Long) {
    val firstEvent: DetectionEvent? get() = events.firstOrNull()
    val detected: EventKind? get() = firstEvent?.kind

    /** Expected outcome: the right kind near the true event time for positives, nothing at all for negatives. */
    val passed: Boolean
        get() {
            val sc = case.scenario
            if (!sc.expectsEvent) return events.isEmpty()
            val ev = firstEvent ?: return false
            val at = eventAtNanos ?: return false
            val dt = (ev.triggeredAtNanos - at) / 1e9
            return ev.kind in sc.expected && dt in -0.5..2.0 && events.all { it.kind in sc.expected }
        }

    fun describe(): String = String.format(
        Locale.ROOT, "%-62s expected=%-30s got=%s conf=%.2f verdicts=%s",
        case.label, case.scenario.expected.ifEmpty { "none" }, events.map { it.kind }, firstEvent?.confidence ?: 0f,
        candidates.filter { !it.verdict.startsWith("insufficient") }.take(5).map { it.verdict.take(40) },
    )
}

object MatrixHarness {
    /** Small LRU: a rendered truth is reused across rates/ranges of one (scenario, seed), but is ~2 MB each. */
    private val renderCache = object : LinkedHashMap<Triple<Scenario, Long, Boolean>, RenderedTruth>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Triple<Scenario, Long, Boolean>, RenderedTruth>?): Boolean = size > 8
    }

    @Synchronized
    private fun rendered(sc: Scenario, seed: Long, arbitrary: Boolean): RenderedTruth =
        renderCache.getOrPut(Triple(sc, seed, arbitrary)) {
            SyntheticTraces.render(sc, seed, randomizeOrientation = true, arbitraryMounting = arbitrary, randomizeTiming = true)
        }

    fun run(c: MatrixCase, config: DetectorConfig = DetectorConfig()): MatrixResult {
        val spec = TraceSpec(
            rateHz = c.rateHz, accelRangeG = c.rangeG, seed = c.seed, withGyro = c.withGyro, withPressure = c.withPressure,
            arbitraryMounting = c.arbitraryMounting,
        )
        val trace = SyntheticTraces.sample(c.scenario, rendered(c.scenario, c.seed, c.arbitraryMounting), spec)
        val r = TraceReplayer.run(trace, config, c.batchMs)
        return MatrixResult(c, r.events, r.candidates, trace.eventAtNanos, trace.startNanos)
    }

    /** Confusion matrix (rows = expected class, columns = first detected kind) as printable text. */
    fun confusion(results: List<MatrixResult>): String {
        val classes = listOf<EventKind?>(null) + EventKind.entries
        fun name(k: EventKind?) = k?.name ?: "NONE"
        fun expectedClass(r: MatrixResult): EventKind? = r.case.scenario.expected.let { if (it.isEmpty()) null else if (it.size == 1) it.first() else r.detected?.takeIf { d -> d in it } ?: it.first() }
        val sb = StringBuilder()
        sb.append(String.format(Locale.ROOT, "%-20s", "expected \\ got"))
        classes.forEach { sb.append(String.format(Locale.ROOT, "%19s", name(it))) }
        sb.append('\n')
        for (row in classes) {
            val rs = results.filter { expectedClass(it) == row }
            if (rs.isEmpty()) continue
            sb.append(String.format(Locale.ROOT, "%-20s", name(row)))
            classes.forEach { col -> sb.append(String.format(Locale.ROOT, "%19d", rs.count { it.detected == col })) }
            sb.append('\n')
        }
        return sb.toString()
    }
}
