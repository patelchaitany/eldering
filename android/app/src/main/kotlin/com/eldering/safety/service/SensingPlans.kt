package com.eldering.safety.service

import com.eldering.detection.Mode
import com.eldering.safety.network.Activity

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
 * | STILL | accel 25 Hz, batched ≈10 s | passive only |
 * | WALKING / RUNNING / UNKNOWN | accel + gyro 50 Hz, batched 5 s | balanced, every 30 s |
 * | VEHICLE / CYCLING | accel + gyro 100 Hz, batched 2 s | high accuracy, every 5 s |
 * | possible accident / alert | accel + gyro 200 Hz (the no-permission cap), no batching | high accuracy, every 1 s |
 *
 * Decisions (see docs/research/android-background.md §0):
 * - Location requests are never distance-filtered: their callbacks also pace sensor FIFO drains. The SPEC's 20 m
 *   walking filter is applied by `TrailSamplingPolicy` when the trail stores a fix.
 * - VEHICLE uses HIGH_ACCURACY (SPEC: balanced 5–10 s): the detector's speed-drop and vehicle-context features
 *   need GNSS speed, which balanced fixes often lack.
 * - Battery saver doubles batching latency and location intervals in STILL/WALKING and drops the barometer; the
 *   vehicle and alert tiers stay as they are (a crash must still be caught).
 * - The barometer, when present, runs at 5 Hz in every tier except STILL under battery saver (falls from standing
 *   still use its height delta).
 */
object SensingPlans {
    private const val US_PER_S = 1_000_000

    fun tierFor(mode: Mode, alerting: Boolean): SensingConfig.Tier = when {
        alerting -> SensingConfig.Tier.ALERT
        mode == Mode.VEHICLE || mode == Mode.CYCLING -> SensingConfig.Tier.VEHICLE
        mode == Mode.STILL -> SensingConfig.Tier.STILL
        else -> SensingConfig.Tier.WALKING // WALKING, RUNNING, UNKNOWN
    }

    fun sensing(mode: Mode, alerting: Boolean, batterySaver: Boolean, hasGyro: Boolean = true, hasPressure: Boolean = false): SensingConfig {
        val tier = tierFor(mode, alerting)
        val base = when (tier) {
            SensingConfig.Tier.STILL -> SensingConfig(tier, periodUs(25), 0, periodUs(5), 10 * US_PER_S)
            SensingConfig.Tier.WALKING -> SensingConfig(tier, periodUs(50), periodUs(50), periodUs(5), 5 * US_PER_S)
            SensingConfig.Tier.VEHICLE -> SensingConfig(tier, periodUs(100), periodUs(100), periodUs(5), 2 * US_PER_S)
            SensingConfig.Tier.ALERT -> SensingConfig(tier, periodUs(200), periodUs(200), periodUs(5), 0)
        }
        val saver = batterySaver && (tier == SensingConfig.Tier.STILL || tier == SensingConfig.Tier.WALKING)
        return base.copy(
            gyroPeriodUs = if (hasGyro) base.gyroPeriodUs else 0,
            pressurePeriodUs = if (!hasPressure || (saver && tier == SensingConfig.Tier.STILL)) 0 else base.pressurePeriodUs,
            maxLatencyUs = if (saver) base.maxLatencyUs * 2 else base.maxLatencyUs,
        )
    }

    fun location(mode: Mode, alerting: Boolean, batterySaver: Boolean): LocationPlan = when (tierFor(mode, alerting)) {
        SensingConfig.Tier.ALERT -> LocationPlan(LocationPlan.PRIORITY_HIGH_ACCURACY, 1_000, 1_000, 1_000)
        SensingConfig.Tier.VEHICLE -> LocationPlan(LocationPlan.PRIORITY_HIGH_ACCURACY, 5_000, 2_500, 5_000)
        SensingConfig.Tier.WALKING ->
            if (batterySaver) {
                LocationPlan(LocationPlan.PRIORITY_BALANCED_POWER_ACCURACY, 60_000, 30_000, 60_000)
            } else {
                LocationPlan(LocationPlan.PRIORITY_BALANCED_POWER_ACCURACY, 30_000, 15_000, 30_000)
            }
        SensingConfig.Tier.STILL ->
            LocationPlan(LocationPlan.PRIORITY_PASSIVE, if (batterySaver) 120_000 else 60_000, 30_000, if (batterySaver) 120_000 else 60_000)
    }

    private fun periodUs(hz: Int): Int = US_PER_S / hz
}

/**
 * When to send `POST /v1/location/heartbeat` (SPEC: 60 s walking / 30 s vehicle, only while sharing is on, none
 * when still and unchanged).
 *
 * Deviation (docs/research/android-background.md §0.3): while still, a keep-alive heartbeat goes out every
 * [stillKeepAliveMs] even if the position is unchanged, because the heartbeat response is the only way a viewer
 * learns about a family member's accident (`pending_alerts`, no push). Battery saver doubles every interval.
 */
data class HeartbeatPolicy(
    val vehicleIntervalMs: Long = 30_000,
    val movingIntervalMs: Long = 60_000,
    val stillKeepAliveMs: Long = 5 * 60_000,
    /** In STILL, a move of at least this much sends a heartbeat right away. */
    val stillMoveM: Double = 50.0,
) {
    data class Last(val atMs: Long, val lat: Double, val lng: Double)

    fun intervalMs(mode: Mode, batterySaver: Boolean): Long {
        val base = when (mode) {
            Mode.VEHICLE -> vehicleIntervalMs
            Mode.STILL -> stillKeepAliveMs
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
        if (mode == Mode.STILL && distanceFromLastM != null && distanceFromLastM >= stillMoveM && elapsed >= movingIntervalMs / 2) {
            return true
        }
        return elapsed >= intervalMs(mode, batterySaver)
    }
}

/**
 * The effective movement mode from its sources, in priority order: a debug override, then the GPS speed (a car
 * is a car even if Activity Recognition is slow to notice), then the last Activity Recognition transition.
 *
 * Speed rule: two consecutive fixes faster than [vehicleSpeedMps] mean VEHICLE; it ends after three consecutive
 * fixes slower than [walkingSpeedMps], or when no fix arrived for [speedStaleMs]. Not thread-safe.
 */
class ModeResolver(
    private val vehicleSpeedMps: Float = 8f,
    private val walkingSpeedMps: Float = 2f,
    private val speedStaleMs: Long = 3 * 60_000L,
) {
    enum class Source { DEBUG, SPEED, ACTIVITY_RECOGNITION, NONE }

    var forced: Mode? = null
    var recognized: Mode? = null
        private set

    private var fastFixes = 0
    private var slowFixes = 0
    private var speedVehicle = false
    private var lastFixAtMs = Long.MIN_VALUE

    fun onRecognized(mode: Mode) {
        recognized = mode
    }

    /** Feeds a fix's speed (null when the fix has none). */
    fun onSpeed(speedMps: Float?, atMs: Long) {
        lastFixAtMs = atMs
        if (speedMps == null || !speedMps.isFinite()) return
        when {
            speedMps >= vehicleSpeedMps -> {
                fastFixes++
                slowFixes = 0
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
        if (speedVehicle && nowMs - lastFixAtMs > speedStaleMs) speedVehicle = false
        if (speedVehicle && recognized != Mode.VEHICLE) return Mode.VEHICLE to Source.SPEED
        recognized?.let { return it to Source.ACTIVITY_RECOGNITION }
        return Mode.UNKNOWN to Source.NONE
    }
}

/**
 * Rate limit for the "possible accident" tier (full-rate sensors + 1 s GPS): the detector reports every
 * candidate, which can be every stride while running. Enter at most once per [reentryMs]; each entry holds for
 * [holdMs] (long enough for the detector's 10–15 s analysis window).
 */
class PossibleAccidentGovernor(
    private val holdMs: Long = 30_000,
    private val reentryMs: Long = 120_000,
) {
    private var enteredAtMs = Long.MIN_VALUE
    private var holdUntilMs = Long.MIN_VALUE

    /** A candidate at [nowMs] (elapsed-realtime ms). Returns true if the alert tier should be (re)applied now. */
    fun onCandidate(nowMs: Long): Boolean {
        if (nowMs < holdUntilMs) return false // already in the tier
        if (enteredAtMs != Long.MIN_VALUE && nowMs - enteredAtMs < reentryMs) return false
        enteredAtMs = nowMs
        holdUntilMs = nowMs + holdMs
        return true
    }

    fun isHolding(nowMs: Long): Boolean = nowMs < holdUntilMs

    /** Elapsed-realtime ms at which the hold ends (meaningful only while [isHolding]). */
    val holdEndsAtMs: Long get() = holdUntilMs
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
