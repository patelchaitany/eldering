package com.eldering.safety.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One location fix in the on-device ring buffer (`TrailStore`, last 65 min). This is the only place the
 * location history lives: the server keeps just the latest point unless an incident uploads a trail.
 */
@Entity(
    tableName = "trail_points",
    // Unique: Fused Location may redeliver a fix (batches, flushes); the duplicate is ignored on insert.
    indices = [Index(value = ["recorded_at"], unique = true)],
)
data class TrailPointEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Fix time, wall-clock epoch ms (`Location.time`). */
    @ColumnInfo(name = "recorded_at") val recordedAtMs: Long,
    /** Fix time on the monotonic clock (`Location.elapsedRealtimeNanos`), aligned with sensor timestamps. */
    @ColumnInfo(name = "elapsed_realtime_nanos") val elapsedRealtimeNanos: Long?,
    val lat: Double,
    val lng: Double,
    @ColumnInfo(name = "accuracy_m") val accuracyM: Float?,
    @ColumnInfo(name = "speed_mps") val speedMps: Float?,
    @ColumnInfo(name = "bearing_deg") val bearingDeg: Float?,
    @ColumnInfo(name = "altitude_m") val altitudeM: Double?,
    /** Wire name of [com.eldering.safety.network.Activity] at the time of the fix, if known. */
    val activity: String?,
)

enum class DetectionOutcome {
    /** Countdown running. */
    PENDING,
    /** User pressed "I'm OK" during the countdown (candidate false alarm). */
    CANCELLED,
    /** Countdown expired or "Send help now": the incident was reported. */
    REPORTED,
    /** Suppressed before any countdown (e.g. protection paused). */
    SUPPRESSED,
}

enum class FeedbackLabel {
    FALSE_ALARM,
    REAL_INCIDENT,
    UNSURE,
}

/**
 * A detector event with its features, kept on the device for false-alarm feedback and threshold tuning
 * (SPEC: "After cancel: false-alarm feedback stored locally with the event window").
 */
@Entity(tableName = "detection_events", indices = [Index("triggered_at")])
data class DetectionEventRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Wall-clock epoch ms of the impact. */
    @ColumnInfo(name = "triggered_at") val triggeredAtMs: Long,
    /** `DetectionEvent.triggeredAtNanos` (sensor / elapsedRealtime time base). */
    @ColumnInfo(name = "sensor_time_nanos") val sensorTimeNanos: Long,
    /** `EventKind` name, e.g. `VEHICLE_CRASH`. */
    val kind: String,
    val confidence: Float,
    val reason: String,
    /** `Mode` name at detection time. */
    val mode: String?,
    /** `DetectionEvent.features`, stored as a JSON object. */
    @ColumnInfo(name = "features_json") val features: Map<String, Float>,
    val lat: Double?,
    val lng: Double?,
    val outcome: DetectionOutcome = DetectionOutcome.PENDING,
    val label: FeedbackLabel? = null,
    @ColumnInfo(name = "label_note") val labelNote: String? = null,
    @ColumnInfo(name = "labeled_at") val labeledAtMs: Long? = null,
    /** Raw sensor window around the event (JSON, written by the caller), for offline analysis of false alarms. */
    @ColumnInfo(name = "window_json") val windowJson: String? = null,
    /** Local key of the incident this event produced, once reported. */
    @ColumnInfo(name = "incident_key") val incidentKey: String? = null,
)

/** An emergency SMS recipient. Stored on this device only (SPEC); never uploaded and excluded from backups. */
@Entity(tableName = "emergency_contacts", indices = [Index("position")])
data class EmergencyContact(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    /** Normalized: digits with an optional leading '+'. */
    val phone: String,
    val relation: String? = null,
    /** Sort order in the list (and SMS order). */
    val position: Int = 0,
    @ColumnInfo(name = "sms_enabled") val smsEnabled: Boolean = true,
    @ColumnInfo(name = "created_at") val createdAtMs: Long,
)

enum class OwnIncidentStatus {
    /** Stored locally, create request not acknowledged by the server yet. */
    QUEUED,
    /** Created on the server; family is being alerted. */
    ACTIVE,
    /** Resolved (any resolution). */
    RESOLVED,
    /** Cancelled locally before it ever reached the server. */
    CANCELLED,
    /** The server permanently rejected the upload (see the outbox row's last error). */
    FAILED,
}

/**
 * An incident raised by this user from this device (accident or SOS). The local [incidentKey] exists before the
 * server id does and doubles as the create request's `Idempotency-Key`.
 */
@Entity(
    tableName = "own_incidents",
    indices = [Index(value = ["server_id"], unique = true), Index("user_id")],
)
data class OwnIncident(
    @PrimaryKey @ColumnInfo(name = "incident_key") val incidentKey: String,
    /** Account that raised it; uploads only ever run under this account. */
    @ColumnInfo(name = "user_id") val userId: String,
    @ColumnInfo(name = "server_id") val serverId: String? = null,
    /** Wire name of [com.eldering.safety.network.IncidentKind]. */
    val kind: String,
    @ColumnInfo(name = "detected_at") val detectedAtMs: Long,
    val lat: Double?,
    val lng: Double?,
    /** Public share link (contains the share secret; app-private storage only). */
    @ColumnInfo(name = "share_url") val shareUrl: String? = null,
    @ColumnInfo(name = "share_expires_at") val shareExpiresAtMs: Long? = null,
    val status: OwnIncidentStatus = OwnIncidentStatus.QUEUED,
    /** Wire name of [com.eldering.safety.network.Resolution] once resolved. */
    val resolution: String? = null,
    @ColumnInfo(name = "created_at") val createdAtMs: Long,
    @ColumnInfo(name = "updated_at") val updatedAtMs: Long,
)

enum class OutboxType {
    CREATE_INCIDENT,
    APPEND_TRAIL,
    RESOLVE_INCIDENT,
}

enum class OutboxState {
    PENDING,
    /** Permanently rejected (client error) or expired; kept for diagnostics, never retried. */
    FAILED,
}

/**
 * A pending incident upload, live-trail append or resolve, retried by WorkManager until the server accepts it.
 * Rows of one incident are sent strictly in [id] order (create → appends → resolve).
 */
@Entity(
    tableName = "outbox",
    indices = [Index("incident_key"), Index(value = ["state", "next_attempt_at"])],
)
data class OutboxItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val type: OutboxType,
    @ColumnInfo(name = "incident_key") val incidentKey: String,
    @ColumnInfo(name = "user_id") val userId: String,
    /** JSON request body for [type]. */
    @ColumnInfo(name = "payload_json") val payloadJson: String,
    @ColumnInfo(name = "created_at") val createdAtMs: Long,
    val attempts: Int = 0,
    /** Earliest wall-clock time of the next opportunistic attempt (WorkManager retries ignore it). */
    @ColumnInfo(name = "next_attempt_at") val nextAttemptAtMs: Long = 0,
    @ColumnInfo(name = "last_error") val lastError: String? = null,
    val state: OutboxState = OutboxState.PENDING,
)
