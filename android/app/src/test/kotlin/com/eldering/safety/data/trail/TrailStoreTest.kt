package com.eldering.safety.data.trail

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.cash.turbine.test
import com.eldering.safety.data.db.ElderingDatabase
import com.eldering.safety.network.Activity
import com.eldering.safety.testing.FakeTime
import com.eldering.safety.testing.inMemoryDatabase
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class TrailStoreTest {

    private lateinit var db: ElderingDatabase
    private val time = FakeTime()
    private lateinit var store: TrailStore

    private val everything = TrailSamplingPolicy(minIntervalMs = 0, minDistanceM = 0.0, maxIntervalMs = 0, maxAccuracyM = Float.MAX_VALUE)

    @Before
    fun setUp() {
        db = inMemoryDatabase()
        store = TrailStore(db.trailDao(), time)
    }

    @After
    fun tearDown() = db.close()

    private fun fix(atMs: Long, northM: Double = 0.0, accuracy: Float? = 8f, activity: Activity? = Activity.WALKING) =
        TrailPoint(recordedAtMs = atMs, lat = 48.137 + northM / 111_195.0, lng = 11.575, accuracyM = accuracy, activity = activity)

    @Test
    fun `prune keeps exactly the last 65 minutes`() = runTest {
        // One point per minute for two hours, recorded "live" as the clock advances.
        val start = time.nowMillis()
        for (minute in 0..120) {
            time.set(start + minute * 60_000L)
            store.record(fix(time.nowMillis(), northM = minute * 50.0), everything)
        }
        store.prune()

        val remaining = store.points(0, Long.MAX_VALUE)
        val oldestAllowed = time.nowMillis() - TrailStore.RETENTION_MS
        assertTrue(remaining.all { it.recordedAtMs >= oldestAllowed })
        assertEquals(66, remaining.size) // minutes 55..120 inclusive
        assertEquals(time.nowMillis(), remaining.last().recordedAtMs)
    }

    @Test
    fun `record prunes on its own at most once a minute`() = runTest {
        val start = time.nowMillis()
        store.record(fix(start), everything)
        time.set(start + 2 * 60 * 60_000L) // two hours later
        store.record(fix(time.nowMillis(), northM = 500.0), everything)

        assertEquals(1, store.count()) // the two-hour-old point was pruned by the second record()
    }

    @Test
    fun `capacity cap keeps only the newest points`() = runTest {
        val small = TrailStore(db.trailDao(), time, capacity = 50)
        val start = time.nowMillis()
        repeat(80) { i -> small.record(fix(start + i * 1_000L, northM = i * 30.0), everything) }
        small.prune()

        val remaining = small.points(0, Long.MAX_VALUE)
        assertEquals(50, remaining.size)
        assertEquals(start + 79_000L, remaining.last().recordedAtMs)
        assertEquals(start + 30_000L, remaining.first().recordedAtMs)
    }

    @Test
    fun `walking policy drops jitter, keeps movement and a keep-alive point`() = runTest {
        val t = time.nowMillis()
        assertTrue(store.record(fix(t), TrailSamplingPolicy.WALKING))
        assertFalse("5 m of jitter", store.record(fix(t + 10_000, northM = 5.0), TrailSamplingPolicy.WALKING))
        assertFalse("too soon", store.record(fix(t + 2_000, northM = 40.0), TrailSamplingPolicy.WALKING))
        assertTrue("moved 25 m", store.record(fix(t + 20_000, northM = 25.0), TrailSamplingPolicy.WALKING))
        assertFalse("inaccurate", store.record(fix(t + 30_000, northM = 80.0, accuracy = 400f), TrailSamplingPolicy.WALKING))
        assertTrue("keep-alive after 60 s", store.record(fix(t + 80_000, northM = 26.0), TrailSamplingPolicy.WALKING))
        assertFalse("duplicate timestamp", store.record(fix(t + 80_000, northM = 90.0), everything))
        assertFalse("out of order", store.record(fix(t + 50_000, northM = 90.0), everything))

        assertEquals(listOf(t, t + 20_000, t + 80_000), store.points(0, Long.MAX_VALUE).map { it.recordedAtMs })
    }

    @Test
    fun `alerting keeps every move at 1 s but not the same spot every second`() = runTest {
        // E2E: a rejected phone_drop candidate's 1 s fixes stored 11 identical points in 10 s.
        val t = time.nowMillis()
        assertTrue(store.record(fix(t, accuracy = 5f), TrailSamplingPolicy.ALERTING))
        for (i in 1..9) {
            assertFalse("identical fix after $i s", store.record(fix(t + i * 1_000L, accuracy = 5f), TrailSamplingPolicy.ALERTING))
        }
        assertFalse("2 m of GPS jitter", store.record(fix(t + 9_500, northM = 2.0, accuracy = 5f), TrailSamplingPolicy.ALERTING))
        assertTrue("keep-alive after 10 s", store.record(fix(t + 10_000, accuracy = 5f), TrailSamplingPolicy.ALERTING))
        assertTrue("a real move, 1 s later", store.record(fix(t + 11_000, northM = 6.0, accuracy = 5f), TrailSamplingPolicy.ALERTING))
        assertTrue("a much better fix of the same spot", store.record(fix(t + 12_000, northM = 6.0, accuracy = 2f), TrailSamplingPolicy.ALERTING))
        // A coarse fix needs to move further than its own uncertainty to count as a move.
        assertFalse(store.record(fix(t + 13_000, northM = 30.0, accuracy = 80f), TrailSamplingPolicy.ALERTING))
        assertEquals(4, store.count())
    }

    @Test
    fun `stale fixes outside retention are not stored`() = runTest {
        assertFalse(store.record(fix(time.nowMillis() - TrailStore.RETENTION_MS - 1), everything))
        assertNull(store.latest())
    }

    @Test
    fun `round-trips every field`() = runTest {
        val point = TrailPoint(
            recordedAtMs = time.nowMillis(),
            lat = -33.8688,
            lng = 151.2093,
            accuracyM = 4.5f,
            speedMps = 13.9f,
            bearingDeg = 271.5f,
            altitudeM = 58.0,
            activity = Activity.VEHICLE,
            elapsedRealtimeNanos = 123_456_789L,
        )
        store.record(point, everything)
        assertEquals(point, store.latest())
    }

    @Test
    fun `snapshot freezes the last hour downsampled to the budget`() = runTest {
        val start = time.nowMillis()
        // 90 minutes of 1 Hz driving with a turn every 10 minutes.
        for (s in 0 until 90 * 60) {
            val leg = s / 600
            val north = if (leg % 2 == 0) s % 600 * 15.0 else (600 - s % 600) * 15.0
            store.record(
                TrailPoint(start + s * 1000L, 48.0 + north / 111_195.0, 11.0 + s * 15.0 / 74_000.0, 5f, activity = Activity.VEHICLE),
                everything,
            )
        }
        time.set(start + 90 * 60_000L)
        val detectedAt = time.nowMillis() - 1_000

        val snapshot = store.snapshot(atMs = detectedAt, maxPoints = 200)

        assertTrue(snapshot.size <= 200)
        assertTrue(snapshot.first().recordedAtMs >= detectedAt - TrailStore.TRIP_WINDOW_MS)
        assertEquals(detectedAt, snapshot.last().recordedAtMs)
        assertEquals(snapshot.sortedBy { it.recordedAtMs }, snapshot)
    }

    @Test
    fun `observeRecent emits new points`() = runTest {
        store.observeRecent().test {
            assertEquals(emptyList<TrailPoint>(), awaitItem())
            store.record(fix(time.nowMillis()), everything)
            assertEquals(1, awaitItem().size)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `clear empties the trail and resets the sampler`() = runTest {
        val t = time.nowMillis()
        store.record(fix(t), TrailSamplingPolicy.WALKING)
        store.clear()
        assertEquals(0, store.count())
        // With the sampler reset, an immediate nearby fix is the first point again.
        assertTrue(store.record(fix(t + 1_000, northM = 1.0), TrailSamplingPolicy.WALKING))
    }
}
