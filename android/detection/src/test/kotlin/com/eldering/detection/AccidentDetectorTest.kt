package com.eldering.detection

import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Streaming behaviour of [AccidentDetector]: API contract, timing, cooldown, robustness to real sensor delivery. */
class AccidentDetectorTest {

    private val events = mutableListOf<DetectionEvent>()
    private val detector = AccidentDetector(listener = events::add)

    private fun trace(sc: Scenario, rate: Int? = null, range: Float? = null, seed: Long = 0, gyro: Boolean = true) =
        SyntheticTraces.generate(sc, TraceSpec(rateHz = rate, accelRangeG = range, seed = seed, withGyro = gyro))

    private fun near(event: DetectionEvent, trace: SyntheticTrace): Boolean =
        (event.triggeredAtNanos - trace.eventAtNanos!!) / 1e9 in -0.5..2.0

    @Test
    fun `phone at rest for ten seconds emits nothing`() {
        detector.setAccelMaxRange(16 * G)
        detector.onMode(Mode.STILL, tNanos = 0)
        val periodNanos = 1_000_000_000L / 25 // 25 Hz, the STILL tier
        repeat(250) { i -> detector.onSample(SensorSample(i * periodNanos, SensorKind.ACCEL, 0f, 0f, G)) }
        assertTrue(events.isEmpty(), "expected no events, got $events")
    }

    @Test
    fun `tracks mode and valid speed, ignores missing speed fixes`() {
        detector.onMode(Mode.VEHICLE, tNanos = 5)
        detector.onSpeed(12.5f, tNanos = 10)
        detector.onSpeed(Float.NaN, tNanos = 20)
        detector.onSpeed(-1f, tNanos = 30)
        assertEquals(Mode.VEHICLE, detector.mode)
        assertEquals(12.5f, detector.lastSpeedMps)
        assertEquals(10L, detector.lastSpeedAtNanos)
    }

    @ParameterizedTest(name = "range {0} m/s²")
    @ValueSource(floats = [0f, -9.8f, Float.NaN, Float.POSITIVE_INFINITY])
    fun `rejects an invalid accelerometer range`(range: Float) {
        assertFailsWith<IllegalArgumentException> { detector.setAccelMaxRange(range) }
        assertNull(detector.accelMaxRangeMs2)
    }

    @Test
    fun `default config has thresholds for every mode and validates itself`() {
        val config = DetectorConfig()
        Mode.entries.forEach { mode -> config.thresholdsFor(mode) }
        assertEquals(100, config.thresholdsFor(Mode.VEHICLE).nominalRateHz)
        assertFailsWith<IllegalArgumentException> { DetectorConfig(modes = DEFAULT_MODES - Mode.CYCLING) }
        assertFailsWith<IllegalArgumentException> { DetectorConfig(preWindowMs = 500) }
        assertFailsWith<IllegalArgumentException> { DetectorConfig(gridHz = 10) }
    }

    @Test
    fun `a fall is emitted once, after the post-event window, with features and a reason`() {
        val t = trace(Scenario.FALL)
        var lastSampleAtEmit = 0L
        var lastSample = 0L
        val d = AccidentDetector { events += it; lastSampleAtEmit = lastSample }
        d.setAccelMaxRange(t.accelMaxRangeMs2)
        val ctx = (t.modeChanges.map { it.tNanos to { d.onMode(it.mode, it.tNanos) } } + t.speedFixes.map { it.tNanos to { d.onSpeed(it.speedMps, it.tNanos) } })
            .sortedBy { it.first }
        var next = 0
        for (s in t.samples) {
            while (next < ctx.size && ctx[next].first <= s.tNanos) ctx[next++].second()
            lastSample = s.tNanos
            d.onSample(s)
        }
        assertEquals(1, events.size, "events: $events")
        val e = events.single()
        assertEquals(EventKind.FALL, e.kind)
        assertTrue(e.confidence >= 0.8f)
        assertTrue(near(e, t), "t0 ${(e.triggeredAtNanos - t.eventAtNanos!!) / 1e9} s from the event")
        assertTrue(lastSampleAtEmit >= e.triggeredAtNanos + 10_000_000_000L, "emitted only once t0 + W was sampled")
        assertTrue(lastSampleAtEmit < e.triggeredAtNanos + 10_100_000_000L, "and promptly then")
        for (key in listOf(FeatureKeys.PEAK_G, FeatureKeys.IMPACTS_1S, FeatureKeys.FREEFALL_MS, FeatureKeys.ROTATION_DEG, FeatureKeys.ORIENTATION_CHANGE_DEG,
            FeatureKeys.STILLNESS_VAR, FeatureKeys.SPEED_BEFORE, FeatureKeys.SPEED_AFTER, FeatureKeys.FALL_SCORE, FeatureKeys.MODE)) {
            assertTrue(key in e.features, "missing $key in ${e.features.keys}")
        }
        val parts = e.features.filterKeys { it.startsWith(FeatureKeys.CONFIDENCE_PART_PREFIX) }
        assertTrue(parts.size >= 4, "confidence parts: $parts")
        assertEquals(e.confidence, parts.values.sum(), 1e-4f, "parts add up to the confidence")
        assertTrue(e.reason.startsWith("Fall:"), e.reason)
    }

    @Test
    fun `cooldown suppresses a second event within 60 s but not after it`() {
        val t = trace(Scenario.FALL)
        TraceReplayer.replay(t, detector)
        TraceReplayer.replay(t, detector, startAtNanos = t.startNanos + 30_000_000_000L)
        assertEquals(1, events.size, "second fall 30 s later is in the cooldown")
        TraceReplayer.replay(t, detector, startAtNanos = t.startNanos + 90_000_000_000L)
        assertEquals(2, events.size, "fall 90 s later is reported")
    }

    @Test
    fun `candidate observer sees rejected candidates with their verdict`() {
        val outcomes = mutableListOf<CandidateOutcome>()
        detector.candidateObserver = { outcomes += it }
        TraceReplayer.replay(trace(Scenario.PHONE_DROP), detector)
        assertTrue(events.isEmpty())
        val analysed = outcomes.filter { it.features.isNotEmpty() }
        assertTrue(analysed.isNotEmpty())
        assertTrue(analysed.all { it.kind == null && it.confidence == 0f })
        assertEquals("phone_drop", analysed.first().verdict)
        assertTrue(FeatureKeys.TRUE_FREEFALL_MS in analysed.first().features)
    }

    @Test
    fun `a running stride just before a fall does not take the fall's place`() {
        // At 200 Hz the stride ~2 s before the fall passes the jerk vote; its posture window shows the fall.
        val t = trace(Scenario.RUNNING_FALL, rate = 200, range = 8f)
        val r = TraceReplayer.run(t)
        val e = r.events.single()
        assertEquals(EventKind.FALL, e.kind)
        assertTrue(near(e, t), "t0 ${(e.triggeredAtNanos - t.eventAtNanos!!) / 1e9} s from the fall")
    }

    @Test
    fun `duplicate, out-of-order and non-finite samples are dropped`() {
        val t = trace(Scenario.VEHICLE_CRASH)
        val clean = TraceReplayer.run(t).events.single()
        val noisy = ArrayList<SensorSample>(t.samples.size + 300)
        for ((i, s) in t.samples.withIndex()) {
            noisy += s
            if (i % 97 == 0) noisy += s // duplicate
            if (i % 131 == 0 && i > 0) noisy += t.samples[i - 1] // late (out of order)
            if (i % 151 == 0) noisy += s.copy(x = Float.NaN)
        }
        val r = TraceReplayer.run(t.copy(samples = noisy))
        val e = r.events.single()
        assertEquals(clean.kind, e.kind)
        assertEquals(clean.triggeredAtNanos, e.triggeredAtNanos)
        assertEquals(clean.confidence, e.confidence)
    }

    @Test
    fun `sensor batching (accel then gyro per 10 s batch) gives the same result`() {
        for (sc in listOf(Scenario.VEHICLE_CRASH, Scenario.PEDESTRIAN_IMPACT, Scenario.FALL)) {
            val t = trace(sc)
            val direct = TraceReplayer.run(t).events.single()
            val batched = TraceReplayer.run(t, batchMs = 10_000).events.single()
            assertEquals(direct.kind, batched.kind, sc.id)
            assertEquals(direct.triggeredAtNanos, batched.triggeredAtNanos, sc.id)
            assertEquals(direct.confidence, batched.confidence, sc.id)
        }
    }

    @Test
    fun `mode and speed reports delivered up front are applied by timestamp`() {
        val t = trace(Scenario.VEHICLE_CRASH)
        detector.setAccelMaxRange(t.accelMaxRangeMs2)
        t.modeChanges.forEach { detector.onMode(it.mode, it.tNanos) } // includes the later VEHICLE→STILL change
        t.speedFixes.forEach { detector.onSpeed(it.speedMps, it.tNanos) }
        t.samples.forEach(detector::onSample)
        assertEquals(EventKind.VEHICLE_CRASH, events.single().kind)
    }

    @Test
    fun `the app raising the rate to 200 Hz with gyro after the trigger is handled`() {
        // STILL tier: 25 Hz accel only; after the trigger the app switches to 200 Hz accel + gyro.
        val slow = trace(Scenario.FALL_FROM_STANDING_STILL, rate = 25, gyro = false)
        val fast = trace(Scenario.FALL_FROM_STANDING_STILL, rate = 200)
        val switchAt = slow.eventAtNanos!! + 600_000_000L // just after the impact triggered
        val merged = (slow.samples.filter { it.tNanos < switchAt } + fast.samples.filter { it.tNanos >= switchAt }).sortedBy { it.tNanos }
        val r = TraceReplayer.run(slow.copy(samples = merged))
        val e = r.events.single()
        assertEquals(EventKind.FALL, e.kind)
        assertTrue(abs(e.features.getValue(FeatureKeys.RATE_HZ) - 25f) < 3f, "rate is measured at the impact: ${e.features[FeatureKeys.RATE_HZ]}")
        assertTrue(FeatureKeys.PRE_ROTATION_DEG !in e.features, "no gyro before the trigger: pre-impact rotation is unknown, not 0")
    }

    @Test
    fun `range reported too low is raised from the readings`() {
        detector.setAccelMaxRange(4 * G)
        detector.onSample(SensorSample(1, SensorKind.ACCEL, 0f, 0f, G))
        detector.onSample(SensorSample(2, SensorKind.ACCEL, 6.5f * G, 0f, G))
        assertEquals(8.0, detector.effectiveRangeG, 1e-6)
        assertEquals(4 * G, detector.accelMaxRangeMs2!!, 1e-3f, "the app's value is kept for reference")
    }

    @Test
    fun `clipping below the reported range is inferred from a plateau`() {
        // Sensor reports ±16 g but clips at ±8 g: the 50 km/h crash still has to be recognised as clipped.
        val t = trace(Scenario.VEHICLE_CRASH, range = 8f)
        val d = AccidentDetector(listener = events::add)
        d.setAccelMaxRange(16 * G)
        TraceReplayer.replay(t, d, applyAccelRange = false)
        assertEquals(EventKind.VEHICLE_CRASH, events.single().kind)
        assertEquals(8.0, d.effectiveRangeG, 0.01)
        assertTrue(events.single().features.getValue(FeatureKeys.SATURATED_MS) >= 20f)
        val fresh = AccidentDetector(listener = {})
        fresh.setAccelMaxRange(16 * G)
        repeat(3) { fresh.onSample(SensorSample(10L + it, SensorKind.ACCEL, -8 * G, 2f, G)) }
        assertEquals(8.0, fresh.effectiveRangeG, 0.01)
        val smooth = AccidentDetector(listener = {})
        smooth.setAccelMaxRange(16 * G)
        listOf(7.9f, 7.95f, 7.97f, 7.95f).forEachIndexed { i, g -> smooth.onSample(SensorSample(10L + i, SensorKind.ACCEL, g * G, 0f, G)) }
        assertEquals(16.0, smooth.effectiveRangeG, 0.01, "a smooth peak is not a clip plateau")
    }

    @Test
    fun `memory stays bounded over a long stream`() {
        val d = AccidentDetector(listener = events::add)
        d.setAccelMaxRange(8 * G)
        val hz = 200
        val step = 1_000_000_000L / hz
        val rng = java.util.Random(3)
        var t = 5_000_000_000L
        repeat(10 * 60 * hz) { i -> // 10 minutes of brisk activity at 200 Hz, a trigger roughly every second
            val phase = i.toDouble() / hz
            val bump = if (i % hz < 4) 1.5f else 0f
            val z = (1.0 + 0.3 * sin(2 * PI * 1.8 * phase) + 0.05 * rng.nextGaussian()).toFloat() + bump
            d.onSample(SensorSample(t, SensorKind.ACCEL, 0.1f * G, 0f, z * G))
            d.onSample(SensorSample(t + 1, SensorKind.GYRO, 0.2f, 0f, 0f))
            if (i % hz == 0) {
                d.onSpeed(1.4f, t)
                d.onMode(if (i % (20 * hz) == 0) Mode.WALKING else Mode.UNKNOWN, t)
            }
            t += step
        }
        val retentionSamples = (2 + 15 + 3.5 + 0.5 + 2) * hz
        assertTrue(d.bufferedAccelSamples <= retentionSamples + 2, "accel ring holds ${d.bufferedAccelSamples}")
        assertTrue(d.bufferedGyroSamples <= retentionSamples + 2, "gyro ring holds ${d.bufferedGyroSamples}")
        assertTrue(d.pendingCandidates < 40, "pending ${d.pendingCandidates}")
        assertTrue(d.contextEntries < 400, "mode + speed history ${d.contextEntries}")
    }

    @Test
    fun `possible-accident hook fires at the candidate, long before the verdict`() {
        val t = trace(Scenario.FALL)
        val hooks = mutableListOf<Long>()
        detector.possibleAccidentListener = { hooks += it }
        TraceReplayer.replay(t, detector)
        val e = events.single()
        assertTrue(e.triggeredAtNanos in hooks, "hook fired for the emitted candidate")
    }

    @Test
    fun `a stream that ends inside the post-event window emits nothing yet`() {
        val t = trace(Scenario.FALL)
        val cut = t.eventAtNanos!! + 5_000_000_000L
        val r = TraceReplayer.run(t.copy(samples = t.samples.filter { it.tNanos < cut }))
        assertTrue(r.events.isEmpty())
    }

    private companion object {
        const val G = 9.80665f
    }
}
