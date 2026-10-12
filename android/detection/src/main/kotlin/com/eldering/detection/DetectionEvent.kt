package com.eldering.detection

/** Kinds of accident the detector can report. */
public enum class EventKind { VEHICLE_CRASH, PEDESTRIAN_IMPACT, FALL }

/**
 * A detected accident candidate that passed analysis and scoring.
 *
 * @property confidence score in 0..1 that crossed the kind's emit threshold.
 * @property triggeredAtNanos sensor time of the impact peak (same time base as [SensorSample.tNanos]).
 * @property features computed window features (for example peak g, free-fall ms, rotation degrees),
 *   kept for alert details and false-alarm feedback.
 * @property reason short human-readable explanation of why the event fired.
 */
public data class DetectionEvent(
    val kind: EventKind,
    val confidence: Float,
    val triggeredAtNanos: Long,
    val features: Map<String, Float>,
    val reason: String,
)

/**
 * Diagnostics for one analysed candidate (see [AccidentDetector.candidateObserver]).
 *
 * @property t0Nanos sensor time of the candidate's SMV peak.
 * @property mode mode reported at [t0Nanos].
 * @property kind emitted kind, or null if the candidate was rejected.
 * @property confidence emitted confidence, 0 when rejected.
 * @property verdict the event's reason when emitted, otherwise the rejection tag (for example `phone_drop`,
 *   `gait_continues`, `violence<2`, `score_low`, `cooldown`, `insufficient_data`).
 * @property features window features and shadow scores ([FeatureKeys]); empty when the window was not analysed.
 */
public data class CandidateOutcome(
    val t0Nanos: Long,
    val mode: Mode,
    val kind: EventKind?,
    val confidence: Float,
    val verdict: String,
    val features: Map<String, Float>,
)
