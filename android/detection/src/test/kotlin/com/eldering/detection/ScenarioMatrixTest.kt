package com.eldering.detection

import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import java.io.File
import java.util.Locale
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * End-to-end scenario matrix: synthetic traces replayed through [AccidentDetector].
 *
 * Asserted (every cell must give the expected outcome: the right kind within [−0.5 s, +2 s] of the true event for
 * positives, no event at all for negatives):
 * - the build task's 14 scenarios × {50, 100, 200} Hz × {±8, ±16} g × 6 seeds (seed = noise, jitter, drops, the
 *   phone's orientation in its carrying position, heading, and the event's gait phase);
 * - the research extras at their reference rate/range (6 seeds) plus an alternate rate;
 * - robustness variants: no gyroscope, 10 s sensor batching, ±4 g, 400 Hz, arbitrary phone orientation;
 * - confidence bounds at the reference configuration and barometer-optional behaviour.
 *
 * Reported but not asserted (physically impossible or out of scope; see [limits report]): ±2 g sensors (below the
 * Android CDD minimum of ±4 g), 25 Hz in CYCLING/VEHICLE (the app samples those modes at 50/100 Hz), arbitrary
 * orientation for the two scenarios that depend on the "phone upright in a pocket" cue, and the research's documented
 * limits (hard sit-down "plop" = false alarm, fall onto the knees = miss).
 */
class ScenarioMatrixTest {

    companion object {
        val REQUIRED: List<Scenario> = listOf(
            Scenario.WALK, Scenario.RUN, Scenario.STAIRS, Scenario.SIT_DOWN, Scenario.PHONE_DROP, Scenario.PHONE_TOSS_ON_BED,
            Scenario.POTHOLE, Scenario.SPEED_BUMP, Scenario.HARD_BRAKE, Scenario.DOOR_SLAM, Scenario.FALL,
            Scenario.PEDESTRIAN_IMPACT, Scenario.VEHICLE_CRASH, Scenario.CYCLING_CRASH,
        )
        val RATES: List<Int> = listOf(50, 100, 200)
        val RANGES: List<Float> = listOf(8f, 16f)
        val SEEDS: LongRange = 0L..5L
        val EXTRAS: List<Scenario> = Scenario.entries.filter { it !in REQUIRED && !it.knownLimit }

        /** Scenarios whose suppression relies on the phone being upright in a pocket before the event (research §3). */
        val ORIENTATION_CUE: Set<Scenario> = setOf(Scenario.CARPET_DROP_LEFT, Scenario.LADDER_FALL)

        private fun cases(scenarios: List<Scenario>, seeds: LongRange, rates: (Scenario) -> List<Int>, ranges: (Scenario) -> List<Float>, variant: (MatrixCase) -> MatrixCase = { it }) =
            buildList { for (sc in scenarios) for (seed in seeds) for (r in rates(sc)) for (g in ranges(sc)) add(variant(MatrixCase(sc, r, g, seed))) }

        val requiredResults: List<MatrixResult> by lazy { cases(REQUIRED, SEEDS, { RATES }, { RANGES }).map { MatrixHarness.run(it) } }

        val extraResults: List<MatrixResult> by lazy {
            val reference = cases(EXTRAS, SEEDS, { listOf(it.defaultRateHz) }, { listOf(it.defaultRangeG) })
            val alternate = cases(EXTRAS, 0L..1L, { listOf(if (it.defaultRateHz < 100) 100 else 50) }, { listOf(it.defaultRangeG) })
            (reference + alternate).map { MatrixHarness.run(it) }
        }

        val variantResults: List<MatrixResult> by lazy {
            val all = REQUIRED + EXTRAS
            val seeds = 0L..2L
            val noGyro = cases(all, seeds, { listOf(50, 100) }, { RANGES }) { it.copy(withGyro = false) }
            val batched = cases(all, seeds, { listOf(100) }, { listOf(8f) }) { it.copy(batchMs = 10_000) }
            val fourG = cases(all, seeds, { RATES }, { listOf(4f) })
            val fast = cases(all, seeds, { listOf(400) }, { RANGES })
            val anyOrientation = cases(all - ORIENTATION_CUE, 0L..3L, { listOf(100) }, { listOf(8f) }) { it.copy(arbitraryMounting = true) }
            (noGyro + batched + fourG + fast + anyOrientation).map { MatrixHarness.run(it) }
        }

        fun tests(results: List<MatrixResult>): List<DynamicTest> =
            results.map { r -> dynamicTest(r.case.label) { assertTrue(r.passed, r.describe()) } }
    }

    @TestFactory
    fun `required scenarios x rates x ranges x seeds`(): List<DynamicTest> = tests(requiredResults)

    @TestFactory
    fun `research extras at their reference and an alternate rate`(): List<DynamicTest> = tests(extraResults)

    @TestFactory
    fun `robustness - no gyro, batching, 4 g, 400 Hz, arbitrary orientation`(): List<DynamicTest> = tests(variantResults)

    @TestFactory
    fun `confidence bounds at the reference configuration`(): List<DynamicTest> =
        (REQUIRED + EXTRAS).filter { it.expectsEvent }.flatMap { sc ->
            SEEDS.map { seed ->
                dynamicTest("${sc.id} seed=$seed") {
                    val r = MatrixHarness.run(MatrixCase(sc, sc.defaultRateHz, sc.defaultRangeG, seed))
                    val c = r.firstEvent?.confidence ?: 0f
                    assertTrue(c >= sc.minConfidence && c <= sc.maxConfidence, "${sc.id}: confidence $c outside [${sc.minConfidence}, ${sc.maxConfidence}] ${r.describe()}")
                }
            }
        }

    @TestFactory
    fun `barometer is optional - same kind, confidence within 0_1`(): List<DynamicTest> =
        (REQUIRED + EXTRAS).filter { it.expectsEvent }.map { sc ->
            dynamicTest(sc.id) {
                for (seed in 0L..2L) {
                    val without = MatrixHarness.run(MatrixCase(sc, sc.defaultRateHz, sc.defaultRangeG, seed, withPressure = false)).firstEvent!!
                    val with = MatrixHarness.run(MatrixCase(sc, sc.defaultRateHz, sc.defaultRangeG, seed, withPressure = true)).firstEvent!!
                    assertEquals(without.kind, with.kind, "seed $seed")
                    assertTrue(with.confidence - without.confidence in -1e-6f..0.1f + 1e-6f, "seed $seed: ${without.confidence} → ${with.confidence}")
                    assertEquals(sc == Scenario.FALL_WITH_BARO, FeatureKeys.PRESSURE_DELTA_HPA in with.features && with.features.getValue(FeatureKeys.PRESSURE_DELTA_HPA) > 0.04f)
                }
            }
        }

    @Test
    fun `summary - confusion matrix, zero false positives, recall at least 95 percent`() {
        val all = requiredResults + extraResults + variantResults
        val negatives = all.filter { !it.case.scenario.expectsEvent }
        val positives = all.filter { it.case.scenario.expectsEvent }
        val falsePositives = negatives.filter { it.events.isNotEmpty() }
        val hits = positives.count { it.passed }
        val recall = hits.toDouble() / positives.size
        val report = buildString {
            appendLine("Accident detector scenario matrix (synthetic traces)")
            appendLine(String.format(Locale.ROOT, "runs=%d  negatives=%d  false positives=%d  positives=%d  recall=%.4f", all.size, negatives.size, falsePositives.size, positives.size, recall))
            appendLine()
            appendLine("All asserted runs:")
            append(MatrixHarness.confusion(all))
            appendLine()
            appendLine("Required matrix only (14 scenarios × {50,100,200} Hz × {8,16} g × 6 seeds):")
            append(MatrixHarness.confusion(requiredResults))
            appendLine()
            appendLine("Per scenario (all asserted runs):")
            for ((sc, rs) in all.groupBy { it.case.scenario }) {
                appendLine(String.format(Locale.ROOT, "  %-32s %4d/%-4d expected=%s", sc.id, rs.count { it.passed }, rs.size, sc.expected.ifEmpty { "none" }))
            }
            if (falsePositives.isNotEmpty()) appendLine("FALSE POSITIVES:\n" + falsePositives.joinToString("\n") { "  " + it.describe() })
            val misses = positives.filter { !it.passed }
            if (misses.isNotEmpty()) appendLine("MISSES / WRONG KIND:\n" + misses.joinToString("\n") { "  " + it.describe() })
        }
        println(report)
        writeReport("scenario-matrix.txt", report)
        assertTrue(falsePositives.isEmpty(), "false positives:\n" + falsePositives.joinToString("\n") { it.describe() })
        assertTrue(recall >= 0.95, "recall $recall")
    }

    /**
     * Combinations outside the supported envelope, run for the record (written to
     * build/reports/detection/limits.txt). Nothing here is asserted: these are physical or design limits.
     */
    @Test
    fun `limits report`() {
        val sections = linkedMapOf<String, List<MatrixResult>>()
        sections["±2 g sensor (below the CDD ±4 g minimum: every gate above 2 g collapses to 1.8 g)"] =
            cases(REQUIRED + EXTRAS, 0L..1L, { listOf(50, 100) }, { listOf(2f) }).map { MatrixHarness.run(it) }
        sections["25 Hz in modes the app samples faster (CYCLING 50 Hz, VEHICLE 100 Hz)"] =
            cases(REQUIRED.filter { it.mode == Mode.CYCLING || it.mode == Mode.VEHICLE }, 0L..1L, { listOf(25) }, { listOf(8f) }).map { MatrixHarness.run(it) }
        sections["Arbitrary orientation where suppression uses the upright-pocket cue"] =
            cases(ORIENTATION_CUE.toList(), 0L..5L, { listOf(50, 100) }, { listOf(8f) }) { it.copy(arbitraryMounting = true) }.map { MatrixHarness.run(it) }
        sections["Research §5.4 documented limits (sit 'plop' = false alarm, knee fall = miss)"] =
            cases(Scenario.entries.filter { it.knownLimit }, 0L..2L, { listOf(it.defaultRateHz) }, { listOf(it.defaultRangeG) }).map { MatrixHarness.run(it) }
        val report = buildString {
            for ((title, rs) in sections) {
                appendLine(title)
                for ((sc, group) in rs.groupBy { it.case.scenario }) {
                    val got = group.groupingBy { it.detected?.name ?: "NONE" }.eachCount()
                    appendLine(String.format(Locale.ROOT, "  %-32s expected=%-30s got=%s  as-expected=%d/%d", sc.id, sc.expected.ifEmpty { "none" }, got, group.count { it.passed }, group.size))
                }
                appendLine()
            }
        }
        println(report)
        writeReport("limits.txt", report)
    }

    private fun writeReport(name: String, text: String) {
        val dir = File("build/reports/detection")
        if (dir.mkdirs() || dir.isDirectory) File(dir, name).writeText(text)
    }

    @Test
    fun `negatives never fire across all rates and ranges including 25 and 400 Hz`() {
        val negatives = (REQUIRED + EXTRAS).filter { !it.expectsEvent }
        val results = cases(negatives, 0L..1L, { listOf(25, 50, 100, 200, 400) }, { listOf(4f, 8f, 16f) }).map { MatrixHarness.run(it) }
        val fired = results.filter { it.events.isNotEmpty() }
        assertTrue(fired.isEmpty(), fired.joinToString("\n") { it.describe() })
    }

    @Test
    fun `detection time stamps the impact within one sample period at 200 Hz`() {
        for (sc in listOf(Scenario.FALL, Scenario.PEDESTRIAN_IMPACT, Scenario.VEHICLE_CRASH)) {
            val r = MatrixHarness.run(MatrixCase(sc, 200, 16f, 0))
            val dt = (r.firstEvent!!.triggeredAtNanos - r.eventAtNanos!!) / 1e9
            val impactOffset = when (sc) {
                Scenario.FALL -> 0.375 // impact half-sine at te + 0.35 s, 50 ms
                Scenario.PEDESTRIAN_IMPACT -> 0.03 // bumper 60 ms from te
                else -> 0.06 // crash pulse 120 ms from te
            }
            assertTrue(abs(dt - impactOffset) < 0.04, "${sc.id}: t0 is ${dt}s after the event start, impact peak at $impactOffset s")
        }
    }
}
