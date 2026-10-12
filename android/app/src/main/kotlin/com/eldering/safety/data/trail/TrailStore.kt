package com.eldering.safety.data.trail

import com.eldering.safety.core.TimeSource
import com.eldering.safety.data.db.TrailDao
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The on-device location ring buffer behind "Trip to past" and incident uploads (SPEC: Room, 65 min).
 *
 * - [record] applies a [TrailSamplingPolicy] and inserts; it prunes at most once per [pruneIntervalMs].
 * - Retention is time-based ([retentionMs]); [capacity] is a hard row cap in case the clock misbehaves.
 * - [snapshot] "freezes" the last hour for an incident, downsampled to an upload-friendly size.
 *
 * Thread-safe; all functions are main-safe (Room suspend DAOs).
 */
class TrailStore(
    private val dao: TrailDao,
    private val time: TimeSource = TimeSource.System,
    private val retentionMs: Long = RETENTION_MS,
    private val capacity: Int = CAPACITY,
    private val pruneIntervalMs: Long = PRUNE_INTERVAL_MS,
) {
    init {
        require(retentionMs > 0 && capacity > 0)
    }

    private val mutex = Mutex()
    private var lastKept: TrailPoint? = null
    private var lastKeptLoaded = false
    private var lastPruneAtMs = Long.MIN_VALUE

    /**
     * Offers a fix. Returns true if it was stored (it passed [policy], is within retention and is not a duplicate).
     */
    suspend fun record(point: TrailPoint, policy: TrailSamplingPolicy = TrailSamplingPolicy.forActivity(point.activity)): Boolean =
        mutex.withLock {
            val now = time.nowMillis()
            if (!lastKeptLoaded) {
                lastKept = dao.latest()?.toTrailPoint()
                lastKeptLoaded = true
            }
            val stored = point.recordedAtMs >= now - retentionMs &&
                policy.shouldKeep(lastKept, point) &&
                dao.insert(point.toEntity()) != -1L
            if (stored) lastKept = point
            if (lastPruneAtMs == Long.MIN_VALUE || now - lastPruneAtMs >= pruneIntervalMs) pruneLocked(now)
            stored
        }

    /** Deletes points older than the retention window and enforces the capacity cap. Returns rows deleted. */
    suspend fun prune(): Int = mutex.withLock { pruneLocked(time.nowMillis()) }

    private suspend fun pruneLocked(now: Long): Int {
        val cutoff = now - retentionMs
        val deleted = dao.deleteOlderThan(cutoff) + dao.trimToNewest(capacity)
        lastPruneAtMs = now
        if (deleted > 0) {
            lastKept = dao.latest()?.toTrailPoint()
        }
        return deleted
    }

    /** Points with `fromMs <= recordedAt <= toMs`, oldest first. */
    suspend fun points(fromMs: Long, toMs: Long): List<TrailPoint> = dao.between(fromMs, toMs).map { it.toTrailPoint() }

    /** The last [windowMs] (default: one hour, the "Trip to past" window), oldest first. */
    suspend fun recent(windowMs: Long = TRIP_WINDOW_MS): List<TrailPoint> {
        val now = time.nowMillis()
        return points(now - windowMs, Long.MAX_VALUE)
    }

    /**
     * Live view of the points recorded since `now - windowMs` (the window start is fixed at subscription time;
     * re-subscribe to slide it).
     */
    fun observeRecent(windowMs: Long = TRIP_WINDOW_MS): Flow<List<TrailPoint>> =
        dao.observeSince(time.nowMillis() - windowMs).map { rows -> rows.map { it.toTrailPoint() } }

    suspend fun latest(): TrailPoint? = dao.latest()?.toTrailPoint()

    suspend fun count(): Int = dao.count()

    /**
     * The trail for an incident detected at [atMs]: the preceding [windowMs] up to [untilMs], downsampled to at
     * most [maxPoints] (start, end and turns preserved). Copy it into the incident upload right away; the ring
     * buffer keeps pruning.
     */
    suspend fun snapshot(
        atMs: Long,
        windowMs: Long = TRIP_WINDOW_MS,
        untilMs: Long = atMs,
        maxPoints: Int = INCIDENT_MAX_POINTS,
    ): List<TrailPoint> = TrailDownsampler.downsample(points(atMs - windowMs, untilMs), maxPoints)

    /** Deletes the whole trail (e.g. the user asked to wipe location history). */
    suspend fun clear() = mutex.withLock {
        dao.clear()
        lastKept = null
    }

    companion object {
        /** SPEC: 65 min retention (60 min trip window + margin). */
        const val RETENTION_MS: Long = 65 * 60_000L
        const val TRIP_WINDOW_MS: Long = 60 * 60_000L
        /** ≈2.7 h at 1 Hz; retention normally prunes long before this. */
        const val CAPACITY: Int = 10_000
        const val PRUNE_INTERVAL_MS: Long = 60_000L
        /** Incident uploads: far below the server's 5000 points and 1 MB body (~100 B per point). */
        const val INCIDENT_MAX_POINTS: Int = 600
    }
}
