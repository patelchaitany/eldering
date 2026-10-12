package com.eldering.safety.data.trail

import java.util.PriorityQueue
import kotlin.math.hypot

/**
 * Reduces a trail to at most N points while keeping its shape: the first and last points always survive, and
 * the remaining budget goes to the points that deviate most from the simplified line (turns, U-turns, stops).
 *
 * Algorithm: top-down greedy Douglas–Peucker with a point budget. Starting from the segment first→last, it
 * repeatedly inserts the point with the largest error across all current segments, until [maxPoints] points are
 * kept or no point deviates more than the tolerance. The error is the *synchronized Euclidean distance* (distance
 * to where the simplified track says the person was at that time) when `timeAware`, which also keeps stops and
 * speed changes so the "Trip to past" scrubber stays truthful; otherwise plain distance to the segment.
 *
 * O(n log n) typical, O(n²) worst case; fine for a one-hour trail (a few thousand points).
 */
object TrailDownsampler {

    /** Errors below this are GPS noise: no point is kept for them. */
    const val DEFAULT_TOLERANCE_M = 1.0

    fun downsample(
        points: List<TrailPoint>,
        maxPoints: Int,
        toleranceM: Double = DEFAULT_TOLERANCE_M,
        timeAware: Boolean = true,
    ): List<TrailPoint> = downsample(points, maxPoints, toleranceM, timeAware, { it.lat }, { it.lng }, { it.recordedAtMs })

    /**
     * Generic form for any point type (e.g. server trails in the UI). [points] must be in time order.
     * Returns [points] itself if it already has at most [maxPoints] elements.
     */
    fun <T> downsample(
        points: List<T>,
        maxPoints: Int,
        toleranceM: Double,
        timeAware: Boolean,
        lat: (T) -> Double,
        lng: (T) -> Double,
        timeMs: (T) -> Long,
    ): List<T> {
        require(maxPoints >= 2) { "maxPoints must be at least 2 (start and end), was $maxPoints" }
        require(toleranceM >= 0) { "toleranceM must not be negative" }
        val n = points.size
        if (n <= maxPoints) return points

        val projection = LocalProjection(lat(points[0]), lng(points[0]))
        val xs = DoubleArray(n) { projection.x(lng(points[it])) }
        val ys = DoubleArray(n) { projection.y(lat(points[it])) }
        val ts = LongArray(n) { timeMs(points[it]) }

        fun error(k: Int, a: Int, b: Int): Double {
            val dx = xs[b] - xs[a]
            val dy = ys[b] - ys[a]
            val f = if (timeAware && ts[b] > ts[a]) {
                ((ts[k] - ts[a]).toDouble() / (ts[b] - ts[a])).coerceIn(0.0, 1.0)
            } else {
                val len2 = dx * dx + dy * dy
                if (len2 == 0.0) 0.0 else (((xs[k] - xs[a]) * dx + (ys[k] - ys[a]) * dy) / len2).coerceIn(0.0, 1.0)
            }
            return hypot(xs[k] - (xs[a] + f * dx), ys[k] - (ys[a] + f * dy))
        }

        class Span(val start: Int, val end: Int, val pivot: Int, val error: Double)

        val queue = PriorityQueue(compareByDescending<Span> { it.error }.thenBy { it.pivot })
        fun enqueue(a: Int, b: Int) {
            if (b - a < 2) return
            var best = a + 1
            var bestError = -1.0
            for (k in a + 1 until b) {
                val e = error(k, a, b)
                if (e > bestError) {
                    bestError = e
                    best = k
                }
            }
            queue.add(Span(a, b, best, bestError))
        }

        val keep = BooleanArray(n)
        keep[0] = true
        keep[n - 1] = true
        var kept = 2
        enqueue(0, n - 1)
        while (kept < maxPoints) {
            val span = queue.poll() ?: break
            if (span.error <= toleranceM) break
            keep[span.pivot] = true
            kept++
            enqueue(span.start, span.pivot)
            enqueue(span.pivot, span.end)
        }
        return points.filterIndexed { i, _ -> keep[i] }
    }
}
