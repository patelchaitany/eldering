# Detection parameters: multi-sensor accident detection (v1)

Feeds the `:detection` module (SPEC.md, section "Android app"). Research date 2026-10-12/13.
Every threshold here is a **starting default**. None has been validated on real accidents (no public phone dataset of crashes or pedestrian strikes exists; see `reports/`).

**Evidence tags**
- **[V]** verified this session from a primary source.
- **[N]** from the project notes in `research_notes/`, sourced earlier and treated here as secondary.
- **[S]** seen only in a search-result abstract or snippet; the full text was not read.
- **[D]** derived from physics or maths; reproducible.
- **[E]** engineering estimate; tune with field data.
- **[P]** checked in the reference prototype `docs/research/detection-proto/` (numpy, synthetic data only).

---

## 0. Decisions at a glance

1. **Units and time.** The detector works internally in g, using `g0 = 9.80665 m/s²`. Time is always `SensorEvent.timestamp` (ns, `elapsedRealtimeNanos` base), never arrival time **[V: CDD 16 §7.3 C-1-6]**.
2. **Pipeline:**
   1. Per-sample trigger.
   2. Candidate `t0` = argmax of SMV in `[trigger, trigger+500 ms]`.
   3. Buffer from `t0−2 s` to `t0+W`, where W = 10 s (15 s in VEHICLE).
   4. Compute features.
   5. Evaluate in order: VEHICLE_CRASH, then PEDESTRIAN_IMPACT, then FALL. Global suppressions run before all three.
   6. Emit if the score is at or above the kind's threshold, then apply a 60 s cooldown.
   7. A candidate that is rejected re-arms after 300 ms, so the next impact still gets evaluated.
3. **Modes.** All three EventKinds stay armed in every Mode. The Mode only changes thresholds and gates. Two context flags drive the gates:
   - `vehicleCtx` = effective mode is VEHICLE (reported now or within the last 60 s), or speedBefore ≥ 8 m/s.
   - `movingInVehicle` = `vehicleCtx` and (speedBefore ≥ 4.2 m/s or speedBefore unknown).

   With those flags:
   - VEHICLE_CRASH requires `vehicleCtx`.
   - PEDESTRIAN_IMPACT is off only while `movingInVehicle`.
   - FALL while `movingInVehicle` (for example a standing bus passenger) needs ≥ 3 g plus a ≥ 35° orientation change.
4. **Pressure is bonus-only.** The target device (OnePlus CPH2717) has **no barometer**: there is no `android.hardware.sensor.barometer` feature and no pressure sensor in `dumpsys sensorservice` **[V: device, 2026-10-12]**. No gate requires pressure (see §6).
5. **Sampling rates and the 200 Hz cap.** Android caps `registerListener` at **200 Hz** unless the app holds `HIGH_SAMPLING_RATE_SENSORS`. The cap applies regardless of that permission when the user's mic toggle is off **[V: Android sensors overview]**.
   - v1 rates follow SPEC: 25 Hz STILL, 50 Hz WALKING/UNKNOWN, 100 Hz VEHICLE. SPEC does not set RUNNING or CYCLING rates; I chose 50 Hz for both.
   - **Interpretation of SPEC:** I read "possible accident → full rate" as **200 Hz** (the cap without the permission), not the device's 400 Hz.
   - **Detection does not need** `HIGH_SAMPLING_RATE_SENSORS`. The impact has already been sampled at the mode rate by the time the rate is raised, so the higher rate only helps the post-event window.
   - `docs/research/android-background.md` declares the permission for a different reason: it is a prerequisite for the `health` FGS type. Declaring it is harmless here; keep requested rates ≤ 200 Hz either way.
6. **Prototype result.** I implemented every rule in §1–§3 and every recipe in §5 in the reference prototype **[P]**.
   - **32/32** scenarios produce the expected outcome. Each was also re-run with 6 jitter/noise seeds plus 2 alternate rates, and all 8 variants of every scenario matched.
   - Known residual false positive: a hard "plop" sit-down. Known false negative: a low-energy fall onto the knees. Both are documented in §5.4 and match the literature's real-world limits.

### Target device facts (from `adb shell dumpsys sensorservice`, `pm list features`, 2026-10-12) [V]

| Item | Value |
|---|---|
| Accelerometer | `bmi2xy acc` (Bosch BMI2xx family). Continuous, minRate 5 Hz, **maxRate 400 Hz**, FIFO (max, reserved) = **(4500, 3000)** events, **non-wake-up** |
| Gyroscope | `bmi2xy gyro`, same rates and FIFO, non-wake-up |
| Virtual sensors | gravity and linear_acc (MTK): 50–200 Hz, no batching. significant_motion: wake-up. step_detector: a wake-up variant exists |
| Vendor sensors (not public API; portability unknown) | `qti.sensor.free_fall` (wake-up, special-trigger), `android.sensor.pocket`, `qti.sensor.lay_detect`, `pickup_detect`. **Not used** by `:detection` |
| Max range | Not exposed by dumpsys, so read `Sensor.getMaximumRange()` at runtime. BMI2xx parts support ±2/4/8/16 g **[S: BMI270 datasheet]**. The CDD minimum is ±4 g **[V]** |
| Observed delivery | Another client requested 30 ms. Hardware timestamps were spaced 29.6 ms and evenly, but arrived in batches. **One 49 ms gap in 50 samples** (a dropped sample). This is real jitter, so handle gaps |
| Barometer | **Absent** |

---

## 1. Feature definitions

### 1.1 Notation and windows

- **Samples.**
  - Raw accel `a_k` (m/s², includes gravity) at time `t_k`.
  - SMV: `S_k = |a_k| / g0`, in g.
  - Gyro `ω_j` (rad/s) at time `τ_j`.
  - Speeds `v_i` (m/s) at time `u_i`, from `onSpeed`.
  - Pressure `p_m` (hPa), optional.
- **`t0`** = time of the max SMV sample in `[t_trig, t_trig + 500 ms]`. The earliest sample wins ties, such as saturated plateaus.
- **Uniform grid.** Accel is linearly interpolated onto a **100 Hz** grid over `[t0−2 s, t0+W]`. The grid is used for jerk, stillness, autocorrelation and recovery. Peaks, saturation, free-fall runs and durations always use **raw** samples.
- **Gravity estimates.**
  - `ĝ_pre = normalize(mean a over [t0−2.0 s, t0−0.5 s])`
  - `ĝ_post = normalize(mean a over [t0+1.5 s, t0+3.5 s])`
  - `ĝ_imp = normalize(mean a over [t0−0.3 s, t0−0.05 s])`
- **Window sources.** Pre/post windows follow Chen (1 s before, 2 s after), Kangas (posture 2 s after impact), Bourke 2010 (posture from t+1 to t+3 s) and Figueiredo (first vs last 1 s of a 4 s window) **[V: Bagalà 2012; Figueiredo 2016]**.

### 1.2 Features

Each feature below is emitted into `DetectionEvent.features` under the key shown in brackets.

**Impact magnitude**
- **SMV peak** (`smvPeakG`). `max S_k` for `t_k ∈ [t0−0.2, t0+0.3] s`. If saturated (`satMs > 0`), treat it as a **lower bound**. Basis: Bourke UFT, WreckWatch 4 g, e-NOTIFY 5 g **[V]/[N]**.
- **Saturation time** (`satMs`). Σ sample period over samples in `[t0−0.2, t0+0.5]` with any `|a_axis| ≥ 0.97·range`. Sharper than the peak when the sensor clips **[N: AGH ICCS 2021]**.
- **Time above 2 g** (`aboveTwoGMs`). Length of the contiguous run of `S ≥ 2 g` that contains `t0`, as n samples × median period. Duration gate: drops and slams last milliseconds, crashes last tens to hundreds of milliseconds **[N: Damoov, AGH]**.
- **Impacts in 1 s** (`impacts1s`). Local maxima with `S ≥ 1.8 g` in `[t0−0.2, t0+1.0]`.
  - Peaks must be ≥ 100 ms apart, and S must dip ≥ 0.5 g below the smaller peak between them; otherwise keep the larger peak.
  - `impacts15s` is the same count over `[t0−0.05, t0+1.5]`; the strike rule uses it.
  - The strike signature is the bumper hit, then the hood/head hit about 125–141 ms later, then the ground **[N: Forman IRCOBI 2015]**.

**Free fall and airborne phases**
- **Partial free fall before impact** (`freeFallMs`, `minSmvPreG`). Longest run of `S ≤ 0.6 g` inside `[t0−1.0 s, t0)`; run duration = `t_last − t_first + period`. Basis: Kangas start-of-fall 0.6 g with impact within 1 s; Bourke 2010 LFT 0.65 g **[V: Bagalà 2012]**.
- **True free fall** (`trueFreeFallMs`). Longest run of `S ≤ 0.35 g` ending within 150 ms before `t0`.
  - This means the device is not on a body, or the whole body is falling from height.
  - The 0.35 g ceiling leaves room for centripetal readings from a tumbling phone: ω²r ≈ 0.2 g at 8 rad/s, r = 3 cm **[D]**.
- **Airborne after impact** (`airborneAfterMs`). Longest run of `S ≤ 0.35 g` inside `(t0+50 ms, t0+1 s]`. A tossed phone, or a body thrown after a strike **[E]**.
- **Bounce airborne** (`bounceAirborneMs`). The same, but restricted to runs ending before `t0+0.6 s`. A bouncing phone; a body never goes airborne between ground impacts **[E]**.
- **Vertical velocity** (`vertVelMps`). `g0·∫(S−1)dt` from the start of the trough to `t0`. Optional, log only. Basis: Bourke 2010 threshold −0.7 m/s **[V: Bagalà 2012]**.

**Jerk**
- **Jerk peak** (`jerkPeakGps`). On the 100 Hz grid, `J_k = (S_{k+1} − S_{k−1})/0.02 s`; take `max|J|` in `[t0−0.1, t0+0.1]`, in g/s.
- Its value depends on the sample rate, so it only counts as 1 of the 4 "violence" votes (§2.3) **[E]**.

**Rotation (gyro)**
- **Gyro net rotation** (`gyroNetRotDeg`). Integrate a quaternion over raw gyro samples in `[t0−1.0, t0+0.5]` (snippet below). Report the **max net angle from the window start**.
  - Use net rotation, not `∫|ω|dt` or peak |ω|: a thigh-pocket phone swings at about 3–4 rad/s in normal gait. This is why trunk thresholds such as 3.1 rad/s do not transfer **[E]**.
  - Basis: Bourke & Lyons 2008 trunk thresholds 3.1 rad/s and 0.59 rad **[S]**.
- **Fast rotation** (`fastRot05sDeg`). Max net rotation inside any 0.5 s sub-window. Sub-windows start every 100 ms from `t0−1.0` to `t0` **[P]**. A fall turns about 75–90° in ≤ 0.5 s; sitting down takes about 1 s.
- **Rotation before the strike** (`preRotDeg`). Net rotation in `[t0−0.5, t0]`. In a strike the impact comes **before** the rotation; in a fall the rotation comes first **[D]/[E]**.
- **Rotation after the strike** (`strikeRotDeg`). Net rotation in `[t0, t0+1.0]`. The body wraps or vaults over the car **[N: Ravani trajectories]**.

**Orientation and direction**
- **Orientation change** (`orientChangeDeg`). `acos(ĝ_pre·ĝ_post)`. Basis: Figueiredo CA 35° **[V]**; Bourke posture > 60° **[V: Bagalà 2012]**.
- **Hand-held before** (`handHeldBefore`). 1 if `acos(|ĝ_pre·ŷ|) ≥ 30°`, meaning the phone was not upright the way it sits in a trouser pocket. Used for drop context **[E/P]**.
- **Horizontal ratio** (`horizRatio`). With `d = a(t0) − g0·ĝ_imp`, the ratio is `|d − (d·ĝ_imp)ĝ_imp| / |d|`. A strike is mostly horizontal at first contact **[E]**.
- **Δv lower bound** (`dvLowerBoundMps`). `∫|a_k − g0·ĝ_pre| dt` over the contiguous run of `S ≥ 1.5 g` around `t0` (trapezoid rule). If the sensor clipped, this is a lower bound (§4.1) **[D]**.

**After the event**
- **Stillness** (`stillFraction`, `restNoiseG`). Take 1 s windows stepped by 0.25 s over `[t0+2 s, t0+W]` on the grid.
  - `stillFraction` = share of windows with std(S) ≤ 0.05 g.
  - `restNoiseG` = median of the window stds.
  - Basis: ADXL345 app-note inactivity 0.1875 g for 2 s **[S]**; Kangas posture 2 s after impact **[V]**. The CDD caps accel noise at std ≤ 0.05 m/s² per axis (0.005 g) **[V]**.
- **Recovered** (`recovered`). Set to 1 if either holds:
  - SMV autocorrelation ≥ 0.5 at a lag of 0.35–1.3 s inside `[t0+2, t0+W]`, with std ≥ 0.08 g (gait has resumed).
  - Pickup: `angle(mean a over [t0+1.5, t0+3.5], mean a over the last 2 s) ≥ 30°`, and max window std ≥ 0.08 g.
  - Basis: the Sony patent suppresses an alert when gait recovers quickly **[N]** **[E/P]**.
- **Gait before and after** (`gaitPre`, `gaitPost`). Max SMV autocorrelation at lags of 0.25–0.8 s over `[t0−2, t0−0.3]` and `[t0+0.5, t0+3.5]`; returns 0 when std < 0.08 g. Used for the running-stride and stairs rule **[E/P]**.

**Speed and pressure**
- **Speed before** (`speedBeforeMps`) = median of `v_i` with `u_i ∈ [t0−15 s, t0−0.5 s]`. **Speed after** (`speedAfterMps`) = max of `v_i` with `u_i ∈ [t0+3 s, t0+W]`. Either is absent when it has no samples. Basis: WreckWatch speed gate and Apple's speed-conditioned logic **[N]**.
- **Pressure delta** (`pressureDeltaHpa`). `median p[t0+2, t0+5] − median p[t0−3, t0−0.5]`. A positive value means the phone is lower. 1 m ≈ **0.12 hPa** near sea level **[D]**. Only short relative windows are usable **[N: P2Est]**. The key is **absent** on this device.

**Also emitted (context):** `rateHz`, `accelRangeG`, `gapCount`, `mode`, and `strikeScore`/`fallScore`/`crashScore` (shadow scores for logging).

### 1.3 Snippets (pure Kotlin, no Android imports)

```kotlin
const val G0 = 9.80665f

fun smvG(x: Float, y: Float, z: Float) = sqrt(x * x + y * y + z * z) / G0

fun isSaturated(s: SensorSample, rangeMs2: Float, frac: Float = 0.97f) =
    abs(s.x) >= frac * rangeMs2 || abs(s.y) >= frac * rangeMs2 || abs(s.z) >= frac * rangeMs2

/** Longest run (ms) of samples with smv <= thr inside [fromNs, toNs); a gap > maxGapNs breaks the run. */
fun longestRunMs(t: LongArray, smv: FloatArray, fromNs: Long, toNs: Long, thr: Float, maxGapNs: Long, periodNs: Long): Float {
    var best = 0L; var start = -1L; var prev = Long.MIN_VALUE
    for (i in t.indices) {
        if (t[i] < fromNs || t[i] >= toNs) continue
        val gap = prev != Long.MIN_VALUE && t[i] - prev > maxGapNs
        if (smv[i] <= thr) {
            if (start < 0 || gap) start = t[i]
            best = maxOf(best, t[i] - start + periodNs)
        } else start = -1L
        prev = t[i]
    }
    return best / 1e6f
}

/** Max net rotation angle (deg) reached within [fromNs, toNs], quaternion integration of raw gyro. */
fun maxNetRotationDeg(t: LongArray, wx: FloatArray, wy: FloatArray, wz: FloatArray, fromNs: Long, toNs: Long): Float {
    var qw = 1.0; var qx = 0.0; var qy = 0.0; var qz = 0.0; var best = 0.0; var last = -1
    for (i in t.indices) {
        if (t[i] < fromNs || t[i] > toNs) continue
        if (last >= 0) {
            val dt = (t[i] - t[last]) / 1e9
            val ox = 0.5 * (wx[i] + wx[last]); val oy = 0.5 * (wy[i] + wy[last]); val oz = 0.5 * (wz[i] + wz[last])
            val n = sqrt(ox * ox + oy * oy + oz * oz); val ang = n * dt
            if (ang > 1e-12) {
                val s = sin(ang / 2) / n; val c = cos(ang / 2)
                val dx = ox * s; val dy = oy * s; val dz = oz * s
                val nw = qw * c - qx * dx - qy * dy - qz * dz; val nx = qw * dx + qx * c + qy * dz - qz * dy
                val ny = qw * dy - qx * dz + qy * c + qz * dx; val nz = qw * dz + qx * dy - qy * dx + qz * c
                val norm = sqrt(nw * nw + nx * nx + ny * ny + nz * nz)
                qw = nw / norm; qx = nx / norm; qy = ny / norm; qz = nz / norm
            }
            best = maxOf(best, Math.toDegrees(2 * acos(min(1.0, abs(qw)))))
        }
        last = i
    }
    return best.toFloat()
}

/** Max Pearson autocorrelation of SMV over lags [loS, hiS] on the uniform grid; 0 if std < minStd (no gait). */
fun gaitAutocorr(x: FloatArray, gridHz: Int, loS: Float, hiS: Float, minStd: Float = 0.08f): Float {
    if (x.size < gridHz / 2) return 0f
    val mean = x.average()
    val std = sqrt(x.sumOf { (it - mean) * (it - mean) } / x.size)
    if (std < minStd) return 0f
    var best = 0.0
    for (lag in (loS * gridHz).toInt()..(hiS * gridHz).toInt()) {
        val n = x.size - lag
        if (n < 10) break
        var sa = 0.0; var sb = 0.0; var saa = 0.0; var sbb = 0.0; var sab = 0.0
        for (i in 0 until n) { val a = x[i].toDouble(); val b = x[i + lag].toDouble()
            sa += a; sb += b; saa += a * a; sbb += b * b; sab += a * b }
        val cov = sab / n - (sa / n) * (sb / n)
        val va = saa / n - (sa / n) * (sa / n); val vb = sbb / n - (sb / n) * (sb / n)
        if (va > 1e-12 && vb > 1e-12) best = maxOf(best, cov / sqrt(va * vb))
    }
    return best.toFloat()
}
```

---

## 2. DetectorConfig

### 2.1 Kotlin defaults

The prototype implements exactly these values **[P]**.

```kotlin
data class ModeThresholds(
    val triggerG: Float,     // cheap per-sample trigger (before rate/range scaling)
    val fallMinG: Float,     // FALL requires smvPeak >= this
    val fallMediumG: Float,  // "violence" vote + score tier 2
    val fallStrongG: Float,  // score tier 1
    val jerkGps: Float,      // "violence" vote
    val nominalRateHz: Int,  // what :app requests in this mode (SPEC battery section)
)

val DEFAULT_MODES = mapOf(
    Mode.STILL    to ModeThresholds(1.8f, 1.8f, 2.4f, 3.0f,  50f,  25),
    Mode.WALKING  to ModeThresholds(1.8f, 1.8f, 2.4f, 3.0f,  50f,  50),
    Mode.UNKNOWN  to ModeThresholds(1.8f, 1.8f, 2.4f, 3.0f,  50f,  50),
    Mode.RUNNING  to ModeThresholds(3.5f, 3.5f, 4.5f, 5.5f, 150f,  50),
    Mode.CYCLING  to ModeThresholds(3.0f, 3.0f, 4.0f, 5.0f, 100f,  50),
    Mode.VEHICLE  to ModeThresholds(2.5f, 3.0f, 3.5f, 4.5f, 100f, 100),
)

data class DetectorConfig(
    val modes: Map<Mode, ModeThresholds> = DEFAULT_MODES,
    // windows
    val preWindowMs: Long = 2_000, val postWindowMs: Long = 10_000, val vehiclePostWindowMs: Long = 15_000,
    val candidateSearchMs: Long = 500, val candidateRearmMs: Long = 300, val cooldownMs: Long = 60_000,
    val vehicleHoldMs: Long = 60_000, val vehicleDebounceMs: Long = 30,
    // sampling / saturation (section 4)
    val gridHz: Int = 100, val saturationFraction: Float = 0.97f, val maxGapMs: Long = 100,
    val bodyImpactMinMs: Float = 80f, val minCaptureFactor: Float = 0.7f, val rangeHeadroom: Float = 0.9f,
    // free fall / airborne
    val partialFreeFallG: Float = 0.6f, val partialFreeFallMinMs: Long = 60,
    val trueFreeFallG: Float = 0.35f, val trueFreeFallMinMs: Long = 200, val trueFreeFallMaxLeadMs: Long = 150,
    val airborneMinMs: Long = 150, val bounceAirborneMinMs: Long = 40,
    // rotation / orientation
    val fastRotationDeg: Float = 75f, val fallRotationDeg: Float = 60f,
    val orientMinDeg: Float = 35f, val orientStrongDeg: Float = 60f, val noGyroOrientVoteDeg: Float = 60f,
    val impulseMaxOrientDeg: Float = 15f, val impulseMaxRotDeg: Float = 20f, val impulseMinPreG: Float = 0.6f,
    val handHeldTiltDeg: Float = 30f,
    // stillness / recovery
    val stillStdG: Float = 0.05f, val stillFractionMin: Float = 0.7f, val restNoiseG: Float = 0.012f,
    val gaitAutocorrMin: Float = 0.5f, val gaitMinStdG: Float = 0.08f, val pickupOrientDeg: Float = 30f, val pickupStdG: Float = 0.08f,
    // impacts
    val impactCountG: Float = 1.8f, val impactMinSeparationMs: Long = 100, val impactValleyDropG: Float = 0.5f,
    val sharpImpactMaxMs: Long = 30, val sharpMinRateHz: Float = 40f, val aboveG: Float = 2.0f, val dvRegionG: Float = 1.5f,
    // VEHICLE_CRASH
    val crashSpeedGateMps: Float = 4.2f,      // 15 km/h
    val crashSpeedHighMps: Float = 7.0f,      // 25 km/h
    val speedImpliesVehicleMps: Float = 8.0f, // 29 km/h
    val crashImpactG: Float = 4.0f, val crashStrongG: Float = 8.0f, val crashMinSatMs: Long = 10, val crashStrongSatMs: Long = 20,
    val crashEnergyMs: Long = 40, val crashMinDvMps: Float = 2.0f, val crashStrongDvMps: Float = 4.0f,
    val stoppedMps: Float = 1.5f, val continuedFraction: Float = 0.5f, val crashRotationDeg: Float = 45f,
    val cabinPressureSpikeHpa: Float = 0.5f,  // [U] bonus only; no published value
    // PEDESTRIAN_IMPACT
    val strikeImpactG: Float = 5.0f, val strikeMinSatMs: Long = 10, val strikeMaxPreRotDeg: Float = 30f,
    val strikeRotDeg: Float = 90f, val strikeHorizRatio: Float = 0.6f, val strikeDvMps: Float = 3.0f, val strikeOrientDeg: Float = 45f,
    val tossMaxPeakG: Float = 5.0f,
    // FALL
    val fallInMovingVehicleMinG: Float = 3.0f, val fallPressureRiseHpa: Float = 0.04f, val minViolenceVotes: Int = 2,
    // emission thresholds
    val emitCrash: Float = 0.60f, val emitStrike: Float = 0.70f, val emitFall: Float = 0.65f, val crashNoSpeedAfterCap: Float = 0.70f,
)
```

**Rate- and range-aware thresholds** (§4). Every g threshold `x` is used as `eff(x) = min(x · cf, 0.9 · rangeG)`, where `cf = max(0.7, cos(π · period / (2 · 80 ms)))`.
- `cf` is 0.71 at 25 Hz, 0.92 at 50 Hz, 0.98 at 100 Hz and about 1 at ≥ 200 Hz.
- A sample at or above 0.97·range counts as exceeding any threshold.

### 2.2 Per-mode table

| Mode | Rate (SPEC) | Gyro | Trigger | FALL min / medium / strong | Jerk vote | Context and justification |
|---|---|---|---|---|---|---|
| STILL | 25 Hz accel, batched ≤ 10 s | **no** (SPEC) | 1.8 g | 1.8 / 2.4 / 3.0 g | 50 g/s | A person standing at a crossing can be struck or can collapse. At 25 Hz, `cf` = 0.71 lowers the effective trigger to 1.27 g. Rotation features are absent; orientation change substitutes **[P: no-gyro variants pass]** |
| WALKING | 50 Hz accel + gyro | yes | 1.8 g | 1.8 / 2.4 / 3.0 g | 50 g/s | The Northwestern 2 g trigger missed 6/10 real falls, and the report recommends 1.6–1.8 g **[N]**. Bourke real-world UFT was 1.79 g **[V]**; simulated-fall UFT was 3.52 g **[V]**, which is the strong tier. Thigh-pocket walking peaks are about 1.3–1.9 g **[E]**, so the trigger fires occasionally and the analysis rejects those candidates **[P]** |
| UNKNOWN | 50 Hz | yes | 1.8 g | as WALKING | 50 g/s | Most permissive. Crash only if speed ≥ 8 m/s |
| RUNNING | 50 Hz *(not specified by SPEC; my choice)* | yes | 3.5 g | 3.5 / 4.5 / 5.5 g | 150 g/s | Pocket running peaks are about 3–4.5 g, with flight-phase troughs of 0.3–0.5 g every stride **[E]**. SisFall reports that jogging and jumping produced most threshold crossings **[S]**. Stride periodicity suppresses these (§3) |
| CYCLING | 50 Hz *(my choice)* | yes | 3.0 g | 3.0 / 4.0 / 5.0 g | 100 g/s | Normal riding reaches about 5 g, mostly from handling, so rotation must be fused in **[S: Candefjord 2014]**. The report recommends about 3 g plus rotation **[N]** |
| VEHICLE | 100 Hz accel + gyro | yes | 2.5 g, **2 samples within 30 ms** or 1 saturated sample | 3.0 / 3.5 / 4.5 g; orientChange ≥ 35° required while `movingInVehicle` | 100 g/s | Hard braking is 0.87–1.08 g on ABS cars **[S: SAE 2023-01-0616]**, giving SMV ≈ 1.3–1.5 g. Mounted-phone potholes are about 0.3–1.2 g deviation **[S: arXiv 1908.10187]**. A crash at the phone is ≥ 4 g **[N: WreckWatch]**. The debounce drops single-sample spikes from loose phones and slams **[P]** |

**Mode handling**
- For `vehicleCtx`, the effective mode is VEHICLE if VEHICLE was reported within `vehicleHoldMs` (60 s), or if speed ≥ 8 m/s was seen in the last 60 s. A crash often *causes* a transition to STILL **[N]**.
- FALL and strike thresholds use the **reported** mode row, so a person who walked away from the car gets WALKING thresholds.
- `onMode` changes take effect for new candidates only.
- After the trigger, `:app` raises the rate to 200 Hz with gyro (SPEC "possible accident"). The detector must accept a mid-window rate change; the grid resampling handles it.

### 2.3 Per-EventKind rules

Evaluated in this order. The first kind that emits wins, and the cooldown starts.

**Global suppressions** (any Mode, checked first; definitions in §3):
- IMPULSE_ONLY returns none.
- Compute `drop` and `toss`; these only block PEDESTRIAN_IMPACT and FALL.

**VEHICLE_CRASH**
- **Context** (all required):
  - vehicle context (§2.2), and
  - `speedBefore ≥ 4.2 m/s`, or speedBefore is absent.
- **Impact gate:** `smvPeak ≥ eff(4.0 g)` or `satMs ≥ 10`.
- **Energy gate:** `aboveTwoGMs ≥ 40` or `dvLowerBound ≥ 2.0 m/s`.
- **Hard suppression:** if `speedAfter ≥ max(4.2, 0.5·speedBefore)`, the vehicle kept driving.
- **Score:**
  - Base 0.40.
  - +0.30 if speedAfter ≤ 1.5 m/s.
  - −0.15 if speedAfter is absent.
  - +0.10 if speedBefore ≥ 7 m/s.
  - +0.10 if satMs ≥ 20 or peak ≥ eff(8 g).
  - +0.10 if dv ≥ 4 m/s.
  - +0.05 if gyroNet ≥ 45°.
  - +0.05 if impacts1s ≥ 2.
  - +0.10 if |Δp| ≥ 0.5 hPa *(bonus only, unverified)*.
  - Cap at 0.70 when speedAfter is absent.
- **Emit** if score ≥ 0.60. Countdown 20 s (SPEC).

**PEDESTRIAN_IMPACT**
- **Context:** not `movingInVehicle`, not `drop`, not `toss`.
- **Gate:** `smvPeak ≥ eff(5 g)` or `satMs ≥ 10`, and `preRotDeg ≤ 30°`. With no gyro, treat the second condition as passed.
- **Score:**
  - Base 0.35.
  - +0.15 if horizRatio ≥ 0.6.
  - +0.15 if strikeRot ≥ 90°.
  - +0.10 if impacts15s ≥ 2.
  - +0.10 if airborne ≥ 150 ms.
  - +0.10 if satMs ≥ 10 or peak ≥ eff(8 g).
  - +0.15 if orientChange ≥ 45°.
  - +0.15 if stillFraction ≥ 0.7.
  - +0.10 if dv ≥ 3 m/s.
  - **−0.25 if recovered.**
- **Emit** if score ≥ 0.70, with confidence = min(1, score). Otherwise fall through to FALL. Countdown 15 s.
- Ship it **shadow-logged**: always store `strikeScore`, because these thresholds are synthetic-only **[N: report recommendation]**.

**FALL**
- **Order of checks:**
  1. Return none if `drop`, `toss`, or (`gaitPre ≥ 0.5` and `gaitPost ≥ 0.5`).
  2. **Gate:** `smvPeak ≥ eff(fallMinG[mode])` (eff(3 g) when `movingInVehicle`) or `satMs ≥ 5`.
  3. **Violence votes:** count how many of these hold; at least **2** are required.
     - peak ≥ eff(medium) or saturated
     - freeFallMs ≥ 60 (S ≤ 0.6 g)
     - fastRot ≥ 75° in 0.5 s (no gyro: orientChange ≥ 60°)
     - jerk ≥ jerkGps[mode]
  4. **Posture gate:** orientChange ≥ 35° **or** stillFraction ≥ 0.7. When `movingInVehicle`, orientChange ≥ 35° is required.
  5. Return none if `recovered`.
- **Score:**
  - Base 0.20.
  - Impact tier: +0.25 if ≥ eff(strong) or saturated; else +0.15 if ≥ eff(medium); else +0.05.
  - +0.15 if freeFall ≥ 60 ms.
  - +0.15 if gyroNet ≥ 60°.
  - Orientation: +0.20 if ≥ 60°; else +0.10 if ≥ 35°.
  - +0.25 if stillFraction ≥ 0.7.
  - +0.10 if Δp ≥ +0.04 hPa (bonus only).
- **Emit** if score ≥ 0.65. Countdown 15 s.

**Confidence and reason.** Confidence is the clamped score. `reason` is the kind tag plus its top contributors, for example `"crash v=13.8→0.0m/s sat=40ms dv≥13.1"`. Log suppression reasons for debug only:

`impulse_only | phone_drop | toss | continued_driving | gait_continues | recovered | violence<2 | no_posture_change | below_fall_min | score_low`

### 2.4 Value justification

| Value | Source / reasoning |
|---|---|
| Free-fall 0.6 g, impact within 1 s, posture 2 s after impact | Kangas 2008 via Bagalà 2012 **[V]** |
| LFT 0.65 g, UFT 2.8 g, falling-edge 600 ms, rising-edge 350 ms, v ≤ −0.7 m/s, posture > 60° for 75% of t+1…t+3 s | Bourke 2010 (Bourke3) via Bagalà 2012 **[V]**. 0.6 FP/day on 52.4 h of elderly ADL **[S]** |
| Real-world falls: SE 57 ± 27%; best SE 76–83% at SP 94–97%; PPV ≤ 38% | Bagalà 2012 **[V]**. Expect similar losses, which is why the countdown exists |
| Sitting down shows SV < 0.73 g ("weightlessness") | Bagalà 2012 **[V]**. Hence free fall is one vote, never a gate |
| Orientation change 35° | Figueiredo 2016 MobiFall thresholds: CA 35°, angle between mean accel of the first and last 1 s of a 4 s window **[V]** |
| SisFall: 200 Hz, ADXL345 ±16 g, MMA8451Q ±8 g, gyro ±2000 dps; std-magnitude feature C9 ≈ 0.36 (1 s windows, waist) | Sucerquia 2017 **[V]** |
| Crash ≥ 4 g, speed gate 15 mph | WreckWatch 2011 **[N]**. e-NOTIFY used 5 g **[N]** |
| Crash pulse: frontal rigid-barrier compartment pulses **91–113 ms**, peaks **31–46 g** at 48–56 km/h | Locey 2012, n = 1094 vehicles **[V]**. Vehicle-to-vehicle contact lasts 200–400 ms **[N: AGH]**. Thresholds therefore use 40 ms energy, not 100 ms |
| Ordinary handling exceeds 20 km/h crash readings; threshold-only detection gives about 10 FP/h at 80% SE | AGH ICCS 2021 **[N]**. Hence speed-after and the energy gates |
| Speed after ≤ 1.5 m/s | Vehicle stopped; GPS noise at standstill is a few tenths of a m/s **[E]** |
| Hard braking 0.87–1.08 g (ABS); telematics "harsh" 0.2–0.6 g | SAE 2023-01-0616 abstract **[S]**; vendor guides **[S]** |
| Pothole statistical thresholds about 0.3 g (mean), 0.15 g (std) at 50 Hz, event 1–5 s; Pothole Patrol 380 Hz, with a speed filter that rejects door slams | arXiv 1908.10187 **[S]**; Eriksson 2008 **[V]** |
| Strike timing: head contact 135–141 ms after leg contact at 40 km/h | Forman IRCOBI 2015 **[N]**. **No peak g at pelvis/hip found [U]**, so 5 g comes from Δv/time: reaching 20–40 km/h in about 100 ms gives an average of 5.7–11 g **[D]** |

---

## 3. Suppression rules

| Rule | Exact condition | Effect | Covers |
|---|---|---|---|
| IMPULSE_ONLY | `aboveTwoGMs ≤ max(30 ms, 1 period)` **and** orientChange < 15° **and** gyroNet < 20° **and** minSmvPre > 0.6 g | No event (all kinds) | Car door slam with the phone in the door pocket; table bang; knock **[P: door_slam]** |
| VEHICLE debounce | In VEHICLE, the trigger needs ≥ 2 samples ≥ eff(2.5 g) within 30 ms, or 1 saturated sample | No candidate | Mounted-phone vibration spikes, loose-phone single-sample spikes **[P: mount_vib, pothole_loose]** |
| PHONE_DROP | trueFreeFall ≥ 200 ms (S ≤ 0.35 g, ending ≤ 150 ms before t0) **and** (sharp impact [aboveTwoG ≤ 30 ms, only trusted at ≥ 40 Hz] **or** bounceAirborne ≥ 40 ms **or** handHeldBefore) **and** (restNoise ≤ 0.012 g **or** recovered) | Blocks FALL and PEDESTRIAN_IMPACT | Drops from hand onto floor or carpet at 25–400 Hz. A ladder fall with the phone in the pocket is **not** suppressed **[P]** |
| TOSS (thrown on bed or seat) | airborneAfter ≥ 150 ms **and** smvPeak < eff(5 g) **and** (restNoise ≤ 0.012 g **or** recovered) | Blocks FALL and PEDESTRIAN_IMPACT | The throw peak comes first, then the flight, then a soft landing. The landing candidate is caught by PHONE_DROP **[P: thrown_on_bed]** |
| QUICK PICKUP / RECOVERY | `recovered` (gait autocorrelation ≥ 0.5, or orientation ≥ 30° from rest with std ≥ 0.08 g, within W) | FALL: no event. STRIKE: −0.25. CRASH: unaffected | Drop then pickup; fall then getting up (Sony-style) **[P: phone_drop_pickup, fall_getup]** |
| CONTINUED DRIVING | VEHICLE_CRASH with speedAfter ≥ max(4.2, 0.5·speedBefore) | No crash event | Pothole, speed bump, railway crossing, a phone falling while driving **[N: Damoov, Pothole Patrol]** |
| CRASH ENERGY | VEHICLE_CRASH needs aboveTwoG ≥ 40 ms or dv ≥ 2 m/s | No crash event | Hard brake plus the phone sliding off the seat onto the floor: 7 g, 20 ms, dv 0.5 m/s **[P: hard_brake_slide]** |
| VEHICLE CONTEXT | VEHICLE_CRASH only with VEHICLE in the last 60 s, or speed ≥ 8 m/s; speedBefore ≥ 4.2 m/s if known | No crash event | Phone drops while walking, a parked car, a door slam at 0 km/h |
| SIT-DOWN / LIE-DOWN | FALL needs ≥ 2 violence votes | No FALL | A normal sit is about 2 g peak, 0.72 g trough, rotation over about 1 s, jerk about 23 g/s: 0 votes **[P: 24 sit variants, all none]**. Lying on a bed slowly never triggers |
| RUNNING STRIDE / STAIRS | gaitPre ≥ 0.5 **and** gaitPost ≥ 0.5 | Blocks FALL. STRIKE: in practice, no running candidate scores ≥ 0.7 | Every stride over 3.5 g, stair descent at 2–2.5 g **[P: run, stairs_down]** |
| COOLDOWN | 60 s after an emitted event; candidates re-arm 300 ms after a rejection | | Duplicates; a fall's bounce |

---

## 4. Saturation (±2/4/8/16 g) and sampling (25–400 Hz, jitter)

### 4.1 Range and saturation

- **Range at runtime.**
  - Call `setAccelMaxRange(sensor.maximumRange)` (m/s²). Typical values: 19.6 (2 g), 39.2 (4 g), 78.5 (8 g), 156.9 (16 g).
  - If |axis| is ever observed above the reported range × 1.02, raise the range to the next standard bin.
  - If ≥ 3 consecutive samples sit within 0.5% of the same |axis| value at ≥ 1.9 g, infer clipping at that value **[E]**.
- **Saturated sample:** any |axis| ≥ 0.97·range. The 3% margin covers the calibration offset and scale applied after the ADC clip **[E]**.
- **When saturated:**
  - `smvPeak` is a lower bound, and the sample counts as exceeding every g threshold.
  - `satMs` becomes the magnitude proxy.
  - `dvLowerBound` is clipped (table below).
  - Thresholds are capped at `0.9·range` so a ±2 g phone can still reach every gate.
- **What the half-sine maths gives** **[D]**, with A = πΔv/(2T). The prototype reproduced these values **[P]**.

| Crash (half-sine) | Peak | ±2 g: sat ms / Δv seen | ±4 g | ±8 g | ±16 g |
|---|---|---|---|---|---|
| 50 km/h, T = 120 ms | 18.5 g | 112 / 2.3 m/s | 103 / 4.4 | 86 / 8.1 | 40 / 13.2 |
| 30 km/h, T = 120 ms | 11.1 g | 106 / 2.2 | 92 / 4.2 | 59 / 7.1 | 0 / 8.3 |
| 20 km/h, T = 150 ms (vehicle-to-vehicle) | 5.9 g | 117 / 2.6 | 79 / 4.6 | 0 / 5.6 | 0 / 5.6 |
| 56 km/h, T = 100 ms (NCAP-like) | 24.9 g | 95 / 1.9 | 90 / 3.7 | 79 / 7.0 | 56 / 12.4 |

- **Implications:**
  - On ±2 g and ±4 g phones the crash is recognised by `satMs ≥ 10–20` and `aboveTwoG ≥ 40`. Δv can never exceed about 2·range·0.1 s.
  - Low-speed detection (below about 16 km/h at T = 150 ms) is lost by design. The prototype sweep detects 16, 20, 25 and 30 km/h, and not 10 or 14 km/h. Apple also deliberately ignores minor crashes **[N]**.
- **Gyro.** Handheld devices must measure ≥ 1000 dps **[V: CDD 16 7.3.4/H-3-2]**. Clip handling is the same, at 0.97·`gyro.maximumRange`; a tumbling phone can exceed 17 rad/s. Net rotation is then a lower bound, which is acceptable.

### 4.2 Sampling rate, jitter and gaps

- **Timestamps.**
  - Use `SensorEvent.timestamp` only. CDD wants continuous-sensor jitter below 3% **[V]**, but this device showed a dropped sample (49 ms vs 29.6 ms) **[V]**.
  - Keep per-sensor ring buffers sorted by timestamp. Drop samples with `t ≤ last` (duplicates or reorders).
  - Accel and gyro arrive in separate batches; align them by timestamp, never by arrival.
- **Rate estimate.** Median Δt over the last 64 samples. The requested rate is only a hint **[V: Android docs]**.
- **Gaps.**
  - Δt > max(3·period, 100 ms) is a gap; increment `gapCount`.
  - A free-fall or airborne run never spans a gap.
  - Stillness windows that overlap a gap are skipped.
  - If a gap overlaps `[t0−0.2, t0+0.3]`, the candidate is still evaluated: the peak is a lower bound.
- **Peak capture.** A point sample of a half-sine of duration T at period Δt can under-read the peak by a factor of `cos(πΔt/2T)` **[D]**:

| Rate | T = 15 ms (drop) | 30 ms | 50 ms | 80 ms (body) | 120 ms (crash) |
|---|---|---|---|---|---|
| 25 Hz | 0 (missed) | 0 | 0.31 | 0.71 | 0.87 |
| 50 Hz | 0 | 0.50 | 0.81 | 0.92 | 0.97 |
| 100 Hz | 0.50 | 0.87 | 0.95 | 0.98 | 0.99 |
| 200 Hz | 0.87 | 0.97 | 0.99 | 1.00 | 1.00 |
| 400 Hz | 0.97 | 0.99 | 1.00 | 1.00 | 1.00 |

- **How the detector uses this.**
  - Real sensors low-pass and average internally, so a short spike shows up attenuated in 1–2 samples rather than vanishing. The prototype models this as a boxcar over one period.
  - The detector scales g thresholds by `cf` from §2.1, with T = 80 ms and a floor of 0.7.
  - Duration features (`aboveTwoGMs`, sharpness) are trusted only at ≥ 40 Hz.
  - At 400 Hz, compute peaks on raw samples. Grid features use the 100 Hz grid, interpolated, or averaged when the source rate is higher.
- **Memory.** A 20 s ring at 400 Hz for accel + gyro is about 16k samples × 2 × 16 B ≈ 0.5 MB.

### 4.3 Implications for `:app`

These are not `:detection` code, but they affect what data arrives.

- **Registration:**

```kotlin
val acc = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)!!            // required
val gyro = sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE)                  // optional
val baro = sm.getDefaultSensor(Sensor.TYPE_PRESSURE)                   // null on CPH2717
detector.setAccelMaxRange(acc.maximumRange)
sm.registerListener(listener, acc, /*samplingPeriodUs*/ 1_000_000 / rateHz, /*maxReportLatencyUs*/ latencyUs)
```

- **Batching latency.** `maxReportLatencyUs` must stay below `fifoMaxEventCount / (sum of event rates sharing the FIFO)`. On this device that is 4500 / (50 + 50) ≈ **45 s** at 50 Hz accel+gyro, and 180 s for STILL at 25 Hz accel only. SPEC uses 10 s; android-background.md cites Google's advice of ≥ 30 s. Both fit under 45 s.
- **Suspend.**
  - accel/gyro are **non-wake-up**. While the SoC is suspended the FIFO keeps the newest events and drops the oldest. AOSP says it is "the responsibility of applications to keep a partial wake lock" **[V: AOSP suspend-mode]**.
  - The drain strategy (short wake lock plus `flush()`, paced by location callbacks, with no always-held wake lock) is defined in `docs/research/android-background.md` §4.
  - `:detection` must tolerate the remaining gaps (§4.2). Measure `gapCount` overnight with the screen off.
- **Manifest:**

```xml
<uses-feature android:name="android.hardware.sensor.accelerometer" android:required="true"/>
<uses-feature android:name="android.hardware.sensor.gyroscope" android:required="false"/>
<uses-feature android:name="android.hardware.sensor.barometer" android:required="false"/>
<!-- HIGH_SAMPLING_RATE_SENSORS: not needed by detection (it stays <= 200 Hz). It may be declared
     for the health-FGS prerequisite; see android-background.md. -->
<!-- <uses-permission android:name="android.permission.HIGH_SAMPLING_RATE_SENSORS"/> -->
```

---

## 5. Synthetic trace recipes (become `SyntheticTraces` and unit tests)

### 5.1 Generator model

The prototype reproduces this model **[P]**.

1. **Truth signal** on a 1 kHz grid, with world frame z up.
   - Specific force `f_w = a_dyn,w + (0,0,1) g`, so free fall is `a_dyn = (0,0,−1)`.
   - Phone attitude `R(t) = R0 · Rot(u, θ(t))`. θ follows a raised-cosine profile `θ = Θ·(s − sin 2πs / 2π)`, so `ω = u·θ'`.
   - Phone reading `f_p = Rᵀ f_w`, plus phone-frame extras (noise, vibration, centripetal ω²r).
2. **Sensor model:**
   1. Clip each axis at ±range.
   2. Boxcar-average over one output period (the chip's filter).
   3. Add white noise σ = 0.005 g per axis (CDD max) and gyro σ = 0.01 rad/s.
   4. Timestamps `k/fs + N(0, 1–2% of period)`, sorted, with 0.2% random drops.
   5. Optionally inject one gap.
   6. Gyro is clipped at ±34.9 rad/s.
3. **Speeds:** 1 Hz `(t, v)`, optional σ 0.3 m/s. **Pressure:** optional, 10 Hz, σ 0.003 hPa, step of `+0.12 hPa/m` of drop.
4. **Determinism:** seeded `java.util.Random(seed)` / `SplittableRandom`; outputs are `List<SensorSample>` plus speed and mode events.
5. **Orientation presets** (gravity direction in the phone frame):
   - POCKET (0, 0.98, 0.17): upright, about 10° tilt.
   - HAND (0, 0.77, 0.64): about 40° toward the face.
   - MOUNT (0, 0.94, 0.34).
   - FLAT (0, 0, 1).
   - Face down (0, 0, −1).
6. **Building blocks** (all in g):
   - `halfSine(t0, durMs, ampG, axisWorld|axisPhone)`
   - `freeFall(a, b)`
   - `rotate(t0, durMs, deg, axisPhone)`, or `orientTo(target)` (the smallest rotation about the axis)
   - `gait(a, b, cadenceHz, peakG, troughG, swingRad, strikeMs)`: pocket-leg strikes alternate with 0.6× strikes from the other leg
   - `noise(a, b, σ)`
   - `sineP(a, b, f, amp)`
   - `stillBody(a, b)`: σ 0.003 g plus 0.25 Hz breathing at 0.005 g
   - `speed(t, v)`

### 5.2 Scenarios and expected outcomes

- t is relative to trace start; "dyn" means the dynamic amplitude added to gravity.
- Run each scenario at its listed rate. Every one must also pass at seeds 0–5, with 2% jitter, and at one alternate rate (100 Hz, or 50 Hz when the base is ≥ 100 Hz).
- Confidence bounds are conservative versions of the prototype values.

**The ten SPEC traces**

| # | Test id | Mode / rate / range | Recipe | Expected |
|---|---|---|---|---|
| 1 | `walk` | WALKING / 50 Hz / 8 g | POCKET, 16 s. gait 1.8 steps/s, peak 1.7 g, trough 0.6 g, swing 0.35 rad, strike 30 ms. Noise 0.02 g. Speed 1.4 m/s | **none** |
| 2 | `run` | RUNNING / 50 / 8 | POCKET, 16 s. gait 2.8 steps/s, peak 4.2 g, trough 0.35 g, swing 0.6 rad, strike 40 ms. Noise 0.04. Speed 3.0 | **none**: candidates fire every stride and are rejected by `gait_continues` |
| 3 | `phone_drop` | WALKING / 50 / 8 | HAND, held 5 s (noise 0.02). Free fall 450 ms (1.0 m) with orientTo face-down (≈130° tumble) plus 6 rad/s centripetal. Impact halfSine 15 ms at 12 g. Bounce: airborne 85 ms, 4 g/12 ms; airborne 40 ms, 1.5 g/10 ms. Rest | **none** (`phone_drop`). Features: trueFreeFall ≈ 440 ms, aboveTwoG ≤ 20 ms |
| 4 | `sit_down` | WALKING / 50 / 8 | POCKET, standing 5 s. Descent 0.8 s dyn −0.28 g (trough 0.72). orientTo thigh-horizontal over 1.0 s (≈77°). Contact halfSine 120 ms, 1.05 g (peak ≈ 2.0 g). Seated: noise 0.015 g plus 3 fidgets (2 Hz, 0.08 g, 0.5 s) | **none** (`violence<2`). Jerk ≈ 23 g/s, fastRot ≈ 62° |
| 5 | `stairs_down` | WALKING / 50 / 8 | gait 1.6 steps/s, peak 2.4 g, trough 0.55, swing 0.5, strike 40 ms. Noise 0.03 | **none** (`gait_continues`) |
| 6 | `pothole` | VEHICLE / 100 / 8 | MOUNT, 24 s. Road noise 0.04 g plus 25 Hz sine at 0.03 g. Speed 11.1 m/s steady. At 8 s: halfSine −0.6 g/40 ms, then +1.2 g/60 ms, then a 12 Hz decaying 0.3 g ring (τ 0.1 s) | **none** (no trigger) |
| 7 | `hard_brake` | VEHICLE / 100 / 8 | MOUNT, 30 s. 16.7 m/s, then −0.9 g longitudinal (0.3 s ramp) until stopped (≈1.9 s). Speeds follow | **none** (SMV ≈ 1.35 g never triggers) |
| 8 | `fall` | WALKING / 50 / 8 | Walk 5 s. dyn −0.6 g over 350 ms (trough ≈ 0.4 g). orientTo thigh-horizontal over 450 ms. Impact halfSine 50 ms, 3.5 g (axis 0.3,0,1; peak ≈ 4–4.4 g). Second impact 1.0 g/40 ms at +200 ms. stillBody from +350 ms. Speed 1.4 then 0 | **FALL, conf ≥ 0.8**. freeFall ≈ 180 ms, fastRot ≈ 88°, orient ≈ 82°, still = 1 |
| 9 | `pedestrian_impact` | WALKING / 50 / 8 | Walk 6 s. At t0, a lateral halfSine of 60 ms, 14 g, axis (1,0,0.2), so it saturates. Rotation of 270° about (0.3,0,1) over 0.8 s, starting 20 ms after t0. Hood hit 6.5 g/30 ms at +140 ms. Airborne/slide dyn −0.6 g from +0.2 to +1.0 s. Ground impact 4.5 g/40 ms at +1.1 s. Slide −0.7 g plus noise 0.15 for 0.75 s. stillBody. Speed 1.4 then 0 | **PEDESTRIAN_IMPACT, conf ≥ 0.9**. satMs ≈ 20, horizRatio ≈ 0.96, strikeRot ≈ 176°, impacts15s = 3 |
| 10 | `vehicle_crash` | VEHICLE / 100 / 16 | MOUNT, 30 s, road noise. Speed 13.9 m/s (σ 0.3) to t0, then 2.0 at +1 s, then 0. Longitudinal half-sine Δv 13.9 m/s, T 120 ms (18.5 g → clips at 16 g), plus vertical 2 g/100 ms. Mount flex 25° over 150 ms. Second hit 3 g/50 ms at +0.4 s. Post: noise 0.01 plus idle 25 Hz 0.02 g | **VEHICLE_CRASH, conf ≥ 0.9**. satMs ≈ 40, aboveTwoG ≈ 120 ms, dv ≈ 13 m/s |

**Extra traces: suppression, degradation and kinds**

| # | Test id | Mode / rate / range | Recipe | Expected |
|---|---|---|---|---|
| 11 | `phone_drop_100hz` | WALKING / 100 / 8 | Same as #3 | none |
| 12 | `phone_drop_quick_pickup` | STILL / 25 / 8 | #3, then at +2.5 s: pickup with dyn +0.6 g/0.8 s and 140° rotation, then in-hand noise 0.03 g | none (`recovered`) |
| 13 | `thrown_on_bed` | STILL / 25 / 8 | HAND. Throw halfSine 250 ms 2 g along (0.7,0,0.7). Airborne 350 ms, orientTo about 8° from flat face-up, 5 rad/s centripetal. Mattress: 8 Hz decaying ring of 1.2 g, τ 80 ms | none (`toss`, then `phone_drop` for the landing candidate) |
| 14 | `carpet_drop_left` | STILL / 25 (and 50–400) / 8 | #3, but the impact is 35 ms at 4 g with no bounce, and the phone is left on the floor | none at all rates (`phone_drop` via handHeldBefore) |
| 15 | `ladder_fall` | WALKING / 25–400 / 8 | POCKET still. **True free fall 500 ms**, orientTo horizontal. Body landing 6 g/70 ms, then 1.5 g/50 ms. stillBody | **FALL** at every rate. It must **not** be suppressed as a drop |
| 16 | `fall_then_get_up` | WALKING / 50 / 8 | #8. At +3.7 s, rotate back over 1.5 s, then walk from +5.2 s | none (`recovered`) |
| 17 | `fall_from_standing_still` | STILL / 25 / 8 / **no gyro** | POCKET. dyn −0.5 g/500 ms, orientTo horizontal over 600 ms, impact 2.2 g/60 ms (peak ≈ 2.8 g), stillBody | **FALL, conf ≥ 0.8**, with and without gyro |
| 18 | `running_fall_4g` | RUNNING / 50 / **4 g** | Run 5 s. Rotate about 100° over 0.4 s, dyn −0.5 g/250 ms, impact 5.5 g/50 ms on axis (0.5,0,1) (clips), slide −0.5 g plus noise 0.2 for 0.55 s, stillBody | **FALL, conf ≥ 0.85** |
| 19 | `bike_crash` | CYCLING / 50 / 8 | POCKET. Pedalling: 1.3 Hz swing 0.6 rad, road noise 0.08 g. Speed 5.5 m/s, then 0 after +2 s. Rotate about 70° about phone y over 0.5 s (net orientation change only about 11°). Impact 4.5 g/60 ms at +0.3 s, slide, stillBody | **FALL, conf ≥ 0.8**. The posture gate passes through stillness, not orientation |
| 20 | `pedestrian_impact_16g_100hz` | WALKING / 100 / 16 | Same as #9 | PEDESTRIAN_IMPACT ≥ 0.9 |
| 21 | `pedestrian_low_speed_standing` | STILL / 25 / 8 (±gyro) | Standing still. Lateral 5.5 g/80 ms. Rotate 100° over 0.5 s starting 30 ms after impact. Ground 2.2 g/50 ms at +0.6 s. stillBody | event ∈ {PEDESTRIAN_IMPACT, FALL}. The prototype gives PEDESTRIAN_IMPACT, 0.9–1.0 |
| 22 | `crash_4g_pocket` | VEHICLE / 100 / **4 g** | #10 with POCKET orientation | VEHICLE_CRASH ≥ 0.9. satMs ≈ 100, dv ≈ 4.5 |
| 23 | `crash_low_speed_20kmh` | VEHICLE / 100 / 8 | #10 with Δv 5.6 m/s and T 150 ms (peak 5.9 g) | VEHICLE_CRASH, **0.8–0.9** |
| 24 | `crash_no_gps_after` | VEHICLE / 100 / 8 | #10 with no speed samples after t0 (tunnel) | VEHICLE_CRASH, **conf ≤ 0.70** (cap) |
| 25 | `crash_50hz` / `crash_400hz` | VEHICLE / 50 / 8 and 400 / 16 | Same as #10 | VEHICLE_CRASH ≥ 0.9 |
| 26 | `pothole_loose_phone` | VEHICLE / 100 / 8 | #6 with the phone near-flat in a cupholder, plus a 5 g/12 ms spike | none. Also none with the debounce disabled (`continued_driving`, or the posture gate) |
| 27 | `hard_brake_phone_slides` | VEHICLE / 100 / 8 | FLAT on the seat, #7 braking. At 8.4 s, a 0.4 g slide. Free fall 300 ms (0.45 m), rotate 20°. Floor impact 12 g/15 ms, then 2 g/12 ms. Speed reaches 0 | none (`CRASH ENERGY` fails: aboveTwoG 20 ms, dv 0.5; FALL fails on posture) |
| 28 | `car_door_slam` | VEHICLE (held) / 100 / 8 | Phone (0.3,0.9,0.3) in the door pocket, speed 0. Spike 5 g/10 ms along x plus a 40 Hz ring of 0.5 g, τ 30 ms | none (debounce; `impulse_only` if the debounce is off) |
| 29 | `mounted_vibration` | VEHICLE / 50 and 100 / 8 | MOUNT, 40 s. Noise 0.06 g plus a 28 Hz sine at 0.05 g (aliases at 50 Hz). Bumps of 0.5 g/30 ms every 7 s. 25 m/s | none |
| 30 | `fall_with_baro` | STILL / 25 / 8 | #17 plus pressure +0.078 hPa (0.65 m) at the impact | FALL. Confidence ≥ the no-pressure confidence; the bonus is at most +0.1 |
| 31 | `stairs_with_baro` | WALKING / 50 / 8 | #5 plus +0.3 hPa step (2.5 m) | none |
| 32 | `fall_with_gap` | WALKING / 50 / 8 | #8 with a 120 ms gap inside the free-fall phase | FALL (peak and posture are intact; the free-fall vote may drop) |

### 5.3 Prototype run

```
$ cd docs/research/detection-proto && python3 scen.py      # 32 cases, expected vs got
mismatches: 0
$ python3 mc.py   # every case at 6 seeds (2% jitter) + 2 alternate-rate runs -> 32/32 OK
$ python3 edge.py && python3 edge2.py && python3 sweep.py   # limits below
```

### 5.4 Known limits (document; do not assert as "correct")

- **Hard "plop" sit** gives **FALL, conf 1.0: an accepted false positive.** Recipe: trough 0.55 g, peak 2.8 g, 77° in 0.5 s, then seated very still.
  - For a thigh-pocket phone, a fast sit has the same posture end-state as lying, and the same violence features.
  - The literature lists "sitting down quickly" as a top false-alarm source. The countdown and the "I'm OK" label are the mitigation.
  - Collect these labels to train a later classifier.
- **Fall onto the knees** gives **none: a false negative** that matches the real-world misses in Bagalà and Northwestern. Recipe: 1.9 g, trough 0.75 g, 30° rotation.
- **Strike below 5 g** (for example a slow car or a padded hit) is classified as **FALL** when fall criteria hold. That is acceptable, since both use a 15 s countdown.
- **Crash below about 16 km/h** gives none (sweep: 14 km/h none, 16 km/h detected).
- **Phone dropped from the hand during a real fall and left on the floor** is suppressed as a drop. Product decision: shadow-log `phone_drop` while mode was WALKING/RUNNING and there was no pickup for 60 s, for later review **[E]**.

---

## 6. No barometer: graceful degradation

1. **Detection.** `PRESSURE` samples are optional.
   - If no pressure samples fall in both windows, `pressureDeltaHpa` is **omitted** from `features`; do not write 0 or NaN.
   - Every rule treats it as a bonus (+0.1 for a fall at Δp ≥ +0.04 hPa; +0.1 for a crash at |Δp| ≥ 0.5 hPa, unverified). **No gate uses it.**
   - Emission thresholds are tuned **without** pressure. Prototype cases #30 and #31 confirm the outcome is the same with and without it.
2. **What is lost, and what compensates:**
   - Height-drop confirmation for slow falls is lost. Stillness and orientation change compensate.
   - Apple-style airbag cabin-pressure confirmation is lost (open windows mask it anyway **[N]**). The speed-after gate and the energy gates are mandatory instead.
3. **App side.**
   - `uses-feature …barometer required="false"`, so Play does not filter out barometer-less phones.
   - `getDefaultSensor(TYPE_PRESSURE) == null` means do not register.
   - Never show "height drop" in the UI.
   - Record `hasBarometer` in the incident `details`.
4. **If a barometer exists** on other phones: use 1–5 Hz batched sampling, and compare medians over relative windows only (§1.2).
   - Veto a pressure rise > 0.25 hPa over 10 s while gait is periodic: that is stairs or an elevator, not a fall.
   - Never use absolute altitude: weather drift of about 1.5 hPa per day dominates **[N: P2Est]**.
5. **Tests.** Run every positive scenario with and without PRESSURE samples. The kind must be identical, and confidence may differ by at most 0.1.

---

## 7. Open issues / unverified

- **[U] Pedestrian-strike magnitudes at the hip and pocket.**
  - No published phone or PMHS pelvis peak g was found.
  - The 5 g gate and the rest of the strike signature are physics-derived. Treat PEDESTRIAN_IMPACT as shadow-validated until field data exists.
- **[U] Cabin-pressure spike** from airbag deployment in hPa is unpublished. That is why it is bonus-only.
- **[U] Accelerometer range on CPH2717** is not visible in dumpsys. Log `maximumRange`, `resolution` and `fifoMaxEventCount` from the app on first run.
- **[E] Thresholds to calibrate on device:**
  - `restNoiseG` (0.012 g), `stillStdG` (0.05 g) and the jerk votes are design estimates. The CDD bounds the noise floor at ≤ 0.005 g per axis.
  - Measure with the phone on a table and in a pocket while lying still. The adb sample (phone in use) showed |a| std of 0.017–0.043 g, which is not a resting value.
- **[S] Secondary-only sources:**
  - Bourke & Lyons gyro thresholds (abstract only).
  - SAE ABS braking figures (abstract).
  - ADXL345 app note (garbled extract).
  - Candefjord: thresholds are proprietary, so only qualitative findings are used.
  - BMI270 ranges (datasheet snippet).
- **Doze and suspend.** Gaps from the non-wake-up FIFO with the screen off depend on the drain strategy in android-background.md. Measure `gapCount` overnight on the device.
- **Vendor `qti.sensor.free_fall` / `pocket` sensors** could be cheap wake hints. They are not public API and their availability to third-party apps is untested.

## References

- Bagalà F. et al., "Evaluation of Accelerometer-Based Fall Detection Algorithms on Real-World Falls", PLoS ONE 2012. Covers Bourke 2007/2010, Kangas 2008 and Chen thresholds, plus real-world SE/SP. https://pmc.ncbi.nlm.nih.gov/articles/PMC3353905/
- Sucerquia A. et al., "SisFall: A Fall and Movement Dataset", Sensors 17(1):198, 2017. https://pmc.ncbi.nlm.nih.gov/articles/PMC5298771/
- Figueiredo I. N. et al., "Exploring smartphone sensors for fall detection" (MobiFall thresholds, Table 10), mUX 2016. https://link.springer.com/article/10.1186/s13678-016-0004-1
- Bourke A. K., Lyons G. M., "A threshold-based fall-detection algorithm using a bi-axial gyroscope sensor", Med Eng Phys 30:84–90, 2008 (abstract) **[S]**. https://research.universityofgalway.ie/en/publications/a-threshold-based-fall-detection-algorithm-using-a-bi-axial-gyros-3/
- Locey C. M. et al., "Homogenization of Vehicle Fleet Frontal Crash Pulses from 2000–2010", AAAM 2012. https://pmc.ncbi.nlm.nih.gov/articles/PMC3503406/
- NHTSA, "Crash pulse modeling for vehicle safety research", 18th ESV (half-sine as the standard pulse) **[S]**. https://www.nhtsa.gov/document/paper-18th-esv-crash-pulsemodeling-vehicle-safety-research
- Eriksson J. et al., "The Pothole Patrol", MobiSys 2008. https://people.cs.rutgers.edu/~badri/553dir/papers/sensor/mobisys08.pdf
- "A Machine Learning Approach for Smartphone-based Sensing of Roads and Driving Style" (pothole/bump thresholds) **[S]**. https://arxiv.org/pdf/1908.10187
- SAE 2023-01-0616, ABS braking decelerations (abstract) **[S]**. https://saemobilus.sae.org/content/2023-01-0616/
- Candefjord S. et al., "Using Smartphones to Monitor Cycling and Automatically Detect Accidents", 2014. https://publications.lib.chalmers.se/records/fulltext/211381/local_211381.pdf
- Android Sensors overview: 200 Hz cap, `HIGH_SAMPLING_RATE_SENSORS`, background limits, rate is a hint. https://developer.android.com/develop/sensors-and-location/sensors/sensors_overview
- Android 16 CDD §7.3, 7.3.1, 7.3.4: timestamps, jitter < 3%, ≥ 4 g, noise ≤ 0.05 m/s², gyro ≥ 1000 dps. https://source.android.com/docs/compatibility/16/android-16-cdd
- AOSP, Sensors suspend mode: non-wake-up FIFO behaviour and app wake locks. https://source.android.com/docs/core/interaction/sensors/suspend-mode
- Bosch BMI270 datasheet BST-BMI270-DS000 (ranges, ODR) **[S]**. https://cdn.sparkfun.com/assets/5/c/0/0/d/bst-bmi270-ds000.pdf
- Project notes **[N]**:
  - `research_notes/Android crash detection market/detection_algorithms.md`: WreckWatch, AGH ICCS 2021, VZCrash, Damoov, Apple/Google.
  - `research_notes/Pedestrian accident detection fusion/sensor_fusion_fall_detection.md`: Northwestern JNER 2021, P2Est, Casilari, Tsinganos.
  - `research_notes/Pedestrian accident detection fusion/pedestrian_collision_studies.md`: Forman IRCOBI 2015, Sony US10803721B2, Ravani.
  - `reports/*.md`.
