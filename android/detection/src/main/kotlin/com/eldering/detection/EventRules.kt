package com.eldering.detection

import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Why a candidate was not emitted (research §2.3, debug log vocabulary). */
internal enum class Rejection(val tag: String) {
    INSUFFICIENT_DATA("insufficient_data"),
    IMPULSE_ONLY("impulse_only"),
    PHONE_DROP("phone_drop"),
    TOSS("toss"),
    CONTINUED_DRIVING("continued_driving"),
    GAIT_CONTINUES("gait_continues"),
    RECOVERED("recovered"),
    VIOLENCE_BELOW_2("violence<2"),
    NO_POSTURE_CHANGE("no_posture_change"),
    BELOW_FALL_MIN("below_fall_min"),
    SCORE_LOW("score_low"),
    COOLDOWN("cooldown"),
}

/** Context of one candidate that is not a window feature. */
internal data class RuleContext(
    /** Mode reported at t0 (selects the threshold row). */
    val mode: Mode,
    /** VEHICLE reported within the hold time, or fast GPS speed (research §0.3). */
    val vehicleContext: Boolean,
    /** Peak-capture factor for the sampling period at the impact (research §2.1). */
    val captureFactor: Double,
)

/** Result of scoring one candidate. Points are hundredths of confidence, so rule arithmetic is exact. */
internal data class Decision(
    val kind: EventKind?,
    val points: Int,
    val rejection: Rejection?,
    val reason: String,
    /** Contributions of the emitted kind's score terms, in points. */
    val parts: List<Pair<String, Int>>,
    val crashPoints: Int?,
    val strikePoints: Int?,
    val fallPoints: Int?,
) {
    val confidence: Float get() = (points / 100f).coerceIn(0f, 1f)
}

/**
 * Mode-specific scoring with suppression (research §2.3 and §3). Evaluated in order VEHICLE_CRASH,
 * PEDESTRIAN_IMPACT, FALL after the global suppressions; the first kind that reaches its emit threshold wins.
 */
internal class EventRules(private val cfg: DetectorConfig) {

    private class Score(base: Int, baseName: String = "base") {
        val parts = ArrayList<Pair<String, Int>>().apply { add(baseName to base) }
        var total = base
            private set

        fun add(name: String, points: Int, condition: Boolean) {
            if (!condition) return
            parts.add(name to points)
            total += points
        }

        fun cap(limit: Int, name: String) {
            if (total > limit) {
                parts.add(name to (limit - total))
                total = limit
            }
        }
    }

    fun classify(f: WindowFeatures, ctx: RuleContext): Decision {
        val th = cfg.thresholdsFor(ctx.mode)
        val rangeCap = cfg.rangeHeadroom * f.accelRangeG
        fun eff(g: Float): Double = min(g * ctx.captureFactor, rangeCap)

        val peak = f.peakG
        val sb = f.speedBeforeMps
        val sa = f.speedAfterMps
        val movingInVehicle = ctx.vehicleContext && (sb == null || sb >= cfg.crashSpeedGateMps)
        val orient = f.orientationChangeDeg ?: 0.0
        var crashPts: Int? = null
        var strikePts: Int? = null

        // ---- Global suppression: a short impulse with no posture change and no fall before it.
        val periodMs = 1000.0 / f.rateHz
        if (f.aboveTwoGMs <= max(cfg.sharpImpactMaxMs.toDouble(), periodMs + 1.0) &&
            orient < cfg.impulseMaxOrientDeg &&
            (f.rotationDeg ?: 0.0) < cfg.impulseMaxRotDeg &&
            f.minSmvPreG > cfg.impulseMinPreG
        ) {
            return reject(Rejection.IMPULSE_ONLY, null, null, null)
        }
        val sharp = f.rateHz >= cfg.sharpMinRateHz && f.aboveTwoGMs <= cfg.sharpImpactMaxMs
        val quietAfter = (f.restNoiseG != null && f.restNoiseG <= cfg.restNoiseG) || f.recovered
        val drop = f.trueFreeFallMs >= cfg.trueFreeFallMinMs &&
            (sharp || f.bounceAirborneMs >= cfg.bounceAirborneMinMs || f.handHeldBefore) && quietAfter
        val toss = f.airborneAfterMs >= cfg.airborneMinMs && peak < eff(cfg.tossMaxPeakG) && quietAfter

        // ---- VEHICLE_CRASH
        if (ctx.vehicleContext &&
            (sb == null || sb >= cfg.crashSpeedGateMps) &&
            (peak >= eff(cfg.crashImpactG) || f.saturatedMs >= cfg.crashMinSatMs) &&
            (f.aboveTwoGMs >= cfg.crashEnergyMs || f.dvLowerBoundMps >= cfg.crashMinDvMps)
        ) {
            // Deviation from research §2.3: with no speed before, any speed ≥ the gate after also means "kept driving".
            if (sa != null && sa >= max(cfg.crashSpeedGateMps.toDouble(), cfg.continuedFraction * (sb ?: 0.0))) {
                return reject(Rejection.CONTINUED_DRIVING, null, null, null)
            }
            val s = Score(40)
            s.add("stopped_after", 30, sa != null && sa <= cfg.stoppedMps)
            s.add("no_speed_after", -15, sa == null)
            s.add("fast_before", 10, sb != null && sb >= cfg.crashSpeedHighMps)
            s.add("strong_impact", 10, f.saturatedMs >= cfg.crashStrongSatMs || peak >= eff(cfg.crashStrongG))
            s.add("high_dv", 10, f.dvLowerBoundMps >= cfg.crashStrongDvMps)
            s.add("rotation", 5, (f.rotationDeg ?: 0.0) >= cfg.crashRotationDeg)
            s.add("multiple_impacts", 5, f.impacts1s >= 2)
            s.add("pressure_spike", 10, f.pressureDeltaHpa != null && abs(f.pressureDeltaHpa) >= cfg.cabinPressureSpikeHpa)
            if (sa == null) s.cap(points(cfg.crashNoSpeedAfterCap), "no_speed_after_cap")
            s.cap(100, "cap")
            crashPts = s.total
            if (s.total >= points(cfg.emitCrash)) {
                return emit(EventKind.VEHICLE_CRASH, s, crashReason(f), crashPts, null, null)
            }
        }

        // ---- PEDESTRIAN_IMPACT
        if (!movingInVehicle && !drop && !toss) {
            val impactOk = peak >= eff(cfg.strikeImpactG) || f.saturatedMs >= cfg.strikeMinSatMs
            val preRotOk = strikePreRotationOk(f)
            if (impactOk && preRotOk) {
                val s = Score(35)
                s.add("horizontal", 15, f.horizRatio >= cfg.strikeHorizRatio)
                s.add("strike_rotation", 15, (f.strikeRotationDeg ?: 0.0) >= cfg.strikeRotDeg)
                s.add("multiple_impacts", 10, f.impacts15s >= 2)
                s.add("airborne", 10, f.airborneAfterMs >= cfg.airborneMinMs)
                // Research §2.3 uses the same 8 g "strong impact" level as the crash rule.
                s.add("strong_impact", 10, f.saturatedMs >= cfg.strikeMinSatMs || peak >= eff(cfg.crashStrongG))
                s.add("orientation", 15, orient >= cfg.strikeOrientDeg)
                s.add("still_after", 15, f.stillFraction >= cfg.stillFractionMin)
                s.add("high_dv", 10, f.dvLowerBoundMps >= cfg.strikeDvMps)
                s.add("recovered", -25, f.recovered)
                strikePts = s.total
                if (s.total >= points(cfg.emitStrike)) {
                    s.cap(100, "cap")
                    return emit(EventKind.PEDESTRIAN_IMPACT, s, strikeReason(f), crashPts, strikePts, null)
                }
            }
        }

        // ---- FALL
        if (drop) return reject(Rejection.PHONE_DROP, crashPts, strikePts, null)
        if (toss) return reject(Rejection.TOSS, crashPts, strikePts, null)
        if (f.gaitPre >= cfg.gaitAutocorrMin && f.gaitPost >= cfg.gaitAutocorrMin) {
            return reject(Rejection.GAIT_CONTINUES, crashPts, strikePts, null)
        }
        val saturated = f.saturatedMs >= cfg.fallMinSatMs
        val fallMin = if (movingInVehicle) eff(cfg.fallInMovingVehicleMinG) else eff(th.fallMinG)
        if (!(peak >= fallMin || saturated)) return reject(Rejection.BELOW_FALL_MIN, crashPts, strikePts, null)
        var votes = 0
        if (peak >= eff(th.fallMediumG) || saturated) votes++
        if (f.freeFallMs >= cfg.partialFreeFallMinMs) votes++
        val fast = f.fastRotationDeg
        val rotationVote = if (fast != null) fast >= cfg.fastRotationDeg else cfg.noGyroOrientationVote && orient >= cfg.noGyroOrientVoteDeg
        if (rotationVote) votes++
        if (f.jerkPeakGps >= th.jerkGps) votes++
        if (votes < cfg.minViolenceVotes) return reject(Rejection.VIOLENCE_BELOW_2, crashPts, strikePts, null)
        val postureOk = if (movingInVehicle) {
            orient >= cfg.orientMinDeg
        } else {
            orient >= cfg.orientMinDeg || f.stillFraction >= cfg.stillFractionMin
        }
        if (!postureOk) return reject(Rejection.NO_POSTURE_CHANGE, crashPts, strikePts, null)
        if (f.recovered) return reject(Rejection.RECOVERED, crashPts, strikePts, null)

        val s = Score(20)
        when {
            peak >= eff(th.fallStrongG) || saturated -> s.add("strong_impact", 25, true)
            peak >= eff(th.fallMediumG) -> s.add("medium_impact", 15, true)
            else -> s.add("light_impact", 5, true)
        }
        s.add("freefall", 15, f.freeFallMs >= cfg.partialFreeFallMinMs)
        s.add("rotation", 15, (f.rotationDeg ?: 0.0) >= cfg.fallRotationDeg)
        when {
            orient >= cfg.orientStrongDeg -> s.add("orientation", 20, true)
            orient >= cfg.orientMinDeg -> s.add("orientation", 10, true)
        }
        s.add("still_after", 25, f.stillFraction >= cfg.stillFractionMin)
        s.add("pressure_rise", 10, f.pressureDeltaHpa != null && f.pressureDeltaHpa >= cfg.fallPressureRiseHpa)
        s.cap(100, "cap")
        val fallPts = s.total
        if (s.total >= points(cfg.emitFall)) return emit(EventKind.FALL, s, fallReason(f), crashPts, strikePts, fallPts)
        return reject(Rejection.SCORE_LOW, crashPts, strikePts, fallPts)
    }

    /**
     * "Impact first, rotation after" (research §2.3): little rotation before t0. Rotation that merely matches the
     * ongoing gait swing also passes when a large post-impact rotation confirms the strike (see
     * [DetectorConfig.strikePreRotGaitMarginDeg]). Without a gyroscope, no pre-impact free fall is required instead.
     */
    private fun strikePreRotationOk(f: WindowFeatures): Boolean {
        val pre = f.preRotationDeg ?: return f.freeFallMs < cfg.strikeNoGyroMaxFreeFallMs
        if (pre <= cfg.strikeMaxPreRotDeg) return true
        val baseline = f.baselineRotationDeg ?: return false
        val strike = f.strikeRotationDeg ?: return false
        return strike >= cfg.strikeRotDeg && pre <= baseline + cfg.strikePreRotGaitMarginDeg
    }

    private fun points(confidence: Float): Int = Math.round(confidence * 100f)

    private fun emit(kind: EventKind, s: Score, reason: String, crash: Int?, strike: Int?, fall: Int?): Decision =
        Decision(kind, s.total, null, reason, s.parts.toList(), crash, strike, fall)

    private fun reject(r: Rejection, crash: Int?, strike: Int?, fall: Int?): Decision =
        Decision(null, 0, r, r.tag, emptyList(), crash, strike, fall)

    // ---- human-readable reasons

    private fun impactText(f: WindowFeatures): String = buildString {
        append(fmt(f.peakG)).append(" g impact")
        if (f.saturatedMs > 0) append(" (sensor clipped ").append(fmt0(f.saturatedMs)).append(" ms, so at least that)")
    }

    private fun crashReason(f: WindowFeatures): String = buildString {
        append("Vehicle crash: ").append(impactText(f))
        append(", Δv ≥ ").append(fmt(f.dvLowerBoundMps)).append(" m/s")
        val sb = f.speedBeforeMps
        val sa = f.speedAfterMps
        when {
            sb != null && sa != null -> append(", speed ").append(fmt(sb)).append(" → ").append(fmt(sa)).append(" m/s")
            sb != null -> append(", speed ").append(fmt(sb)).append(" m/s before, no GPS after")
            sa != null -> append(", ").append(fmt(sa)).append(" m/s after")
            else -> append(", no GPS speed")
        }
        if (f.impacts1s >= 2) append(", ").append(f.impacts1s).append(" impacts in 1 s")
    }

    private fun strikeReason(f: WindowFeatures): String = buildString {
        append("Pedestrian impact: ").append(impactText(f))
        if (f.horizRatio >= cfg.strikeHorizRatio) append(", mostly horizontal")
        if (f.impacts15s >= 2) append(", ").append(f.impacts15s).append(" impacts in 1.5 s")
        f.strikeRotationDeg?.let { append(", body rotated ").append(fmt0(it)).append("° after the hit") }
        f.orientationChangeDeg?.let { append(", posture changed ").append(fmt0(it)).append("°") }
        if (f.stillFraction >= cfg.stillFractionMin) append(", still afterwards")
    }

    private fun fallReason(f: WindowFeatures): String = buildString {
        append("Fall: ").append(impactText(f))
        if (f.freeFallMs >= cfg.partialFreeFallMinMs) append(" after ").append(fmt0(f.freeFallMs)).append(" ms of free fall")
        f.fastRotationDeg?.let { append(", rotated ").append(fmt0(it)).append("° within 0.5 s") }
        f.orientationChangeDeg?.let { append(", posture changed ").append(fmt0(it)).append("°") }
        if (f.stillFraction >= cfg.stillFractionMin) append(", lying still afterwards")
        // No "height drop" wording: most target phones have no barometer (research §6.3).
    }

    private fun fmt(v: Double): String = String.format(Locale.ROOT, "%.1f", v)

    private fun fmt0(v: Double): String = String.format(Locale.ROOT, "%.0f", v)
}
