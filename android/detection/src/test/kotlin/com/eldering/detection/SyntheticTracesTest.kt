package com.eldering.detection

import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.sqrt
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** The synthetic trace generator and replayer: determinism, sensor model and physical sanity. */
class SyntheticTracesTest {
    private val g = 9.80665f

    private fun accel(t: SyntheticTrace) = t.samples.filter { it.kind == SensorKind.ACCEL }
    private fun gyro(t: SyntheticTrace) = t.samples.filter { it.kind == SensorKind.GYRO }
    private fun smv(s: SensorSample) = sqrt(s.x * s.x + s.y * s.y + s.z * s.z) / g

    @Test
    fun `same spec gives the same trace, another seed a different one`() {
        val spec = TraceSpec(rateHz = 100, seed = 7)
        val a = SyntheticTraces.generate(Scenario.PEDESTRIAN_IMPACT, spec)
        val b = SyntheticTraces.generate("pedestrian_impact", spec)
        assertEquals(a, b)
        val c = SyntheticTraces.generate(Scenario.PEDESTRIAN_IMPACT, spec.copy(seed = 8))
        assertNotEquals(a.samples, c.samples)
    }

    @ParameterizedTest
    @EnumSource(Scenario::class)
    fun `every scenario generates a well-formed trace`(sc: Scenario) {
        val t = SyntheticTraces.generate(sc, TraceSpec(seed = 1))
        assertTrue(t.samples.isNotEmpty())
        for (kind in SensorKind.entries) {
            val ts = t.samples.filter { it.kind == kind }.map { it.tNanos }
            assertTrue(ts.zipWithNext().all { (p, q) -> q > p }, "$kind timestamps strictly increase")
        }
        assertTrue(t.samples.zipWithNext().all { (p, q) -> q.tNanos >= p.tNanos }, "merged stream is time ordered")
        assertTrue(t.samples.all { it.tNanos in t.startNanos..t.endNanos })
        assertEquals(sc.mode, t.modeChanges.first().mode)
        assertEquals(t.startNanos, t.modeChanges.first().tNanos)
        val maxAxis = accel(t).maxOf { maxOf(abs(it.x), abs(it.y), abs(it.z)) }
        assertTrue(maxAxis <= t.accelMaxRangeMs2 + 1e-3f, "never above the range")
        t.eventAtNanos?.let { at ->
            val post = if (sc.mode == Mode.VEHICLE) 15.0 else 10.0
            assertTrue((t.endNanos - at) / 1e9 >= post + 1.0, "room for the post-event window")
            assertTrue((at - t.startNanos) / 1e9 >= 3.0, "room for the pre-event window")
        }
    }

    @Test
    fun `timestamps follow the requested rate with jitter and rare drops`() {
        val t = SyntheticTraces.generate(Scenario.WALK, TraceSpec(rateHz = 50, seed = 3, jitterFraction = 0.02, dropProbability = 0.01))
        val dt = accel(t).map { it.tNanos }.zipWithNext { a, b -> (b - a) / 1e9 }
        val median = dt.sorted()[dt.size / 2]
        assertEquals(0.02, median, 0.0005)
        val drops = dt.count { it > 0.03 }
        val expected = dt.size * 0.01
        assertTrue(drops > expected * 0.4 && drops < expected * 2.0, "drops $drops vs ≈ $expected")
        val jitter = dt.filter { it < 0.03 }.map { it - 0.02 }
        val sd = sqrt(jitter.sumOf { it * it } / jitter.size)
        assertTrue(sd in 0.0002..0.0009, "jitter std $sd s (2 % of 20 ms ⇒ ≈0.57 ms between neighbours)")
    }

    @Test
    fun `phone at rest reads 1 g and a free fall reads near 0 g`() {
        val t = SyntheticTraces.generate(Scenario.PHONE_DROP, TraceSpec(rateHz = 100, seed = 2, randomizeTiming = false))
        val at = t.eventAtNanos!!
        val rest = accel(t).filter { it.tNanos < at - 1_000_000_000L }.map(::smv)
        assertEquals(1.0, rest.average(), 0.01)
        val fall = accel(t).filter { it.tNanos in (at + 100_000_000L)..(at + 400_000_000L) }.map(::smv)
        assertTrue(fall.max() < 0.3, "free fall plus 6 rad/s spin reads ≈ 0.11 g, got ${fall.max()}")
    }

    @Test
    fun `clipped samples sit exactly at full scale`() {
        val t = SyntheticTraces.generate(Scenario.VEHICLE_CRASH, TraceSpec(accelRangeG = 8f, seed = 4))
        val fullScale = 8 * g
        val clipped = accel(t).filter { maxOf(abs(it.x), abs(it.y), abs(it.z)) >= 0.97f * fullScale }
        assertTrue(clipped.size >= 3, "a 50 km/h crash clips a ±8 g sensor")
        assertTrue(clipped.any { s -> listOf(s.x, s.y, s.z).any { abs(abs(it) - fullScale) < 1e-3f } })
    }

    @Test
    fun `gyro integrates to the same rotation the gravity direction shows`() {
        val t = SyntheticTraces.generate(Scenario.FALL_FROM_STANDING_STILL, TraceSpec(rateHz = 200, seed = 5))
        val at = t.eventAtNanos!!
        fun meanG(from: Long, to: Long) = accel(t).filter { it.tNanos in from..to }.let { s ->
            doubleArrayOf(s.sumOf { it.x.toDouble() }, s.sumOf { it.y.toDouble() }, s.sumOf { it.z.toDouble() })
        }
        val before = meanG(at - 2_000_000_000L, at - 500_000_000L)
        val after = meanG(at + 3_000_000_000L, at + 5_000_000_000L)
        val tilt = Math.toDegrees(acos(((before[0] * after[0] + before[1] * after[1] + before[2] * after[2]) /
            (sqrt(before.sumOf { it * it }) * sqrt(after.sumOf { it * it }))).coerceIn(-1.0, 1.0)))
        val gy = gyro(t).filter { it.tNanos in (at - 500_000_000L)..(at + 1_500_000_000L) }
        val tg = DoubleArray(gy.size) { (gy[it].tNanos - at) / 1e9 }
        val net = FeatureMath.maxNetRotationDeg(tg, DoubleArray(gy.size) { gy[it].x.toDouble() }, DoubleArray(gy.size) { gy[it].y.toDouble() },
            DoubleArray(gy.size) { gy[it].z.toDouble() }, tg.first(), tg.last())
        assertTrue(tilt > 60.0, "falls to the ground: $tilt°")
        assertEquals(tilt, net, 6.0)
    }

    @Test
    fun `optional channels and gaps`() {
        val noGyro = SyntheticTraces.generate(Scenario.FALL, TraceSpec(withGyro = false))
        assertTrue(gyro(noGyro).isEmpty())
        assertTrue(noGyro.samples.none { it.kind == SensorKind.PRESSURE }, "no barometer unless asked")

        val baro = SyntheticTraces.generate(Scenario.FALL_WITH_BARO, TraceSpec(seed = 1))
        val at = baro.eventAtNanos!!
        val p = baro.samples.filter { it.kind == SensorKind.PRESSURE }
        val before = p.filter { it.tNanos < at }.map { it.x.toDouble() }.average()
        val after = p.filter { it.tNanos > at + 2_000_000_000L }.map { it.x.toDouble() }.average()
        assertEquals(0.078, after - before, 0.004, "0.65 m lower ⇒ +0.078 hPa")

        val gap = SyntheticTraces.generate(Scenario.FALL_WITH_GAP, TraceSpec(seed = 1))
        val longest = accel(gap).map { it.tNanos }.zipWithNext { a, b -> b - a }.max()
        assertTrue(longest >= 110_000_000L, "120 ms hole, got ${longest / 1e6} ms")
    }

    @Test
    fun `random orientation keeps the carrying position, arbitrary mounting does not`() {
        fun uprightDeg(spec: TraceSpec): Double {
            val t = SyntheticTraces.generate(Scenario.WALK, spec)
            val s = accel(t).take(500)
            val gx = s.sumOf { it.x.toDouble() }
            val gy = s.sumOf { it.y.toDouble() }
            val gz = s.sumOf { it.z.toDouble() }
            return Math.toDegrees(acos(abs(gy) / sqrt(gx * gx + gy * gy + gz * gz)))
        }
        val pocket = (0L until 8L).map { uprightDeg(TraceSpec(seed = it)) }
        assertTrue(pocket.all { it < 25.0 }, "pocket phone stays roughly upright: $pocket")
        val anyWay = (0L until 8L).map { uprightDeg(TraceSpec(seed = it, arbitraryMounting = true)) }
        assertTrue(anyWay.any { it > 30.0 }, "arbitrary mounting tilts it: $anyWay")
        val heading = (0L until 4L).map { SyntheticTraces.generate(Scenario.WALK, TraceSpec(seed = it)).samples[0] }
        assertTrue(heading.map { it.x }.distinct().size > 1, "orientation differs between seeds")
    }

    @Test
    fun `scenario ids are unique and looked up by id`() {
        val ids = SyntheticTraces.ids
        assertEquals(ids.size, ids.toSet().size)
        for (sc in Scenario.entries) assertEquals(sc, Scenario.fromId(sc.id))
        val e = assertFailsWith<IllegalArgumentException> { Scenario.fromId("nope") }
        assertTrue(e.message!!.contains("fall"))
        assertFailsWith<IllegalArgumentException> { TraceSpec(rateHz = 5) }
    }

    @Test
    fun `replaying at another start time shifts the event by the same amount`() {
        val t = SyntheticTraces.generate(Scenario.FALL, TraceSpec(seed = 3))
        val direct = TraceReplayer.run(t).events.single()
        val shifted = mutableListOf<DetectionEvent>()
        val d = AccidentDetector { shifted += it }
        val start = 987_654_321_000_000L
        TraceReplayer.replay(t, d, startAtNanos = start)
        assertEquals(direct.triggeredAtNanos - t.startNanos, shifted.single().triggeredAtNanos - start)
        assertEquals(direct.kind, shifted.single().kind)
        assertContentEquals(direct.features.keys.sorted(), shifted.single().features.keys.sorted())
        val now = 5_000_000_000_000L
        assertEquals(now, TraceReplayer.startForEndingAt(t, now) + (t.endNanos - t.startNanos))
    }
}
