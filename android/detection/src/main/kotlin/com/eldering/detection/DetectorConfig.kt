package com.eldering.detection

/**
 * Per-mode thresholds. Accelerations are in g (1 g = 9.80665 m/s²).
 *
 * @property triggerG cheap per-sample trigger (before rate/range scaling).
 * @property fallMinG FALL requires the SMV peak to reach at least this.
 * @property fallMediumG "violence" vote and score tier 2.
 * @property fallStrongG score tier 1.
 * @property jerkGps jerk (g/s) needed for the "violence" vote.
 * @property nominalRateHz sensor rate the app requests in this mode (SPEC battery tiers).
 */
public data class ModeThresholds(
    val triggerG: Float,
    val fallMinG: Float,
    val fallMediumG: Float,
    val fallStrongG: Float,
    val jerkGps: Float,
    val nominalRateHz: Int,
)

/** Default per-mode thresholds from docs/research/detection-params.md §2. */
public val DEFAULT_MODES: Map<Mode, ModeThresholds> = mapOf(
    Mode.STILL to ModeThresholds(1.8f, 1.8f, 2.4f, 3.0f, 50f, 25),
    Mode.WALKING to ModeThresholds(1.8f, 1.8f, 2.4f, 3.0f, 50f, 50),
    Mode.UNKNOWN to ModeThresholds(1.8f, 1.8f, 2.4f, 3.0f, 50f, 50),
    Mode.RUNNING to ModeThresholds(3.5f, 3.5f, 4.5f, 5.5f, 150f, 50),
    Mode.CYCLING to ModeThresholds(3.0f, 3.0f, 4.0f, 5.0f, 100f, 50),
    Mode.VEHICLE to ModeThresholds(2.5f, 3.0f, 3.5f, 4.5f, 100f, 100),
)

/**
 * Detector tuning. Defaults are the starting values from docs/research/detection-params.md §2.1;
 * none has been validated on real accidents, so treat them as tunable.
 *
 * Deviations from and additions to the research, each found with the synthetic scenario matrix and documented on its
 * field: [supersedeWindowMs] / [supersedeRatio], [auxStreamWaitMs], [strikePreRotGaitMarginDeg],
 * [strikeNoGyroMaxFreeFallMs], [noGyroOrientationVote], [cyclingSpeedIsNotVehicle]. Behavioural details without a
 * knob: the VEHICLE debounce window is max(30 ms, 1.5 sample periods) so a 25 Hz stream can still trigger; the impact's
 * sampling rate is measured before t0 (the app raises the rate after a trigger); "kept driving" also applies when no
 * speed before the crash is known; clip-plateau inference needs bit-identical readings near a standard range.
 */
public data class DetectorConfig(
    val modes: Map<Mode, ModeThresholds> = DEFAULT_MODES,
    // windows
    val preWindowMs: Long = 2_000,
    val postWindowMs: Long = 10_000,
    val vehiclePostWindowMs: Long = 15_000,
    val candidateSearchMs: Long = 500,
    val candidateRearmMs: Long = 300,
    /**
     * Addition to the research pipeline: a candidate followed within this time by a clearly larger impact
     * ([supersedeRatio] × its impulse above 1 g) is evaluated after that impact instead of before it, because its
     * post-event features (posture, stillness, gait) would describe the later event (e.g. a running stride 2 s
     * before a fall). If the larger impact is rejected, the deferred candidate is still evaluated.
     */
    val supersedeWindowMs: Long = 3_500,
    val supersedeRatio: Float = 1.2f,
    val cooldownMs: Long = 60_000,
    val vehicleHoldMs: Long = 60_000,
    val vehicleDebounceMs: Long = 30,
    // sampling / saturation
    val gridHz: Int = 100,
    val saturationFraction: Float = 0.97f,
    val maxGapMs: Long = 100,
    val bodyImpactMinMs: Float = 80f,
    val minCaptureFactor: Float = 0.7f,
    val rangeHeadroom: Float = 0.9f,
    /** Range assumed until [AccidentDetector.setAccelMaxRange] is called (the widest common range, so clipping is never over-reported). */
    val defaultAccelRangeG: Float = 16f,
    /** Samples used for the running rate estimate (median Δt) that scales the trigger threshold. */
    val rateEstimateSamples: Int = 64,
    /** Clipping inference: this many consecutive identical |axis| readings at ≥ [plateauMinG] mean the sensor clips there. */
    val plateauSamples: Int = 3,
    val plateauMinG: Float = 1.9f,
    /** A gyro interval is analysed only if samples reach within max(3 periods, this) of both ends. */
    val gyroCoverageToleranceMs: Long = 50,
    /**
     * Android delivers each sensor's batch separately (accelerometer first, gyroscope after), so a candidate whose
     * accelerometer window is complete waits for gyro/pressure streams that were active around the event to catch
     * up, for at most this much further accelerometer time. With batches longer than this the late stream's
     * features are treated as missing (the gyro-less rules apply).
     */
    val auxStreamWaitMs: Long = 20_000,
    // free fall / airborne
    val partialFreeFallG: Float = 0.6f,
    val partialFreeFallMinMs: Long = 60,
    val trueFreeFallG: Float = 0.35f,
    val trueFreeFallMinMs: Long = 200,
    val trueFreeFallMaxLeadMs: Long = 150,
    val airborneMinMs: Long = 150,
    val bounceAirborneMinMs: Long = 40,
    /** "Bounce" airborne runs must end before t0 + this (a bouncing phone; a body never goes airborne between ground hits). */
    val bounceWindowMs: Long = 600,
    // rotation / orientation
    val fastRotationDeg: Float = 75f,
    val fallRotationDeg: Float = 60f,
    val orientMinDeg: Float = 35f,
    val orientStrongDeg: Float = 60f,
    val noGyroOrientVoteDeg: Float = 60f,
    /**
     * Deviation from research §2.3: without a gyroscope the orientation change is *not* counted as a violence vote by
     * default, because it is already the posture gate (double counting made a 25 Hz gyro-less sit-down a FALL).
     * Set true for the research behaviour (vote when orientChange ≥ [noGyroOrientVoteDeg]).
     */
    val noGyroOrientationVote: Boolean = false,
    val impulseMaxOrientDeg: Float = 15f,
    val impulseMaxRotDeg: Float = 20f,
    val impulseMinPreG: Float = 0.6f,
    val handHeldTiltDeg: Float = 30f,
    // stillness / recovery
    val stillStdG: Float = 0.05f,
    val stillFractionMin: Float = 0.7f,
    val restNoiseG: Float = 0.012f,
    val gaitAutocorrMin: Float = 0.5f,
    val gaitMinStdG: Float = 0.08f,
    val pickupOrientDeg: Float = 30f,
    val pickupStdG: Float = 0.08f,
    // impacts
    val impactCountG: Float = 1.8f,
    val impactMinSeparationMs: Long = 100,
    val impactValleyDropG: Float = 0.5f,
    val sharpImpactMaxMs: Long = 30,
    val sharpMinRateHz: Float = 40f,
    val aboveG: Float = 2.0f,
    val dvRegionG: Float = 1.5f,
    // VEHICLE_CRASH
    val crashSpeedGateMps: Float = 4.2f, // 15 km/h
    val crashSpeedHighMps: Float = 7.0f, // 25 km/h
    val speedImpliesVehicleMps: Float = 8.0f, // 29 km/h
    /**
     * Deviation from research §0.3: a fast GPS speed does not imply vehicle context while Activity Recognition
     * reports CYCLING (road cyclists exceed 29 km/h; a cycling crash must stay a FALL candidate).
     */
    val cyclingSpeedIsNotVehicle: Boolean = true,
    val crashImpactG: Float = 4.0f,
    val crashStrongG: Float = 8.0f,
    val crashMinSatMs: Long = 10,
    val crashStrongSatMs: Long = 20,
    val crashEnergyMs: Long = 40,
    val crashMinDvMps: Float = 2.0f,
    val crashStrongDvMps: Float = 4.0f,
    val stoppedMps: Float = 1.5f,
    val continuedFraction: Float = 0.5f,
    val crashRotationDeg: Float = 45f,
    val cabinPressureSpikeHpa: Float = 0.5f, // bonus only; no published value
    // PEDESTRIAN_IMPACT
    val strikeImpactG: Float = 5.0f,
    val strikeMinSatMs: Long = 10,
    val strikeMaxPreRotDeg: Float = 30f,
    /**
     * Without a gyroscope, "impact first" is judged by the absence of a pre-impact free fall: the strike gate requires
     * [FeatureKeys.FREEFALL_MS] below this (research §2.3 lets the gate pass, which made gyro-less falls strikes).
     */
    val strikeNoGyroMaxFreeFallMs: Long = 60,
    val strikeRotDeg: Float = 90f,
    /**
     * Deviation from research §2.3 (gate `preRot ≤ 30°`): a thigh-pocket phone swings up to ≈40° per 0.5 s while
     * walking, so the gate also passes when the pre-impact rotation is within this margin of the activity's own
     * [FeatureKeys.BASELINE_ROTATION_DEG] *and* the body then rotates ≥ [strikeRotDeg] (impact first, rotation after).
     */
    val strikePreRotGaitMarginDeg: Float = 10f,
    val strikeHorizRatio: Float = 0.6f,
    val strikeDvMps: Float = 3.0f,
    val strikeOrientDeg: Float = 45f,
    val tossMaxPeakG: Float = 5.0f,
    // FALL
    val fallInMovingVehicleMinG: Float = 3.0f,
    /** FALL treats the impact as saturated (gate and strong tier) when at least this long is clipped. */
    val fallMinSatMs: Long = 5,
    val fallPressureRiseHpa: Float = 0.04f,
    val minViolenceVotes: Int = 2,
    // emission thresholds
    val emitCrash: Float = 0.60f,
    val emitStrike: Float = 0.70f,
    val emitFall: Float = 0.65f,
    val crashNoSpeedAfterCap: Float = 0.70f,
) {
    init {
        val missing = Mode.entries.filterNot { it in modes }
        require(missing.isEmpty()) { "DetectorConfig.modes is missing thresholds for $missing" }
        modes.forEach { (mode, th) ->
            require(th.triggerG > 0f && th.fallMinG > 0f && th.nominalRateHz > 0) { "invalid thresholds for $mode: $th" }
        }
        require(preWindowMs >= 2_000) { "preWindowMs must cover the 2 s pre-event features, was $preWindowMs" }
        require(postWindowMs >= 5_000 && vehiclePostWindowMs >= 5_000) { "post windows must cover the 3.5 s posture window plus stillness" }
        require(candidateSearchMs > 0 && candidateRearmMs > 0 && cooldownMs >= 0) { "candidate timing must be positive" }
        require(supersedeWindowMs >= 0 && supersedeRatio >= 1f && auxStreamWaitMs >= 0) { "supersede/wait settings out of range" }
        require(gridHz in 50..400) { "gridHz must be in 50..400, was $gridHz" }
        require(saturationFraction in 0.5f..1f) { "saturationFraction must be in 0.5..1" }
        require(rangeHeadroom in 0.5f..1f) { "rangeHeadroom must be in 0.5..1" }
        require(defaultAccelRangeG >= 2f) { "defaultAccelRangeG must be at least 2 g" }
        require(rateEstimateSamples >= 4) { "rateEstimateSamples must be at least 4" }
        require(plateauSamples >= 2) { "plateauSamples must be at least 2" }
    }

    /** Longest post-event window W, ms. */
    internal val maxPostWindowMs: Long get() = maxOf(postWindowMs, vehiclePostWindowMs)

    /** Thresholds for [mode]; every mode is guaranteed present by construction. */
    public fun thresholdsFor(mode: Mode): ModeThresholds = modes.getValue(mode)
}
