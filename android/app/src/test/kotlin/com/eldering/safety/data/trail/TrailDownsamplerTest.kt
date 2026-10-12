package com.eldering.safety.data.trail

import com.eldering.safety.testing.FakeTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class TrailDownsamplerTest {

    private val t0 = FakeTime.T0
    private val lat0 = 52.5200
    private val lng0 = 13.4050

    /** ~1 m north/east steps at this latitude. */
    private val dLat = 1.0 / 111_195.0
    private val dLng = 1.0 / (111_195.0 * Math.cos(Math.toRadians(lat0)))

    private fun point(northM: Double, eastM: Double, seconds: Long) =
        TrailPoint(recordedAtMs = t0 + seconds * 1000, lat = lat0 + northM * dLat, lng = lng0 + eastM * dLng)

    @Test
    fun `returns the input untouched when it already fits`() {
        val points = List(10) { point(it * 10.0, 0.0, it.toLong()) }
        assertSame(points, TrailDownsampler.downsample(points, maxPoints = 10))
    }

    @Test
    fun `rejects a budget below start plus end`() {
        assertThrows(IllegalArgumentException::class.java) {
            TrailDownsampler.downsample(listOf(point(0.0, 0.0, 0)), maxPoints = 1)
        }
    }

    @Test
    fun `keeps start, end and the corner of an L-shaped walk`() {
        // 100 s north at 1 m/s, then 100 s east: 201 points, corner at index 100.
        val north = (0..100).map { point(it.toDouble(), 0.0, it.toLong()) }
        val east = (1..100).map { point(100.0, it.toDouble(), 100L + it) }
        val trail = north + east

        val reduced = TrailDownsampler.downsample(trail, maxPoints = 10)

        assertTrue("at most 10 points, got ${reduced.size}", reduced.size <= 10)
        assertEquals(trail.first(), reduced.first())
        assertEquals(trail.last(), reduced.last())
        assertTrue("corner kept", trail[100] in reduced)
        assertEquals("output stays in time order", reduced.sortedBy { it.recordedAtMs }, reduced)
    }

    @Test
    fun `a straight constant-speed line collapses to its endpoints`() {
        val line = (0..500).map { point(it * 2.0, it * 1.0, it.toLong()) }
        val reduced = TrailDownsampler.downsample(line, maxPoints = 50)
        assertEquals(listOf(line.first(), line.last()), reduced)
    }

    @Test
    fun `keeps every sharp turn of a zigzag when the budget allows`() {
        // Zigzag: 5 legs of 50 m alternating north-east / south-east -> 4 inner turns.
        val points = mutableListOf<TrailPoint>()
        var north = 0.0
        var east = 0.0
        var s = 0L
        val turnIndices = mutableListOf<Int>()
        for (leg in 0 until 5) {
            val dir = if (leg % 2 == 0) 1.0 else -1.0
            for (step in 0 until 50) {
                points += point(north, east, s++)
                north += dir
                east += 1.0
            }
            if (leg < 4) turnIndices += points.size // first point of the next leg is the apex
        }
        points += point(north, east, s)

        val reduced = TrailDownsampler.downsample(points, maxPoints = 8)

        assertTrue(reduced.size <= 8)
        assertEquals(points.first(), reduced.first())
        assertEquals(points.last(), reduced.last())
        turnIndices.forEach { i -> assertTrue("turn at $i kept", points[i] in reduced) }
    }

    @Test
    fun `time-aware mode keeps a long stop on a straight road`() {
        // Walk 100 m north in 100 s, stand still 600 s, walk 100 m more in 100 s.
        val before = (0..100).map { point(it.toDouble(), 0.0, it.toLong()) }
        val stop = (1..600).map { point(100.0, 0.0, 100L + it) }
        val after = (1..100).map { point(100.0 + it, 0.0, 700L + it) }
        val trail = before + stop + after

        val timeAware = TrailDownsampler.downsample(trail, maxPoints = 6)
        val spatialOnly = TrailDownsampler.downsample(trail, maxPoints = 6, timeAware = false)

        // The stop's start and end survive so the scrubber shows where the person waited.
        assertTrue(trail[100] in timeAware || trail[101] in timeAware)
        assertTrue(trail[700] in timeAware || trail[699] in timeAware)
        // Purely spatially the path is a straight line: just the endpoints.
        assertEquals(listOf(trail.first(), trail.last()), spatialOnly)
    }

    @Test
    fun `respects the budget on a long noisy trail`() {
        val random = java.util.Random(42)
        val trail = (0 until 3_900).map { i ->
            point(i * 1.4 + random.nextGaussian() * 5, Math.sin(i / 200.0) * 300 + random.nextGaussian() * 5, i.toLong())
        }
        val reduced = TrailDownsampler.downsample(trail, maxPoints = 600)
        assertEquals(600, reduced.size)
        assertEquals(trail.first(), reduced.first())
        assertEquals(trail.last(), reduced.last())
    }
}
