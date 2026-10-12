package com.eldering.detection

import java.util.Random
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min

/** A mode report in a synthetic trace (feed with [AccidentDetector.onMode]). */
public data class ModeChange(val tNanos: Long, val mode: Mode)

/** A GPS speed fix in a synthetic trace (feed with [AccidentDetector.onSpeed]). */
public data class SpeedFix(val tNanos: Long, val speedMps: Float)

/**
 * How to sample a [Scenario]. Everything random (motion noise, sensor noise, timestamp jitter, dropped samples,
 * phone orientation and the event's phase within the gait) derives from [seed], so a spec always yields the same
 * trace.
 *
 * @property rateHz accelerometer/gyro rate; null uses [Scenario.defaultRateHz].
 * @property accelRangeG accelerometer full scale (±g); null uses [Scenario.defaultRangeG].
 * @property withGyro include gyroscope samples.
 * @property withPressure include barometer samples; null uses the scenario default (only the `*_with_baro` ones).
 * @property randomizeOrientation random heading of the motion relative to the phone and random way the phone sits
 *   in its carrying position (spin about its long axis, upside down, a few degrees of tilt).
 * @property arbitraryMounting a uniformly random phone orientation instead (also changes the "upright in a pocket"
 *   cue that phone-drop suppression uses at low rates).
 * @property randomizeTiming shift the event by up to 0.6 s so it falls at a random gait phase.
 * @property jitterFraction timestamp jitter, standard deviation as a fraction of the period.
 * @property dropProbability probability that a single sample is lost.
 * @property accelNoiseG white accelerometer noise per axis, g.
 * @property startNanos timestamp of the trace start (an `elapsedRealtimeNanos`-like base).
 */
public data class TraceSpec(
    val rateHz: Int? = null,
    val accelRangeG: Float? = null,
    val seed: Long = 0L,
    val withGyro: Boolean = true,
    val withPressure: Boolean? = null,
    val randomizeOrientation: Boolean = true,
    val arbitraryMounting: Boolean = false,
    val randomizeTiming: Boolean = true,
    val jitterFraction: Double = 0.015,
    val dropProbability: Double = 0.002,
    val accelNoiseG: Double = 0.005,
    val startNanos: Long = 1_000_000_000_000L,
) {
    init {
        require(rateHz == null || rateHz in 10..1000) { "rateHz must be in 10..1000, was $rateHz" }
        require(accelRangeG == null || accelRangeG >= 2f) { "accelRangeG must be at least 2 g, was $accelRangeG" }
        require(jitterFraction in 0.0..0.2 && dropProbability in 0.0..0.2 && accelNoiseG >= 0.0)
    }
}

/**
 * A generated trace: time-ordered sensor samples plus the mode and speed timeline, and the ground truth.
 *
 * @property eventAtNanos when the scenario's event (impact, drop, slam…) starts; null for continuous activities.
 */
public data class SyntheticTrace(
    val scenario: Scenario,
    val rateHz: Int,
    val accelRangeG: Float,
    val seed: Long,
    val samples: List<SensorSample>,
    val modeChanges: List<ModeChange>,
    val speedFixes: List<SpeedFix>,
    val eventAtNanos: Long?,
    val startNanos: Long,
    val endNanos: Long,
) {
    /** Range to pass to [AccidentDetector.setAccelMaxRange], m/s². */
    val accelMaxRangeMs2: Float get() = (accelRangeG * G0).toFloat()
}

/**
 * Synthetic scenarios (docs/research/detection-params.md §5.2, plus `speed_bump`). Magnitudes and durations follow
 * the research recipes; `expected` is the outcome the detector must produce (empty = no event).
 * Scenarios flagged [knownLimit] document accepted errors (research §5.4) and are not asserted as correct.
 */
public enum class Scenario(
    public val id: String,
    public val mode: Mode,
    public val expected: Set<EventKind>,
    public val defaultRateHz: Int,
    public val defaultRangeG: Float,
    /** Lower bound on confidence at the default rate/range (research §5.2), when an event is expected. */
    public val minConfidence: Float,
    /** Upper bound on confidence (only the GPS-less crash is capped). */
    public val maxConfidence: Float,
    public val knownLimit: Boolean,
    public val description: String,
    internal val baseEventS: Double,
    internal val recipe: (Double, Random) -> Truth,
    internal val pressureByDefault: Boolean = false,
    /** Sensor gap relative to the event start, seconds. */
    internal val gapAfterEventS: ClosedFloatingPointRange<Double>? = null,
) {
    // ---- the build task's scenario list
    WALK("walk", Mode.WALKING, none(), 50, 8f, 0f, 1f, false, "40 s of walking, phone in a trouser pocket", Double.NaN, Recipes::walk),
    RUN("run", Mode.RUNNING, none(), 50, 8f, 0f, 1f, false, "40 s of running; every stride passes the trigger", Double.NaN, Recipes::run),
    STAIRS("stairs", Mode.WALKING, none(), 50, 8f, 0f, 1f, false, "40 s walking down stairs (2.4 g strides)", Double.NaN, Recipes::stairs),
    SIT_DOWN("sit_down", Mode.WALKING, none(), 50, 8f, 0f, 1f, false, "Sitting down onto a chair, then fidgeting", 5.0, Recipes::sitDown),
    PHONE_DROP("phone_drop", Mode.WALKING, none(), 50, 8f, 0f, 1f, false, "Phone dropped 1 m from the hand, bounces, left on the floor", 5.0, { te, r -> Recipes.phoneDrop(te, r, pickup = false) }),
    PHONE_TOSS_ON_BED("phone_toss_on_bed", Mode.STILL, none(), 25, 8f, 0f, 1f, false, "Phone tossed onto a bed", 5.0, Recipes::tossOnBed),
    POTHOLE("pothole", Mode.VEHICLE, none(), 100, 8f, 0f, 1f, false, "Mounted phone, car hits a pothole at 40 km/h", 8.0, { te, r -> Recipes.pothole(te, r, loose = false) }),
    SPEED_BUMP("speed_bump", Mode.VEHICLE, none(), 100, 8f, 0f, 1f, false, "Mounted phone, speed bump taken at 20 km/h (≈3 g), car drives on", 10.0, Recipes::speedBump),
    HARD_BRAKE("hard_brake", Mode.VEHICLE, none(), 100, 8f, 0f, 1f, false, "Emergency stop from 60 km/h at 0.9 g", 8.0, { te, r -> Recipes.hardBrake(te, r, slide = false) }),
    DOOR_SLAM("door_slam", Mode.VEHICLE, none(), 100, 8f, 0f, 1f, false, "Parked car, phone in the door pocket, door slammed", 8.0, Recipes::doorSlam),
    FALL("fall", Mode.WALKING, setOf(EventKind.FALL), 50, 8f, 0.8f, 1f, false, "Walking, trip and fall to the ground, lying still", 5.0, { te, r -> Recipes.fall(te, r, getUp = false) }),
    PEDESTRIAN_IMPACT("pedestrian_impact", Mode.WALKING, setOf(EventKind.PEDESTRIAN_IMPACT), 50, 8f, 0.9f, 1f, false, "Walking pedestrian struck by a car at 40 km/h", 6.0, Recipes::pedestrian),
    VEHICLE_CRASH("vehicle_crash", Mode.VEHICLE, setOf(EventKind.VEHICLE_CRASH), 100, 16f, 0.9f, 1f, false, "Frontal crash at 50 km/h, mounted phone", 10.0, { te, r -> Recipes.crash(te, r) }),
    CYCLING_CRASH("cycling_crash", Mode.CYCLING, setOf(EventKind.FALL), 50, 8f, 0.8f, 1f, false, "Cyclist at 20 km/h falls and lies still (reported as FALL)", 8.0, Recipes::bikeCrash),

    // ---- research extras (suppression, degradation, kinds)
    PHONE_DROP_QUICK_PICKUP("phone_drop_quick_pickup", Mode.STILL, none(), 25, 8f, 0f, 1f, false, "Phone drop, picked up 3 s later", 5.0, { te, r -> Recipes.phoneDrop(te, r, pickup = true) }),
    CARPET_DROP_LEFT("carpet_drop_left", Mode.STILL, none(), 25, 8f, 0f, 1f, false, "Phone dropped onto carpet (4 g, 35 ms), no bounce, left there", 5.0, Recipes::carpetDrop),
    LADDER_FALL("ladder_fall", Mode.WALKING, setOf(EventKind.FALL), 50, 8f, 0.8f, 1f, false, "Fall from a ladder: 0.5 s true free fall with the phone in a pocket", 5.0, Recipes::ladderFall),
    FALL_THEN_GET_UP("fall_then_get_up", Mode.WALKING, none(), 50, 8f, 0f, 1f, false, "Fall, then gets up and walks on within 5 s", 5.0, { te, r -> Recipes.fall(te, r, getUp = true) }),
    FALL_FROM_STANDING_STILL("fall_from_standing_still", Mode.STILL, setOf(EventKind.FALL), 25, 8f, 0.8f, 1f, false, "Standing still, collapses (2.8 g impact)", 5.0, Recipes::fallFromStill),
    RUNNING_FALL("running_fall", Mode.RUNNING, setOf(EventKind.FALL), 50, 4f, 0.85f, 1f, false, "Running fall on a ±4 g phone (impact clips)", 5.0, Recipes::runningFall),
    PEDESTRIAN_LOW_SPEED_STANDING("pedestrian_low_speed_standing", Mode.STILL, setOf(EventKind.PEDESTRIAN_IMPACT, EventKind.FALL), 25, 8f, 0.8f, 1f, false, "Standing pedestrian hit at low speed (5.5 g)", 5.0, Recipes::pedestrianLow),
    CRASH_POCKET_4G("crash_pocket_4g", Mode.VEHICLE, setOf(EventKind.VEHICLE_CRASH), 100, 4f, 0.9f, 1f, false, "50 km/h crash, phone in a pocket, ±4 g sensor", 10.0, { te, r -> Recipes.crash(te, r, carry = Carry.POCKET) }),
    CRASH_LOW_SPEED_20KMH("crash_low_speed_20kmh", Mode.VEHICLE, setOf(EventKind.VEHICLE_CRASH), 100, 8f, 0.8f, 1f, false, "20 km/h vehicle-to-vehicle crash (5.9 g, 150 ms)", 10.0, { te, r -> Recipes.crash(te, r, kmh = 20.0, pulseS = 0.15) }),
    CRASH_NO_GPS_AFTER("crash_no_gps_after", Mode.VEHICLE, setOf(EventKind.VEHICLE_CRASH), 100, 8f, 0.6f, 0.7f, false, "50 km/h crash in a tunnel: no speed fixes after", 10.0, { te, r -> Recipes.crash(te, r, tunnel = true) }),
    POTHOLE_LOOSE_PHONE("pothole_loose_phone", Mode.VEHICLE, none(), 100, 8f, 0f, 1f, false, "Pothole with the phone loose in a cup holder (5 g spike)", 8.0, { te, r -> Recipes.pothole(te, r, loose = true) }),
    HARD_BRAKE_PHONE_SLIDES("hard_brake_phone_slides", Mode.VEHICLE, none(), 100, 8f, 0f, 1f, false, "Hard brake, phone slides off the seat onto the floor (12 g, 15 ms)", 8.0, { te, r -> Recipes.hardBrake(te, r, slide = true) }),
    MOUNTED_VIBRATION("mounted_vibration", Mode.VEHICLE, none(), 100, 8f, 0f, 1f, false, "Highway at 90 km/h, vibrating mount and road bumps", Double.NaN, Recipes::mountedVibration),
    FALL_WITH_BARO("fall_with_baro", Mode.STILL, setOf(EventKind.FALL), 25, 8f, 0.8f, 1f, false, "Fall from standing with a barometer (+0.078 hPa)", 5.0, { te, r -> Recipes.fallFromStill(te, r).also { it.heightDrop(te + 0.55, 0.65) } }, pressureByDefault = true),
    STAIRS_WITH_BARO("stairs_with_baro", Mode.WALKING, none(), 50, 8f, 0f, 1f, false, "Stairs down 2.5 m with a barometer", 5.0, { te, r -> Recipes.stairs(te, r).also { it.heightDrop(te, 2.5) } }, pressureByDefault = true),
    FALL_WITH_GAP("fall_with_gap", Mode.WALKING, setOf(EventKind.FALL), 50, 8f, 0.65f, 1f, false, "Fall with a 120 ms sensor gap in the free-fall phase", 5.0, { te, r -> Recipes.fall(te, r, getUp = false) }, gapAfterEventS = 0.05..0.17),

    // ---- documented limits (research §5.4): reported, not asserted
    SIT_PLOP("sit_plop", Mode.WALKING, setOf(EventKind.FALL), 50, 8f, 0f, 1f, true, "Known false alarm: hard plop onto a chair looks like a fall", 5.0, Recipes::sitPlop),
    KNEE_FALL("knee_fall", Mode.WALKING, none(), 50, 8f, 0f, 1f, true, "Known miss: low-energy fall onto the knees (1.9 g)", 5.0, Recipes::kneeFall),
    ;

    /** True if the detector should emit an event for this scenario. */
    public val expectsEvent: Boolean get() = expected.isNotEmpty()

    public companion object {
        /** Looks a scenario up by [id] (for example from the debug-inject broadcast). */
        public fun fromId(id: String): Scenario =
            entries.firstOrNull { it.id == id } ?: throw IllegalArgumentException("Unknown scenario '$id'. Known: ${entries.joinToString { it.id }}")
    }
}

private fun none(): Set<EventKind> = emptySet()

/** Deterministic synthetic sensor traces for unit tests and the app's debug injector. */
public object SyntheticTraces {

    /** Scenario ids, in catalogue order. */
    public val ids: List<String> get() = Scenario.entries.map { it.id }

    /** Generates [scenario] sampled as described by [spec]. */
    public fun generate(scenario: Scenario, spec: TraceSpec = TraceSpec()): SyntheticTrace {
        val rendered = render(scenario, spec.seed, spec.randomizeOrientation, spec.arbitraryMounting, spec.randomizeTiming)
        return sample(scenario, rendered, spec)
    }

    /** Generates the scenario with [id]; see [Scenario.fromId]. */
    public fun generate(id: String, spec: TraceSpec = TraceSpec()): SyntheticTrace = generate(Scenario.fromId(id), spec)

    /** Builds and renders the ground truth (independent of rate and range, so tests can reuse it). */
    internal fun render(scenario: Scenario, seed: Long, randomizeOrientation: Boolean, arbitraryMounting: Boolean, randomizeTiming: Boolean): RenderedTruth {
        val motionRng = Random(mix(seed, scenario.ordinal.toLong(), 1))
        val placementRng = Random(mix(seed, scenario.ordinal.toLong(), 2))
        val te = if (scenario.baseEventS.isNaN()) 0.0 else scenario.baseEventS + if (randomizeTiming) placementRng.nextDouble() * 0.6 else 0.0
        val truth = scenario.recipe(te, motionRng)
        val yaw: Double
        val mount: DoubleArray
        when {
            arbitraryMounting -> {
                yaw = placementRng.nextDouble() * 2 * PI
                mount = Rot3.random(placementRng)
            }
            randomizeOrientation -> {
                yaw = placementRng.nextDouble() * 2 * PI
                // Spin about the phone's long axis (keeps the angle to "upright"), maybe upside down, ≤ 8° tilt.
                var m = Rot3.axisAngle(Carry.Y, placementRng.nextDouble() * 2 * PI)
                if (placementRng.nextBoolean()) m = Rot3.mul(m, Rot3.axisAngle(doubleArrayOf(0.0, 0.0, 1.0), PI))
                val tiltAxis = doubleArrayOf(placementRng.nextGaussian(), placementRng.nextGaussian(), placementRng.nextGaussian())
                m = Rot3.mul(m, Rot3.axisAngle(tiltAxis, Math.toRadians(placementRng.nextDouble() * 8.0)))
                mount = m
            }
            else -> {
                yaw = 0.0
                mount = Rot3.IDENTITY
            }
        }
        return truth.render(yaw, mount).also { it.eventS = if (scenario.baseEventS.isNaN()) Double.NaN else te }
    }

    /** Samples a rendered truth at the spec's rate and range. */
    internal fun sample(scenario: Scenario, r: RenderedTruth, spec: TraceSpec): SyntheticTrace {
        val rate = spec.rateHz ?: scenario.defaultRateHz
        val rangeG = spec.accelRangeG ?: scenario.defaultRangeG
        val rng = Random(mix(spec.seed, scenario.ordinal.toLong() * 7919 + rate, rangeG.toLong() + 3))
        val last = r.lastTimeS
        val start = spec.startNanos
        val gap = scenario.gapAfterEventS?.let { (r.eventS + it.start)..(r.eventS + it.endInclusive) }
        val ts = SensorModel.timestamps(last, rate, spec.jitterFraction, spec.dropProbability, 0.0, gap, rng)
        val samples = ArrayList<SensorSample>(ts.size * 2)
        samples.addAll(SensorModel.accelSamples(r, ts, rate, rangeG.toDouble(), spec.accelNoiseG, rng, start))
        if (spec.withGyro) {
            val gts = SensorModel.timestamps(last, rate, spec.jitterFraction, spec.dropProbability, SensorModel.gyroOffsetS(), gap, rng)
            samples.addAll(SensorModel.gyroSamples(r, gts, rate, rng, start))
        }
        if (spec.withPressure ?: scenario.pressureByDefault) samples.addAll(SensorModel.pressureSamples(r, rng, start))
        samples.sortWith(compareBy({ it.tNanos }, { it.kind.ordinal }))
        fun ns(s: Double) = start + Math.round(s * 1e9)
        return SyntheticTrace(
            scenario = scenario,
            rateHz = rate,
            accelRangeG = rangeG,
            seed = spec.seed,
            samples = samples,
            modeChanges = r.modeChanges.map { ModeChange(ns(it.first), it.second) },
            speedFixes = r.speeds.map { SpeedFix(ns(it.first), it.second.toFloat()) },
            eventAtNanos = if (r.eventS.isNaN()) null else ns(r.eventS),
            startNanos = start,
            endNanos = ns(last),
        )
    }

    /** SplitMix64-style seed mixing so per-purpose generators are independent. */
    private fun mix(a: Long, b: Long, c: Long): Long {
        var z = a * -0x61c8864680b583ebL + b * 0x5851f42d4c957f2dL + c * 0x14057b7ef767814fL
        z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L
        z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L
        return z xor (z ushr 31)
    }
}

/** Scenario recipes (research §5.2), all times relative to the event start [te]. */
internal object Recipes {
    private val UP = Carry.UP
    private val THIGH_HORIZONTAL = doubleArrayOf(0.0, 0.05, 1.0)

    private fun walkGait(t: Truth, a: Double, b: Double) = t.gait(a, b, 1.8, 1.7, 0.6, 0.35)
    private fun runGait(t: Truth, a: Double, b: Double) = t.gait(a, b, 2.8, 4.2, 0.35, 0.6, strikeMs = 40.0)

    fun walk(@Suppress("UNUSED_PARAMETER") te: Double, rng: Random): Truth = Truth(40.0, Carry.POCKET, rng).apply {
        mode(0.0, Mode.WALKING)
        walkGait(this, 0.0, 40.0)
        noise(0.0, 40.0, 0.02)
        speeds(0.0, 40.0) { 1.4 }
    }

    fun run(@Suppress("UNUSED_PARAMETER") te: Double, rng: Random): Truth = Truth(40.0, Carry.POCKET, rng).apply {
        mode(0.0, Mode.RUNNING)
        runGait(this, 0.0, 40.0)
        noise(0.0, 40.0, 0.04)
        speeds(0.0, 40.0) { 3.0 }
    }

    fun stairs(@Suppress("UNUSED_PARAMETER") te: Double, rng: Random): Truth = Truth(40.0, Carry.POCKET, rng).apply {
        mode(0.0, Mode.WALKING)
        gait(0.0, 40.0, 1.6, 2.4, 0.55, 0.5, strikeMs = 40.0)
        noise(0.0, 40.0, 0.03)
        speeds(0.0, 40.0) { 0.6 }
    }

    fun sitDown(te: Double, rng: Random): Truth = Truth(te + 13.0, Carry.POCKET, rng).apply {
        mode(0.0, Mode.WALKING)
        noise(0.0, te, 0.02)
        halfSineWorld(te, 0.8, -0.28, UP)
        orientTo(te, 1.0, THIGH_HORIZONTAL)
        halfSineWorld(te + 0.85, 0.12, 1.05, UP)
        noise(te + 1.0, durationS, 0.015)
        for (s in doubleArrayOf(te + 3.0, te + 6.5, te + 10.0)) sine(s, s + 0.5, 2.0, 0.08, doubleArrayOf(0.6, 0.8, 0.0))
    }

    fun sitPlop(te: Double, rng: Random): Truth = Truth(te + 13.0, Carry.POCKET, rng).apply {
        mode(0.0, Mode.WALKING)
        noise(0.0, te, 0.02)
        halfSineWorld(te, 0.45, -0.45, UP)
        orientTo(te, 0.5, THIGH_HORIZONTAL)
        halfSineWorld(te + 0.45, 0.07, 1.8, UP)
        noise(te + 0.6, durationS, 0.01)
    }

    fun kneeFall(te: Double, rng: Random): Truth = Truth(te + 17.0, Carry.POCKET, rng).apply {
        mode(0.0, Mode.WALKING)
        walkGait(this, 0.0, te)
        noise(0.0, te, 0.02)
        halfSineWorld(te, 0.3, -0.25, UP)
        rotate(te, 0.4, Math.toRadians(30.0))
        halfSineWorld(te + 0.3, 0.06, 0.95, UP)
        stillBody(te + 0.5, durationS)
    }

    fun phoneDrop(te: Double, rng: Random, pickup: Boolean): Truth = Truth(te + 13.0, Carry.HAND, rng).apply {
        mode(0.0, if (pickup) Mode.STILL else Mode.WALKING)
        noise(0.0, te, 0.02)
        freeFall(te, te + 0.45)
        orientTo(te, 0.45, doubleArrayOf(0.0, 0.0, -1.0))
        centripetal(te, te + 0.45, 6.0)
        halfSineWorld(te + 0.45, 0.015, 12.0, UP)
        freeFall(te + 0.465, te + 0.55)
        halfSineWorld(te + 0.55, 0.012, 4.0, UP)
        freeFall(te + 0.562, te + 0.60)
        halfSineWorld(te + 0.60, 0.010, 1.5, UP)
        if (pickup) {
            halfSineWorld(te + 3.0, 0.8, 0.6, UP)
            rotate(te + 3.0, 0.8, Math.toRadians(140.0))
            noise(te + 3.8, durationS, 0.03)
        }
    }

    fun carpetDrop(te: Double, rng: Random): Truth = Truth(te + 13.0, Carry.HAND, rng).apply {
        mode(0.0, Mode.STILL)
        noise(0.0, te, 0.02)
        freeFall(te, te + 0.45)
        orientTo(te, 0.45, doubleArrayOf(0.0, 0.0, -1.0))
        centripetal(te, te + 0.45, 6.0)
        halfSineWorld(te + 0.45, 0.035, 4.0, UP)
    }

    fun tossOnBed(te: Double, rng: Random): Truth = Truth(te + 13.0, Carry.HAND, rng).apply {
        mode(0.0, Mode.STILL)
        noise(0.0, te, 0.02)
        halfSineWorld(te, 0.25, 2.0, doubleArrayOf(0.7, 0.0, 0.7))
        freeFall(te + 0.25, te + 0.60)
        orientTo(te + 0.25, 0.35, doubleArrayOf(0.0, 0.14, 0.99))
        centripetal(te + 0.25, te + 0.6, 5.0)
        ringWorld(te + 0.6, te + 0.95, 1.2, 0.08, 8.0, UP)
    }

    private fun roadNoise(t: Truth, a: Double, b: Double, sigma: Double = 0.04, vib: Double = 0.03) {
        t.noise(a, b, sigma)
        t.sine(a, b, 25.0, vib)
    }

    fun pothole(te: Double, rng: Random, loose: Boolean): Truth = Truth(te + 17.0, if (loose) doubleArrayOf(0.0, 0.2, 0.98) else Carry.MOUNT, rng).apply {
        mode(0.0, Mode.VEHICLE)
        roadNoise(this, 0.0, durationS)
        speeds(0.0, durationS) { 11.1 }
        halfSineWorld(te, 0.04, -0.6, UP)
        halfSineWorld(te + 0.04, 0.06, 1.2, UP)
        ringWorld(te + 0.1, te + 0.4, 0.3, 0.1, 12.0, UP)
        if (loose) halfSineWorld(te + 0.05, 0.012, 5.0, UP)
    }

    /** New (not in the research table): a speed bump taken too fast, ≈3 g at the mount, car keeps driving. */
    fun speedBump(te: Double, rng: Random): Truth = Truth(te + 20.0, Carry.MOUNT, rng).apply {
        mode(0.0, Mode.VEHICLE)
        roadNoise(this, 0.0, durationS)
        speeds(0.0, durationS) { t ->
            when {
                t < te - 4 -> 8.3
                t < te - 1 -> 8.3 - (8.3 - 5.5) * (t - (te - 4)) / 3.0
                t < te + 2 -> 5.5
                t < te + 6 -> 5.5 + (8.3 - 5.5) * (t - (te + 2)) / 4.0
                else -> 8.3
            }
        }
        val wheelbaseS = 2.7 / 5.5
        for ((offset, amp) in listOf(0.0 to 2.0, wheelbaseS to 1.6)) {
            val t0 = te + offset
            halfSineWorld(t0, 0.10, amp, UP)
            halfSineWorld(t0 + 0.10, 0.15, -0.25 * amp, UP)
            val pitch = if (offset == 0.0) 4.0 else -3.0
            rotate(t0, 0.25, Math.toRadians(pitch))
            rotate(t0 + 0.25, 0.3, Math.toRadians(-pitch))
        }
        ringWorld(te + 0.8, te + 2.5, 0.25, 0.3, 1.5, UP)
    }

    fun hardBrake(te: Double, rng: Random, slide: Boolean): Truth = Truth(te + 22.0, if (slide) Carry.FLAT else Carry.MOUNT, rng).apply {
        mode(0.0, Mode.VEHICLE)
        roadNoise(this, 0.0, durationS)
        val v0 = 16.7
        val dec = 0.9 * G0
        val stop = te + v0 / dec
        speeds(0.0, durationS) { t -> if (t < te) v0 else max(0.0, v0 - dec * (t - te)) }
        for (i in idx(te, stop)) dynW[0][i] += -0.9 * min(1.0, (time(i) - te) / 0.3)
        if (slide) {
            halfSinePhone(te + 0.4, 0.4, 0.4, Carry.Y)
            freeFall(te + 0.8, te + 1.1)
            rotate(te + 0.8, 0.3, Math.toRadians(20.0))
            halfSineWorld(te + 1.1, 0.015, 12.0, UP)
            halfSineWorld(te + 1.13, 0.012, 2.0, UP)
        }
    }

    fun doorSlam(te: Double, rng: Random): Truth = Truth(te + 17.0, doubleArrayOf(0.3, 0.9, 0.3), rng).apply {
        mode(0.0, Mode.VEHICLE)
        mode(3.0, Mode.STILL) // parked: Activity Recognition has switched, the 60 s vehicle hold has not expired
        noise(0.0, durationS, 0.005)
        speeds(0.0, durationS) { 0.0 }
        halfSineWorld(te, 0.010, 5.0, Carry.X)
        ringWorld(te + 0.01, te + 0.09, 0.5, 0.03, 40.0, Carry.X)
    }

    fun mountedVibration(@Suppress("UNUSED_PARAMETER") te: Double, rng: Random): Truth = Truth(40.0, Carry.MOUNT, rng).apply {
        mode(0.0, Mode.VEHICLE)
        noise(0.0, 40.0, 0.06)
        sine(0.0, 40.0, 28.0, 0.05)
        speeds(0.0, 40.0) { 25.0 }
        var s = 3.0
        while (s < 38.0) {
            halfSineWorld(s, 0.03, 0.5, UP)
            s += 7.0
        }
    }

    fun fall(te: Double, rng: Random, getUp: Boolean): Truth = Truth(te + 17.0, Carry.POCKET, rng).apply {
        mode(0.0, Mode.WALKING)
        walkGait(this, 0.0, te)
        noise(0.0, te, 0.02)
        speeds(0.0, te) { 1.4 }
        speeds(te + 1.0, durationS) { 0.0 }
        halfSineWorld(te, 0.35, -0.6, UP)
        val angle = orientTo(te - 0.05, 0.45, THIGH_HORIZONTAL)
        halfSineWorld(te + 0.35, 0.05, 3.5, doubleArrayOf(0.3, 0.0, 1.0))
        halfSineWorld(te + 0.55, 0.04, 1.0, UP)
        if (!getUp) {
            stillBody(te + 0.7, durationS)
            mode(te + 6.0, Mode.STILL)
        } else {
            stillBody(te + 0.7, te + 4.0)
            rotate(te + 4.0, 1.5, -angle)
            halfSineWorld(te + 4.0, 1.5, 0.3, UP)
            walkGait(this, te + 5.5, durationS)
            noise(te + 5.5, durationS, 0.02)
        }
    }

    fun fallFromStill(te: Double, rng: Random): Truth = Truth(te + 17.0, Carry.POCKET, rng).apply {
        mode(0.0, Mode.STILL)
        noise(0.0, te, 0.01)
        halfSineWorld(te, 0.5, -0.5, UP)
        orientTo(te, 0.6, THIGH_HORIZONTAL)
        halfSineWorld(te + 0.55, 0.06, 2.2, UP)
        stillBody(te + 0.7, durationS)
    }

    fun ladderFall(te: Double, rng: Random): Truth = Truth(te + 17.0, Carry.POCKET, rng).apply {
        mode(0.0, Mode.WALKING)
        noise(0.0, te, 0.02)
        freeFall(te, te + 0.5)
        orientTo(te, 0.6, THIGH_HORIZONTAL)
        halfSineWorld(te + 0.5, 0.07, 6.0, UP)
        halfSineWorld(te + 0.65, 0.05, 1.5, UP)
        stillBody(te + 0.8, durationS)
    }

    fun runningFall(te: Double, rng: Random): Truth = Truth(te + 17.0, Carry.POCKET, rng).apply {
        mode(0.0, Mode.RUNNING)
        runGait(this, 0.0, te)
        noise(0.0, te, 0.04)
        speeds(0.0, te) { 3.0 }
        speeds(te + 2.0, durationS) { 0.0 }
        orientTo(te, 0.4, doubleArrayOf(0.0, -0.3, 0.95))
        halfSineWorld(te + 0.05, 0.25, -0.5, UP)
        halfSineWorld(te + 0.4, 0.05, 5.5, doubleArrayOf(0.5, 0.0, 1.0))
        addWorld(te + 0.45, te + 1.0, doubleArrayOf(-0.5, 0.0, 0.0))
        noise(te + 0.45, te + 1.0, 0.2)
        stillBody(te + 1.0, durationS)
    }

    fun pedestrian(te: Double, rng: Random): Truth = Truth(te + 18.0, Carry.POCKET, rng).apply {
        mode(0.0, Mode.WALKING)
        walkGait(this, 0.0, te)
        noise(0.0, te, 0.02)
        speeds(0.0, te) { 1.4 }
        speeds(te + 3.0, durationS) { 0.0 }
        halfSineWorld(te, 0.06, 14.0, doubleArrayOf(1.0, 0.0, 0.2)) // bumper
        rotate(te + 0.02, 0.8, Math.toRadians(270.0), doubleArrayOf(0.3, 0.0, 1.0))
        halfSineWorld(te + 0.14, 0.03, 6.5, doubleArrayOf(0.5, 0.0, -1.0)) // hood
        addWorld(te + 0.2, te + 1.0, doubleArrayOf(0.0, 0.0, -0.6)) // thrown
        halfSineWorld(te + 1.1, 0.04, 4.5, UP) // ground
        addWorld(te + 1.15, te + 1.9, doubleArrayOf(-0.7, 0.0, 0.0)) // slide
        noise(te + 1.15, te + 1.9, 0.15)
        stillBody(te + 1.9, durationS)
        mode(te + 6.0, Mode.STILL)
    }

    fun pedestrianLow(te: Double, rng: Random): Truth = Truth(te + 17.0, Carry.POCKET, rng).apply {
        mode(0.0, Mode.STILL)
        noise(0.0, te, 0.01)
        speeds(0.0, durationS) { 0.0 }
        halfSineWorld(te, 0.08, 5.5, Carry.X)
        rotate(te + 0.03, 0.5, Math.toRadians(100.0), doubleArrayOf(0.2, 0.0, 1.0))
        halfSineWorld(te + 0.6, 0.05, 2.2, UP)
        stillBody(te + 0.8, durationS)
    }

    /** Frontal crash: half-sine Δv pulse (research §4.1), mount flex, secondary hit, then idling. */
    fun crash(te: Double, rng: Random, kmh: Double = 50.0, pulseS: Double = 0.12, carry: DoubleArray = Carry.MOUNT, tunnel: Boolean = false): Truth =
        Truth(te + 20.0, carry, rng).apply {
            mode(0.0, Mode.VEHICLE)
            roadNoise(this, 0.0, te)
            val v = kmh / 3.6
            speeds(0.0, te, sigma = 0.3) { v }
            if (!tunnel) {
                speed(te + 1.0, 2.0)
                speeds(te + 2.0, durationS) { 0.0 }
            }
            val amp = PI * v / (2 * pulseS) / G0
            halfSineWorld(te, pulseS, amp, doubleArrayOf(-1.0, 0.0, 0.0))
            halfSineWorld(te, 0.1, 2.0, UP)
            rotate(te + 0.02, 0.15, Math.toRadians(25.0), Carry.Y)
            halfSineWorld(te + 0.4, 0.05, 3.0, doubleArrayOf(-1.0, 0.0, 0.3))
            noise(te + 0.6, durationS, 0.01)
            sine(te + 0.6, durationS, 25.0, 0.02)
            mode(te + 8.0, Mode.STILL) // Activity Recognition catches up after the crash
        }

    fun bikeCrash(te: Double, rng: Random): Truth = Truth(te + 16.0, Carry.POCKET, rng).apply {
        mode(0.0, Mode.CYCLING)
        noise(0.0, te, 0.08)
        sine(0.0, te, 1.3, 0.2, doubleArrayOf(0.0, 0.3, 0.95))
        for (i in idx(0.0, te)) swing[i] += 0.6 * kotlin.math.sin(2 * PI * 1.3 * time(i))
        speeds(0.0, te) { 5.5 }
        speeds(te + 2.0, durationS) { 0.0 }
        orientTo(te, 0.5, doubleArrayOf(0.9, 0.1, 0.4), Carry.Y)
        halfSineWorld(te + 0.3, 0.06, 4.5, doubleArrayOf(0.4, 0.0, 1.0))
        addWorld(te + 0.36, te + 0.9, doubleArrayOf(-0.6, 0.0, 0.0))
        noise(te + 0.36, te + 0.9, 0.2)
        stillBody(te + 0.9, durationS)
        mode(te + 6.0, Mode.STILL)
    }
}
