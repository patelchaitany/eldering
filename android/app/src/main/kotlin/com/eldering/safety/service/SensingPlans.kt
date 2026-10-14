package com.eldering.safety.service

import com.eldering.detection.Mode
import com.eldering.safety.network.Activity
import kotlin.math.sqrt

/**
 * Sensor registration for one movement mode (SPEC "Battery"; docs/research/android-background.md §4.5).
 *
 * @property accelPeriodUs accelerometer sampling period in µs (always ≥ 4: 0–3 are the SENSOR_DELAY_* constants).
 * @property gyroPeriodUs gyroscope period in µs, 0 = gyroscope off.
 * @property pressurePeriodUs barometer period in µs, 0 = off. Optional: many phones have no barometer.
 * @property maxLatencyUs `maxReportLatencyUs` batching (0 = deliver every sample as it comes).
 */
data class SensingConfig(
    val tier: Tier,
    val accelPeriodUs: Int,
    val gyroPeriodUs: Int,
    val pressurePeriodUs: Int,
    val maxLatencyUs: Int,
) {
    init {
        require(accelPeriodUs >= MIN_PERIOD_US) { "accelPeriodUs must be a period in µs, not a SENSOR_DELAY constant" }
        require(gyroPeriodUs == 0 || gyroPeriodUs >= MIN_PERIOD_US)
        require(pressurePeriodUs == 0 || pressurePeriodUs >= MIN_PERIOD_US)
        require(maxLatencyUs >= 0)
    }

    val accelHz: Int get() = hz(accelPeriodUs)
    val gyroHz: Int get() = if (gyroPeriodUs == 0) 0 else hz(gyroPeriodUs)
    val pressureHz: Int get() = if (pressurePeriodUs == 0) 0 else hz(pressurePeriodUs)

    /** SPEC battery tiers. */
    enum class Tier { STILL, WALKING, VEHICLE, ALERT }

    private fun hz(periodUs: Int): Int = Math.round(1_000_000.0 / periodUs).toInt()

    companion object {
        const val MIN_PERIOD_US = 4
    }
}

/** Fused-location request parameters (mapped to `com.google.android.gms.location.LocationRequest` by the service). */
data class LocationPlan(
    /** `com.google.android.gms.location.Priority` value. */
    val priority: Int,
    val intervalMs: Long,
    val minIntervalMs: Long,
    val maxDelayMs: Long,
    val minDistanceM: Float = 0f,
) {
    companion object {
        // Values of com.google.android.gms.location.Priority (kept here so this file stays plain Kotlin).
        const val PRIORITY_HIGH_ACCURACY = 100
        const val PRIORITY_BALANCED_POWER_ACCURACY = 102
        const val PRIORITY_LOW_POWER = 104
        const val PRIORITY_PASSIVE = 105
    }
}

/**
 * Pure selection of sensor rates, batching and location requests per mode (SPEC "Battery"):
 *
 * | Mode | Sensors | Location |
 * |---|---|---|
 * | STILL | accel 25 Hz, batched ≈10 s; significant motion armed | passive only |
 * | WALKING / RUNNING / UNKNOWN | accel + gyro 50 Hz, batched 5 s | balanced, every 30 s |
 * | CYCLING | accel + gyro 50 Hz, batched 5 s (the cycling rules are calibrated for 50 Hz) | balanced, every 20 s |
 * | VEHICLE | accel + gyro 100 Hz, batched 2 s | high accuracy, every 10 s (min 5 s) |
 * | possible accident / countdown / sending | accel + gyro 200 Hz (the no-permission cap), no batching | high accuracy, every 1 s |
 * | sent, live updates running | the mode's sensors | high accuracy, every 5 s (live pushes are every 15 s) |
 *
 * Decisions (see docs/research/android-background.md §0):
 * - Location requests are never distance-filtered: their callbacks also pace sensor FIFO drains (interval ≤ ½ the
 *   FIFO depth). The SPEC's 20 m walking filter is applied by `TrailSamplingPolicy` when the trail stores a fix.
 * - VEHICLE keeps HIGH_ACCURACY at the SPEC's upper bound of 10 s: the detector's vehicle context and speed before a
 *   crash need GNSS speed, which balanced fixes often lack. The 1 s fixes for the speed after an impact come with
 *   the possible-accident hold.
 * - STILL has no location wake-ups; its FIFO is drained by significant motion, a wake-up accelerometer where one
 *   exists, or the keep-alive alarm at [stillDrainIntervalMs] (≤ ½ the FIFO depth).
 * - Battery saver doubles batching latency and location intervals in STILL/WALKING and drops the barometer; the
 *   vehicle and alert tiers stay as they are (a crash must still be caught).
 * - The barometer, when present, runs at 5 Hz in every tier except STILL under battery saver (falls from standing
 *   still use its height delta).
 */
object SensingPlans {
    private const val US_PER_S = 1_000_000

    /** Without a known FIFO depth (the emulator reports 0) the STILL tier is drained this often. */
    const val DEFAULT_STILL_DRAIN_MS = 60_000L
    const val MIN_STILL_DRAIN_MS = 30_000L
    const val MAX_STILL_DRAIN_MS = 5 * 60_000L

    /** Accelerometer rate of the STILL tier. */
    const val STILL_ACCEL_HZ = 25

    fun tierFor(mode: Mode, alerting: Boolean): SensingConfig.Tier = when {
        alerting -> SensingConfig.Tier.ALERT
        mode == Mode.VEHICLE -> SensingConfig.Tier.VEHICLE
        mode == Mode.STILL -> SensingConfig.Tier.STILL
        else -> SensingConfig.Tier.WALKING // WALKING, RUNNING, CYCLING, UNKNOWN
    }

    fun sensing(mode: Mode, alerting: Boolean, batterySaver: Boolean, hasGyro: Boolean = true, hasPressure: Boolean = false): SensingConfig {
        val tier = tierFor(mode, alerting)
        val base = when (tier) {
            SensingConfig.Tier.STILL -> SensingConfig(tier, periodUs(STILL_ACCEL_HZ), 0, periodUs(5), 10 * US_PER_S)
            SensingConfig.Tier.WALKING -> SensingConfig(tier, periodUs(50), periodUs(50), periodUs(5), 5 * US_PER_S)
            SensingConfig.Tier.VEHICLE -> SensingConfig(tier, periodUs(100), periodUs(100), periodUs(5), 2 * US_PER_S)
            SensingConfig.Tier.ALERT -> SensingConfig(tier, periodUs(200), periodUs(200), periodUs(5), 0)
        }
        val saver = batterySaver && (tier == SensingConfig.Tier.STILL || tier == SensingConfig.Tier.WALKING) && mode != Mode.CYCLING
        return base.copy(
            gyroPeriodUs = if (hasGyro) base.gyroPeriodUs else 0,
            pressurePeriodUs = if (!hasPressure || (saver && tier == SensingConfig.Tier.STILL)) 0 else base.pressurePeriodUs,
            maxLatencyUs = if (saver) base.maxLatencyUs * 2 else base.maxLatencyUs,
        )
    }

    /**
     * @param alerting possible-accident hold, countdown or send in flight: 1 s fixes.
     * @param live an alert was sent and its live updates run (pushed every 15 s): 5 s fixes are plenty.
     * @param gnss walking/running/cycling with balanced fixes that are stale or coarse ([WalkingFixMonitor]): the
     *   same cadence at high accuracy, so the trail is still usable where Wi-Fi/cell positioning is poor.
     */
    fun location(mode: Mode, alerting: Boolean, batterySaver: Boolean, live: Boolean = false, gnss: Boolean = false): LocationPlan {
        val priority = if (gnss && usesWalkingFixes(mode)) LocationPlan.PRIORITY_HIGH_ACCURACY else LocationPlan.PRIORITY_BALANCED_POWER_ACCURACY
        return when {
            alerting -> LocationPlan(LocationPlan.PRIORITY_HIGH_ACCURACY, 1_000, 1_000, 1_000)
            live -> LocationPlan(LocationPlan.PRIORITY_HIGH_ACCURACY, 5_000, 2_500, 5_000)
            mode == Mode.VEHICLE -> LocationPlan(LocationPlan.PRIORITY_HIGH_ACCURACY, 10_000, 5_000, 10_000)
            mode == Mode.CYCLING -> LocationPlan(priority, 20_000, 10_000, 20_000)
            mode == Mode.STILL ->
                LocationPlan(LocationPlan.PRIORITY_PASSIVE, if (batterySaver) 120_000 else 60_000, 30_000, if (batterySaver) 120_000 else 60_000)
            batterySaver -> LocationPlan(priority, 60_000, 30_000, 60_000)
            else -> LocationPlan(priority, 30_000, 15_000, 30_000)
        }
    }

    /**
     * Modes whose trail comes from balanced (Wi-Fi/cell) fixes and may need [WalkingFixMonitor]'s GNSS escalation.
     * Not UNKNOWN: without Activity Recognition the phone may well be lying still indoors, where GNSS only burns
     * battery.
     */
    fun usesWalkingFixes(mode: Mode): Boolean = mode == Mode.WALKING || mode == Mode.RUNNING || mode == Mode.CYCLING

    /**
     * How often the STILL tier must drain the accelerometer FIFO so nothing is overwritten while the CPU sleeps:
     * half its depth (`fifoEvents` at [accelHz]), clamped to [MIN_STILL_DRAIN_MS]..[MAX_STILL_DRAIN_MS]; the
     * target phone's 3000 reserved events at 25 Hz (120 s) give 60 s.
     */
    fun stillDrainIntervalMs(fifoEvents: Int, accelHz: Int = STILL_ACCEL_HZ): Long {
        if (fifoEvents <= 0 || accelHz <= 0) return DEFAULT_STILL_DRAIN_MS
        val depthMs = fifoEvents * 1_000L / accelHz
        return (depthMs / 2).coerceIn(MIN_STILL_DRAIN_MS, MAX_STILL_DRAIN_MS)
    }

    private fun periodUs(hz: Int): Int = US_PER_S / hz
}

/**
 * Decides when walking/running/cycling needs GNSS (SPEC: "Walking → balanced location 30 s / 20 m").
 *
 * Balanced fixes come from Wi-Fi and cell towers. Where those are poor the walking trail goes stale or coarse (seen
 * on the emulator, which has no network positioning: one ±100 m fix and nothing for minutes while the route ran,
 * and the heartbeats kept repeating it). So:
 * - balanced while fixes are fresh and good;
 * - no fix for [staleAfterMs], or [coarseFixes] fixes in a row worse than [coarseM]: probe with high accuracy;
 * - a probe that yields a fix of [goodM] or better keeps high accuracy for [holdMs], then balanced is tried again;
 * - a probe that yields nothing good within [probeMs] (indoors: GNSS cannot help) backs off for [backoffMs].
 * All times are elapsed-realtime ms. Not thread-safe (the service's main thread).
 */
class WalkingFixMonitor(
    private val staleAfterMs: Long = 75_000,
    private val coarseM: Float = 75f,
    private val coarseFixes: Int = 2,
    private val goodM: Float = 40f,
    private val probeMs: Long = 120_000,
    private val holdMs: Long = 5 * 60_000,
    private val backoffMs: Long = 10 * 60_000,
) {
    enum class State { BALANCED, PROBING, GNSS, BACKOFF }

    var state: State = State.BALANCED
        private set

    private var active = false
    private var referenceMs = Long.MIN_VALUE
    private var coarseInRow = 0
    private var untilMs = Long.MIN_VALUE

    /** Whether the walking-type tier is in use (from [nowMs]); leaving it resets everything. */
    fun setActive(isActive: Boolean, nowMs: Long) {
        if (isActive == active) return
        active = isActive
        state = State.BALANCED
        coarseInRow = 0
        referenceMs = nowMs
        untilMs = Long.MIN_VALUE
    }

    /** A fix arrived (accuracy in m, null if unknown). */
    fun onFix(nowMs: Long, accuracyM: Float?) {
        if (!active) return
        referenceMs = nowMs
        val good = accuracyM != null && accuracyM <= goodM
        val coarse = accuracyM == null || accuracyM > coarseM
        coarseInRow = if (coarse) coarseInRow + 1 else 0
        when (state) {
            State.PROBING, State.GNSS -> if (good) {
                state = State.GNSS
                untilMs = nowMs + holdMs
            }
            State.BALANCED -> if (coarseInRow >= coarseFixes) startProbe(nowMs)
            State.BACKOFF -> Unit
        }
    }

    /** True if the location request should use high accuracy now. Advances time-based transitions. */
    fun wantsGnss(nowMs: Long): Boolean {
        if (!active) return false
        when (state) {
            State.BALANCED -> if (referenceMs != Long.MIN_VALUE && nowMs - referenceMs >= staleAfterMs) startProbe(nowMs)
            State.PROBING -> if (nowMs >= untilMs) {
                state = State.BACKOFF
                untilMs = nowMs + backoffMs
            }
            State.GNSS -> if (nowMs >= untilMs) toBalanced(nowMs)
            State.BACKOFF -> if (nowMs >= untilMs) toBalanced(nowMs)
        }
        return state == State.PROBING || state == State.GNSS
    }

    private fun startProbe(nowMs: Long) {
        state = State.PROBING
        untilMs = nowMs + probeMs
    }

    private fun toBalanced(nowMs: Long) {
        state = State.BALANCED
        coarseInRow = 0
        referenceMs = nowMs
        untilMs = Long.MIN_VALUE
    }
}

/**
 * When to send `POST /v1/location/heartbeat` (SPEC: 60 s walking / 30 s vehicle, only while sharing is on, none
 * when still and unchanged).
 *
 * Deviation (docs/research/android-background.md §0.3): while still, a keep-alive heartbeat goes out every
 * [stillKeepAliveMs] even if the position is unchanged. Battery saver doubles every interval.
 * UNKNOWN (no Activity Recognition) is paced like STILL: a heartbeat after a real move, else the keep-alive.
 *
 * There is no push channel: a viewer learns about a family member's accident from the heartbeat response
 * (`pending_alerts`) or from `GET /v1/incidents/active`. So between still heartbeats the family alerts are polled
 * every [stillAlertPollMs] ([alertPollIntervalMs]); a still phone used to hear about an accident up to 5 min late.
 */
data class HeartbeatPolicy(
    val vehicleIntervalMs: Long = 30_000,
    val movingIntervalMs: Long = 60_000,
    val stillKeepAliveMs: Long = 5 * 60_000,
    /** In STILL / UNKNOWN, a move of at least this much sends a heartbeat right away. */
    val stillMoveM: Double = 50.0,
    /** STILL / UNKNOWN: family alerts are checked at least this often (one small GET, no location). */
    val stillAlertPollMs: Long = 2 * 60_000,
) {
    data class Last(val atMs: Long, val lat: Double, val lng: Double)

    /** How long a family accident may go unnoticed at most (heartbeat or alert poll, whichever is more often). */
    fun alertPollIntervalMs(mode: Mode, batterySaver: Boolean): Long {
        val base = when (mode) {
            Mode.STILL, Mode.UNKNOWN -> minOf(stillAlertPollMs, stillKeepAliveMs)
            else -> intervalMs(mode, batterySaver = false)
        }
        return if (batterySaver) base * 2 else base
    }

    fun intervalMs(mode: Mode, batterySaver: Boolean): Long {
        val base = when (mode) {
            Mode.VEHICLE -> vehicleIntervalMs
            Mode.STILL, Mode.UNKNOWN -> stillKeepAliveMs
            else -> movingIntervalMs
        }
        return if (batterySaver) base * 2 else base
    }

    /**
     * @param distanceFromLastM distance between the fix to send and the last one sent (null if none was sent).
     */
    fun shouldSend(
        nowMs: Long,
        mode: Mode,
        signedIn: Boolean,
        sharingOn: Boolean,
        batterySaver: Boolean,
        last: Last?,
        distanceFromLastM: Double?,
    ): Boolean {
        if (!signedIn || !sharingOn) return false
        if (last == null) return true
        val elapsed = nowMs - last.atMs
        if (elapsed < 0) return true // clock went backwards: resync
        val stillRules = mode == Mode.STILL || mode == Mode.UNKNOWN
        if (stillRules && distanceFromLastM != null && distanceFromLastM >= stillMoveM && elapsed >= movingIntervalMs / 2) {
            return true
        }
        return elapsed >= intervalMs(mode, batterySaver)
    }
}

/**
 * The effective movement mode from its sources, in priority order: a debug override, then the GPS speed (a car
 * is a car even if Activity Recognition is slow to notice), then the last Activity Recognition transition, then
 * (only while Activity Recognition has nothing) the phone's own stillness.
 *
 * Speed rule: two consecutive fixes faster than [vehicleSpeedMps] mean VEHICLE. It ends after three consecutive
 * fixes slower than [walkingSpeedMps]; when no fix *with a speed* arrived for [speedStaleMs] (indoor Wi-Fi/cell
 * fixes carry none, so they cannot keep it alive); or when Activity Recognition reported STILL/WALKING/RUNNING
 * after the last fast fix and that fix is older than [arOverrideMs]. Not thread-safe.
 */
class ModeResolver(
    private val vehicleSpeedMps: Float = 8f,
    private val walkingSpeedMps: Float = 2f,
    private val speedStaleMs: Long = 3 * 60_000L,
    private val arOverrideMs: Long = 60_000L,
) {
    enum class Source { DEBUG, SPEED, ACTIVITY_RECOGNITION, SENSORS, NONE }

    var forced: Mode? = null
    var recognized: Mode? = null
        private set

    /** Accelerometer stillness ([StillnessMonitor]); only used while [recognized] is null. */
    var sensorStill: Boolean = false
        private set

    private var recognizedAtMs = Long.MIN_VALUE
    private var fastFixes = 0
    private var slowFixes = 0
    private var speedVehicle = false
    private var lastSpeedFixAtMs = Long.MIN_VALUE
    private var lastFastFixAtMs = Long.MIN_VALUE

    fun onRecognized(mode: Mode, atMs: Long = Long.MIN_VALUE) {
        recognized = mode
        recognizedAtMs = atMs
    }

    fun onSensorStill(still: Boolean) {
        sensorStill = still
    }

    /** Feeds a fix's speed (null when the fix has none: such fixes say nothing about driving). */
    fun onSpeed(speedMps: Float?, atMs: Long) {
        if (speedMps == null || !speedMps.isFinite()) return
        lastSpeedFixAtMs = atMs
        when {
            speedMps >= vehicleSpeedMps -> {
                fastFixes++
                slowFixes = 0
                lastFastFixAtMs = atMs
                if (fastFixes >= 2) speedVehicle = true
            }
            speedMps < walkingSpeedMps -> {
                slowFixes++
                fastFixes = 0
                if (slowFixes >= 3) speedVehicle = false
            }
            else -> {
                fastFixes = 0
                slowFixes = 0
            }
        }
    }

    fun resolve(nowMs: Long): Pair<Mode, Source> {
        forced?.let { return it to Source.DEBUG }
        if (speedVehicle && nowMs - lastSpeedFixAtMs > speedStaleMs) speedVehicle = false
        if (speedVehicle && recognized in ON_FOOT_OR_STILL && recognizedAtMs > lastFastFixAtMs && nowMs - lastFastFixAtMs > arOverrideMs) {
            speedVehicle = false
        }
        if (speedVehicle && recognized != Mode.VEHICLE) return Mode.VEHICLE to Source.SPEED
        recognized?.let { return it to Source.ACTIVITY_RECOGNITION }
        if (sensorStill) return Mode.STILL to Source.SENSORS
        return Mode.UNKNOWN to Source.NONE
    }

    /** While the speed rule holds VEHICLE: the wall-clock time it goes stale without new speed fixes. */
    fun speedStaleAtMs(): Long? = if (speedVehicle) lastSpeedFixAtMs + speedStaleMs else null

    private companion object {
        val ON_FOOT_OR_STILL = setOf(Mode.STILL, Mode.WALKING, Mode.RUNNING)
    }
}

/**
 * Rate limit for the "possible accident" tier (full-rate sensors + 1 s GPS). The detector reports every
 * candidate, which can be every stride while running or on stairs. A candidate escalates only if
 * - it is fresh: younger than `maxAgeMs` when it reaches us (with the screen off, batches arrive late; an old
 *   candidate's window was already sampled at the batched rate and its verdict is due anyway);
 * - it is not part of a gait rhythm: fewer than [gaitCandidates] other candidates in the [gaitWindowMs] before it;
 * - the tier's time budget is not used up: at most [budgetMs] of holds in any [budgetWindowMs] (and at least
 *   [minGapMs] since the previous hold ended).
 * Each entry holds for at most [holdMs] (long enough for the detector's 10–15 s analysis window); the service ends
 * it earlier as soon as the detector has no candidate pending ([endHold]), so a rejected false trigger costs only
 * its own few seconds.
 *
 * The budget replaces a fixed "one entry per 2 min" rule, which let a dropped phone or sitting down block the full
 * rate for the real impact that followed a minute later (seen in emulator tests: `RATE_LIMITED` twice for a
 * pedestrian impact). Bursts of isolated candidates are still bounded: about 25 % of the time at most.
 *
 * All times are elapsed-realtime milliseconds (candidate times are sensor time, the same base).
 */
class PossibleAccidentGovernor(
    private val holdMs: Long = 30_000,
    private val budgetWindowMs: Long = 10 * 60_000,
    private val budgetMs: Long = 150_000,
    private val minGapMs: Long = 0,
    private val gaitWindowMs: Long = 4_000,
    private val gaitCandidates: Int = 3,
) {
    enum class Decision { ENTER, ALREADY_HOLDING, TOO_OLD, GAIT, RATE_LIMITED }

    init {
        require(holdMs > 0 && budgetWindowMs > 0 && budgetMs >= 0 && minGapMs >= 0)
    }

    private class Hold(val startMs: Long, var endMs: Long)

    private var holdUntilMs = Long.MIN_VALUE
    private val holds = ArrayDeque<Hold>()
    private val recent = ArrayDeque<Long>()

    /** A candidate with peak at [candidateAtMs] reached us at [nowMs]. [Decision.ENTER]: apply the alert tier now. */
    fun onCandidate(nowMs: Long, candidateAtMs: Long = nowMs, maxAgeMs: Long = DEFAULT_MAX_AGE_MS): Decision {
        while (recent.isNotEmpty() && recent.first() < candidateAtMs - gaitWindowMs) recent.removeFirst()
        val rhythm = recent.count { it in (candidateAtMs - gaitWindowMs) until candidateAtMs } >= gaitCandidates
        recent.addLast(candidateAtMs)
        if (recent.size > MAX_RECENT) recent.removeFirst()
        return when {
            nowMs < holdUntilMs -> Decision.ALREADY_HOLDING
            nowMs - candidateAtMs >= maxAgeMs -> Decision.TOO_OLD
            rhythm -> Decision.GAIT
            !budgetAllows(nowMs) -> Decision.RATE_LIMITED
            else -> {
                holdUntilMs = nowMs + holdMs
                holds.addLast(Hold(nowMs, holdUntilMs))
                Decision.ENTER
            }
        }
    }

    fun isHolding(nowMs: Long): Boolean = nowMs < holdUntilMs

    /** Ends the hold now (the detector has delivered its verdict). */
    fun endHold(nowMs: Long) {
        if (holdUntilMs > nowMs) holdUntilMs = nowMs
        holds.lastOrNull()?.let { if (it.endMs > nowMs) it.endMs = maxOf(it.startMs, nowMs) }
    }

    /** Elapsed-realtime ms at which the hold ends (meaningful only while [isHolding]). */
    val holdEndsAtMs: Long get() = holdUntilMs

    /** Hold time used in the [budgetWindowMs] before [nowMs] (debug status, tests). */
    fun usedMs(nowMs: Long): Long {
        val from = nowMs - budgetWindowMs
        while (holds.isNotEmpty() && holds.first().endMs <= from) holds.removeFirst()
        return holds.sumOf { h -> (minOf(h.endMs, nowMs) - maxOf(h.startMs, from)).coerceAtLeast(0) }
    }

    private fun budgetAllows(nowMs: Long): Boolean {
        val last = holds.lastOrNull()
        if (last != null && nowMs >= last.endMs && nowMs - last.endMs < minGapMs) return false
        return usedMs(nowMs) + MIN_USEFUL_HOLD_MS <= budgetMs
    }

    companion object {
        /** Walking-type modes: the post window is 10 s. */
        const val DEFAULT_MAX_AGE_MS = 5_000L

        /** Vehicle: a 15 s post window, and 1 s fixes still help the speed after a crash. */
        const val VEHICLE_MAX_AGE_MS = 10_000L

        /** An entry needs at least this much budget left to be worth it (the detector's post window). */
        private const val MIN_USEFUL_HOLD_MS = 10_000L

        private const val MAX_RECENT = 64
    }
}

/**
 * Sensor-only stillness (for phones without Activity Recognition: permission denied or Play services missing).
 * The accelerometer magnitude's standard deviation per [windowNs] window must stay below [stillStdG] for
 * [requiredStillNs] to call the phone still; one moving window, or any jolt above [joltG], ends it at once.
 * Gaps in the stream (FIFO overwritten while asleep) neither start nor end stillness. Not thread-safe.
 */
class StillnessMonitor(
    private val stillStdG: Double = 0.05,
    private val windowNs: Long = 10_000_000_000L,
    private val requiredStillNs: Long = 150_000_000_000L,
    private val joltG: Double = 0.35,
) {
    private var windowStartNs = Long.MIN_VALUE
    private var n = 0
    private var sum = 0.0
    private var sumSq = 0.0
    private var stillSinceNs = Long.MIN_VALUE

    var isStill: Boolean = false
        private set

    /** Accelerometer sample in m/s². Returns true if [isStill] changed. */
    fun onAccel(tNanos: Long, x: Float, y: Float, z: Float): Boolean {
        val g = sqrt((x * x + y * y + z * z).toDouble()) / G0
        if (windowStartNs == Long.MIN_VALUE || tNanos < windowStartNs) startWindow(tNanos)
        if (tNanos - windowStartNs >= windowNs) {
            closeWindow()
            startWindow(tNanos)
        }
        n++
        sum += g
        sumSq += g * g
        if (kotlin.math.abs(g - 1.0) > joltG) stillSinceNs = Long.MIN_VALUE
        val now = stillSinceNs != Long.MIN_VALUE && tNanos - stillSinceNs >= requiredStillNs
        val changed = now != isStill
        isStill = now
        return changed
    }

    /** Movement reported elsewhere (significant motion): not still any more. */
    fun reset() {
        stillSinceNs = Long.MIN_VALUE
        isStill = false
        windowStartNs = Long.MIN_VALUE
        n = 0
    }

    private fun startWindow(t: Long) {
        windowStartNs = t
        n = 0
        sum = 0.0
        sumSq = 0.0
    }

    private fun closeWindow() {
        if (n < MIN_SAMPLES) return // too few samples to judge (a gap): keep the current verdict
        val mean = sum / n
        val std = sqrt((sumSq / n - mean * mean).coerceAtLeast(0.0))
        if (std < stillStdG) {
            if (stillSinceNs == Long.MIN_VALUE) stillSinceNs = windowStartNs
        } else {
            stillSinceNs = Long.MIN_VALUE
        }
    }

    private companion object {
        const val G0 = 9.80665
        const val MIN_SAMPLES = 20
    }
}

fun Mode.toActivity(): Activity = when (this) {
    Mode.STILL -> Activity.STILL
    Mode.WALKING -> Activity.WALKING
    Mode.RUNNING -> Activity.RUNNING
    Mode.CYCLING -> Activity.CYCLING
    Mode.VEHICLE -> Activity.VEHICLE
    Mode.UNKNOWN -> Activity.UNKNOWN
}

/** Parses a mode name case-insensitively ("walking", "IN_VEHICLE" and "driving" also work); null if unknown. */
fun parseMode(value: String?): Mode? {
    val v = value?.trim()?.uppercase() ?: return null
    return when (v) {
        "IN_VEHICLE", "DRIVING", "CAR" -> Mode.VEHICLE
        "ON_BICYCLE", "BIKE", "BICYCLE" -> Mode.CYCLING
        "ON_FOOT", "WALK" -> Mode.WALKING
        "RUN" -> Mode.RUNNING
        else -> Mode.entries.firstOrNull { it.name == v }
    }
}
