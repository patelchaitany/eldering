package com.eldering.detection

/**
 * Keys of [DetectionEvent.features] and [CandidateOutcome.features]. Names follow the build task; the research
 * name (docs/research/detection-params.md §1.2) is given for each. Keys whose input is unavailable are omitted,
 * never written as 0 or NaN: gyro features without a gyroscope, speeds without GPS fixes in their window, and
 * pressure without a barometer.
 */
public object FeatureKeys {
    /** Peak SMV in g over [t0−0.2 s, t0+0.3 s] (`smvPeakG`); a lower bound when [SATURATED_MS] > 0. */
    public const val PEAK_G: String = "peak_g"

    /** Time with any axis at ≥ 97 % of the accelerometer range in [t0−0.2 s, t0+0.5 s], ms (`satMs`). */
    public const val SATURATED_MS: String = "saturated_ms"

    /** Contiguous time at SMV ≥ 2 g around t0, ms (`aboveTwoGMs`). */
    public const val ABOVE_2G_MS: String = "above_2g_ms"

    /** Distinct impacts ≥ 1.8 g in [t0−0.2 s, t0+1 s] (`impacts1s`). */
    public const val IMPACTS_1S: String = "impacts_1s"

    /** Distinct impacts ≥ 1.8 g in [t0−0.05 s, t0+1.5 s] (`impacts15s`). */
    public const val IMPACTS_1_5S: String = "impacts_1_5s"

    /** Longest run of SMV ≤ 0.6 g in the second before t0, ms (`freeFallMs`). */
    public const val FREEFALL_MS: String = "freefall_ms"

    /** Minimum SMV in the second before t0, g (`minSmvPreG`). */
    public const val MIN_SMV_PRE_G: String = "min_smv_pre_g"

    /** Longest run of SMV ≤ 0.35 g ending at most 150 ms before t0, ms (`trueFreeFallMs`). */
    public const val TRUE_FREEFALL_MS: String = "true_freefall_ms"

    /** Longest run of SMV ≤ 0.35 g in (t0+50 ms, t0+1 s], ms (`airborneAfterMs`). */
    public const val AIRBORNE_AFTER_MS: String = "airborne_after_ms"

    /** As [AIRBORNE_AFTER_MS] but only runs ending before t0+0.6 s, ms (`bounceAirborneMs`). */
    public const val BOUNCE_AIRBORNE_MS: String = "bounce_airborne_ms"

    /** g0·∫(SMV−1)dt over the pre-impact free-fall run, m/s; logged only (`vertVelMps`). */
    public const val VERTICAL_VELOCITY_MPS: String = "vert_vel_mps"

    /** Peak |d SMV/dt| within ±0.1 s of t0 on the 100 Hz grid, g/s (`jerkPeakGps`). */
    public const val JERK_PEAK_GPS: String = "jerk_peak_gps"

    /** Max net gyro rotation over [t0−1 s, t0+0.5 s], degrees (`gyroNetRotDeg`). */
    public const val ROTATION_DEG: String = "rotation_deg"

    /** Max net rotation inside any 0.5 s window starting in [t0−1 s, t0], degrees (`fastRot05sDeg`). */
    public const val FAST_ROTATION_DEG: String = "fast_rotation_deg"

    /** Net rotation in [t0−0.5 s, t0], degrees (`preRotDeg`). */
    public const val PRE_ROTATION_DEG: String = "pre_rotation_deg"

    /** Net rotation in [t0, t0+1 s], degrees (`strikeRotDeg`). */
    public const val STRIKE_ROTATION_DEG: String = "strike_rotation_deg"

    /**
     * Max net rotation inside any 0.5 s window of [t0−2 s, t0−0.5 s], degrees: the rotation the ongoing activity
     * (e.g. thigh swing while walking) produces, used to judge whether [PRE_ROTATION_DEG] is unusual.
     */
    public const val BASELINE_ROTATION_DEG: String = "baseline_rotation_deg"

    /** Angle between the gravity direction before ([t0−2, t0−0.5) s) and after ([t0+1.5, t0+3.5) s), degrees (`orientChangeDeg`). */
    public const val ORIENTATION_CHANGE_DEG: String = "orientation_change_deg"

    /** 1 when the phone was tilted ≥ 30° from upright before the event, i.e. likely hand-held (`handHeldBefore`). */
    public const val HAND_HELD_BEFORE: String = "hand_held_before"

    /** Horizontal share of the dynamic acceleration at t0, 0..1 (`horizRatio`). */
    public const val HORIZONTAL_RATIO: String = "horiz_ratio"

    /** ∫|a − g| dt over the ≥ 1.5 g region around t0, m/s; a lower bound when clipped (`dvLowerBoundMps`). */
    public const val DV_LOWER_BOUND_MPS: String = "dv_lower_bound_mps"

    /** Share of 1 s windows in [t0+2 s, t0+W] whose SMV std is ≤ 0.05 g (`stillFraction`). */
    public const val STILL_FRACTION: String = "still_fraction"

    /** Median SMV variance of the 1 s post-event windows, g² (square of `restNoiseG`). */
    public const val STILLNESS_VAR: String = "stillness_var"

    /** Median SMV std of the 1 s post-event windows, g (`restNoiseG`). */
    public const val REST_NOISE_G: String = "rest_noise_g"

    /** 1 when gait resumed or the phone was picked up within the post window (`recovered`). */
    public const val RECOVERED: String = "recovered"

    /** SMV autocorrelation (gait periodicity) before the event (`gaitPre`). */
    public const val GAIT_PRE: String = "gait_pre"

    /** SMV autocorrelation (gait periodicity) right after the event (`gaitPost`). */
    public const val GAIT_POST: String = "gait_post"

    /** Median GPS speed over [t0−15 s, t0−0.5 s], m/s (`speedBeforeMps`). */
    public const val SPEED_BEFORE: String = "speed_before"

    /** Max GPS speed over [t0+3 s, t0+W], m/s (`speedAfterMps`). */
    public const val SPEED_AFTER: String = "speed_after"

    /** Median pressure after minus before, hPa; positive means the phone is lower (`pressureDeltaHpa`). */
    public const val PRESSURE_DELTA_HPA: String = "pressure_delta_hpa"

    /** Accelerometer rate around t0, Hz (`rateHz`). */
    public const val RATE_HZ: String = "rate_hz"

    /** Effective accelerometer full-scale range, g (`accelRangeG`). */
    public const val ACCEL_RANGE_G: String = "accel_range_g"

    /** Number of accelerometer data gaps inside the analysis window (`gapCount`). */
    public const val GAP_COUNT: String = "gap_count"

    /** [Mode.ordinal] reported at t0. */
    public const val MODE: String = "mode"

    /** 1 when the candidate was evaluated in vehicle context (VEHICLE within the hold time, or fast GPS speed). */
    public const val VEHICLE_CONTEXT: String = "vehicle_context"

    /** Shadow scores (0..1+, before clamping) of each rule that reached scoring; logged for tuning (`crashScore` etc.). */
    public const val CRASH_SCORE: String = "crash_score"
    public const val STRIKE_SCORE: String = "strike_score"
    public const val FALL_SCORE: String = "fall_score"

    /**
     * Prefix of the confidence parts of the emitted kind: each scoring term that contributed is stored as
     * `conf_<term>` with its signed contribution, so the parts sum to the unclamped score
     * (for example `conf_base`, `conf_stopped_after`, `conf_still_after`).
     */
    public const val CONFIDENCE_PART_PREFIX: String = "conf_"
}
