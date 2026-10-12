package com.eldering.safety.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/*
 * Wire models for SPEC "HTTP API". Field names match the server's JSON exactly (snake_case via @SerialName);
 * see the Go handlers in server/internal/handlers. Fields the SPEC table does not list but the server sends are optional here
 * so the client keeps working against older or newer servers.
 */

// ---------------------------------------------------------------------------------------------------------------
// Enums
// ---------------------------------------------------------------------------------------------------------------

@Serializable
enum class SharingState {
    @SerialName("on") ON,
    @SerialName("paused") PAUSED,
    @SerialName("off") OFF,
}

/** What a sharer lets one viewer see (share_grants.level). */
@Serializable
enum class GrantLevel {
    @SerialName("live") LIVE,
    @SerialName("emergency") EMERGENCY,
    @SerialName("off") OFF,
}

@Serializable
enum class FamilyRole {
    @SerialName("admin") ADMIN,
    @SerialName("member") MEMBER,
}

/** Visibility of a co-member's live location for the caller (`GET /v1/family/locations`). */
@Serializable
enum class LocationStatus {
    @SerialName("live") LIVE,
    @SerialName("paused") PAUSED,
    @SerialName("hidden") HIDDEN,
}

@Serializable
enum class IncidentKind {
    @SerialName("vehicle_crash") VEHICLE_CRASH,
    @SerialName("pedestrian_impact") PEDESTRIAN_IMPACT,
    @SerialName("fall") FALL,
    @SerialName("sos") SOS,
}

@Serializable
enum class IncidentStatus {
    @SerialName("active") ACTIVE,
    @SerialName("resolved") RESOLVED,
    @SerialName("cancelled") CANCELLED,
}

@Serializable
enum class Resolution {
    /** Owner: "I'm OK" after the alert went out. */
    @SerialName("user_ok") USER_OK,
    /** Owner: the detection was wrong. */
    @SerialName("false_alarm") FALSE_ALARM,
    /** Owner or a viewer with access: help arrived. */
    @SerialName("handled") HANDLED,
}

@Serializable
enum class AckState {
    @SerialName("seen") SEEN,
    @SerialName("on_my_way") ON_MY_WAY,
}

/** Movement activity; the names match `com.eldering.detection.Mode` (the server accepts them case-insensitively). */
@Serializable
enum class Activity {
    @SerialName("still") STILL,
    @SerialName("walking") WALKING,
    @SerialName("running") RUNNING,
    @SerialName("cycling") CYCLING,
    @SerialName("vehicle") VEHICLE,
    @SerialName("unknown") UNKNOWN,
}

// ---------------------------------------------------------------------------------------------------------------
// Errors
// ---------------------------------------------------------------------------------------------------------------

/** SPEC error envelope `{error:{code,message}}`. */
@Serializable
data class ErrorEnvelope(val error: ErrorBody)

@Serializable
data class ErrorBody(val code: String, val message: String = "")

// ---------------------------------------------------------------------------------------------------------------
// Auth
// ---------------------------------------------------------------------------------------------------------------

@Serializable
data class RegisterRequest(
    val email: String,
    val password: String,
    @SerialName("display_name") val displayName: String,
) {
    override fun toString(): String = "RegisterRequest(email=$email, password=██, displayName=$displayName)"
}

@Serializable
data class LoginRequest(val email: String, val password: String) {
    override fun toString(): String = "LoginRequest(email=$email, password=██)"
}

/** Body of `/v1/auth/refresh` and `/v1/auth/logout`. */
@Serializable
data class RefreshTokenRequest(@SerialName("refresh_token") val refreshToken: String) {
    override fun toString(): String = "RefreshTokenRequest(refreshToken=██)"
}

@Serializable
data class UserDto(
    val id: String,
    val email: String,
    @SerialName("display_name") val displayName: String,
    @SerialName("created_at") val createdAt: ApiInstant? = null,
)

/** Response of register, login and refresh. */
@Serializable
data class AuthResponse(
    @SerialName("access_token") val accessToken: String,
    @SerialName("refresh_token") val refreshToken: String,
    val user: UserDto,
    @SerialName("token_type") val tokenType: String = "Bearer",
    /** Access-token lifetime in seconds (15 min); 0 if the server did not say. */
    @SerialName("expires_in") val expiresIn: Long = 0,
    @SerialName("access_expires_at") val accessExpiresAt: ApiInstant? = null,
    @SerialName("refresh_expires_at") val refreshExpiresAt: ApiInstant? = null,
) {
    override fun toString(): String =
        "AuthResponse(accessToken=██, refreshToken=██, user=$user, expiresIn=$expiresIn)"
}

// ---------------------------------------------------------------------------------------------------------------
// Me + settings
// ---------------------------------------------------------------------------------------------------------------

@Serializable
data class SettingsDto(
    @SerialName("sharing_state") val sharingState: SharingState,
    /** End of a timed pause; null while not paused or paused indefinitely. */
    @SerialName("paused_until") val pausedUntil: ApiInstant? = null,
    @SerialName("always_alert_on_accident") val alwaysAlertOnAccident: Boolean = true,
    /** [sharingState] with an elapsed timed pause folded back to [SharingState.ON] (server-computed). */
    @SerialName("effective_sharing_state") val effectiveSharingState: SharingState = sharingState,
)

@Serializable
data class FamilyDto(
    val id: String,
    val name: String,
    /** The caller's role in this family. */
    val role: FamilyRole,
    @SerialName("joined_at") val joinedAt: ApiInstant? = null,
    @SerialName("member_count") val memberCount: Int = 0,
    @SerialName("created_at") val createdAt: ApiInstant? = null,
)

/** `GET /v1/me`. */
@Serializable
data class MeResponse(
    val user: UserDto,
    val settings: SettingsDto,
    val families: List<FamilyDto> = emptyList(),
)

/** How a settings PATCH changes `paused_until`. */
sealed interface PauseEnd {
    /** Explicit JSON `null`: paused until switched back on. */
    data object Indefinite : PauseEnd

    data class At(val instant: java.time.Instant) : PauseEnd
}

/**
 * `PATCH /v1/me/settings`. Every field is optional; null means "leave unchanged". The server distinguishes an
 * absent `paused_until` from an explicit `null`, so the body is built by hand instead of by the serializer.
 */
data class SettingsPatch(
    val sharingState: SharingState? = null,
    val pausedUntil: PauseEnd? = null,
    val alwaysAlertOnAccident: Boolean? = null,
) {
    val isEmpty: Boolean get() = sharingState == null && pausedUntil == null && alwaysAlertOnAccident == null

    fun toJson(): JsonObject = buildJsonObject {
        sharingState?.let { put("sharing_state", ApiJson.encodeToJsonElement(SharingState.serializer(), it)) }
        when (val p = pausedUntil) {
            null -> Unit
            PauseEnd.Indefinite -> put("paused_until", JsonNull)
            is PauseEnd.At -> put("paused_until", ApiJson.encodeToJsonElement(InstantSerializer, p.instant))
        }
        alwaysAlertOnAccident?.let { put("always_alert_on_accident", JsonPrimitive(it)) }
    }
}

// ---------------------------------------------------------------------------------------------------------------
// Families, invites, members, grants
// ---------------------------------------------------------------------------------------------------------------

/** Body of `POST /v1/families` and `PATCH /v1/families/{id}`. */
@Serializable
data class FamilyNameRequest(val name: String)

@Serializable
data class InviteDto(
    /** 8 characters, Crockford base32. */
    val code: String,
    @SerialName("expires_at") val expiresAt: ApiInstant,
    @SerialName("max_uses") val maxUses: Int? = null,
)

/** `POST /v1/families`: the new family (Go embeds it, so its fields are top-level) plus the first invite. */
@Serializable
data class CreateFamilyResponse(
    val id: String,
    val name: String,
    val role: FamilyRole = FamilyRole.ADMIN,
    @SerialName("joined_at") val joinedAt: ApiInstant? = null,
    @SerialName("member_count") val memberCount: Int = 1,
    @SerialName("created_at") val createdAt: ApiInstant? = null,
    val invite: InviteDto,
) {
    val family: FamilyDto get() = FamilyDto(id, name, role, joinedAt, memberCount, createdAt)
}

@Serializable
data class JoinFamilyRequest(val code: String)

@Serializable
data class FamilyMemberDto(
    @SerialName("user_id") val userId: String,
    @SerialName("display_name") val displayName: String,
    val role: FamilyRole,
    @SerialName("joined_at") val joinedAt: ApiInstant? = null,
    @SerialName("is_me") val isMe: Boolean = false,
    /** What I share with them; null for myself. */
    @SerialName("my_grant") val myGrant: GrantLevel? = null,
    /** What they share with me; null for myself. */
    @SerialName("their_grant") val theirGrant: GrantLevel? = null,
)

/** One of my outgoing grants (`GET /v1/grants`, `PUT /v1/grants/{viewerId}`). */
@Serializable
data class GrantDto(
    @SerialName("viewer_id") val viewerId: String,
    @SerialName("display_name") val displayName: String = "",
    val level: GrantLevel,
    /** True when no explicit grant exists yet (SPEC default: emergency). */
    @SerialName("is_default") val isDefault: Boolean = false,
    @SerialName("updated_at") val updatedAt: ApiInstant? = null,
)

@Serializable
data class SetGrantRequest(val level: GrantLevel)

// ---------------------------------------------------------------------------------------------------------------
// Location
// ---------------------------------------------------------------------------------------------------------------

@Serializable
data class HeartbeatRequest(
    val lat: Double,
    val lng: Double,
    @SerialName("accuracy_m") val accuracyM: Float? = null,
    @SerialName("speed_mps") val speedMps: Float? = null,
    @SerialName("bearing_deg") val bearingDeg: Float? = null,
    val activity: Activity? = null,
    @SerialName("battery_pct") val batteryPct: Int? = null,
    @SerialName("recorded_at") val recordedAt: ApiInstant,
)

@Serializable
data class HeartbeatResponse(
    /** Active incidents I can see and have not acknowledged yet. */
    @SerialName("pending_alerts") val pendingAlerts: List<IncidentSummaryDto> = emptyList(),
    @SerialName("server_time") val serverTime: ApiInstant,
)

/** A co-member on the family map. Coordinates are present only when [status] is [LocationStatus.LIVE]. */
@Serializable
data class MemberLocationDto(
    @SerialName("user_id") val userId: String,
    @SerialName("display_name") val displayName: String,
    val status: LocationStatus = LocationStatus.HIDDEN,
    val lat: Double? = null,
    val lng: Double? = null,
    @SerialName("accuracy_m") val accuracyM: Float? = null,
    @SerialName("speed_mps") val speedMps: Float? = null,
    @SerialName("bearing_deg") val bearingDeg: Float? = null,
    val activity: Activity? = null,
    @SerialName("battery_pct") val batteryPct: Int? = null,
    @SerialName("recorded_at") val recordedAt: ApiInstant? = null,
) {
    val hasPosition: Boolean get() = status == LocationStatus.LIVE && lat != null && lng != null
}

// ---------------------------------------------------------------------------------------------------------------
// Incidents
// ---------------------------------------------------------------------------------------------------------------

/** Trail point sent with an incident, returned in incident detail and in the public share feed. */
@Serializable
data class TrailPointDto(
    val lat: Double,
    val lng: Double,
    @SerialName("recorded_at") val recordedAt: ApiInstant,
    @SerialName("accuracy_m") val accuracyM: Float? = null,
    /** Reverse-geocoded label for a few key points (≤200 chars); decoration only, never safety-critical. */
    val landmark: String? = null,
)

@Serializable
data class CreateIncidentRequest(
    val kind: IncidentKind,
    @SerialName("detected_at") val detectedAt: ApiInstant,
    val lat: Double? = null,
    val lng: Double? = null,
    /** Free-form JSON object (≤16 KiB): detector features, confidence, mode… */
    val details: JsonObject = JsonObject(emptyMap()),
    /** Pre-event trail (≤5000 points; keep the whole request under the 1 MB body limit). */
    val trail: List<TrailPointDto> = emptyList(),
)

@Serializable
data class CreateIncidentResponse(
    val id: String,
    @SerialName("share_url") val shareUrl: String,
    @SerialName("share_expires_at") val shareExpiresAt: ApiInstant,
    val status: IncidentStatus = IncidentStatus.ACTIVE,
) {
    override fun toString(): String = "CreateIncidentResponse(id=$id, shareUrl=██, shareExpiresAt=$shareExpiresAt)"
}

@Serializable
data class AppendTrailRequest(val points: List<TrailPointDto>)

@Serializable
data class AppendTrailResponse(
    /** Points actually inserted; duplicates (same recorded_at) are ignored by the server. */
    val accepted: Int = 0,
)

@Serializable
data class ResolveIncidentRequest(val resolution: Resolution)

@Serializable
data class AckIncidentRequest(val state: AckState)

@Serializable
data class PointDto(
    val lat: Double,
    val lng: Double,
    @SerialName("recorded_at") val recordedAt: ApiInstant,
    @SerialName("accuracy_m") val accuracyM: Float? = null,
)

@Serializable
data class IncidentSummaryDto(
    val id: String,
    @SerialName("user_id") val userId: String,
    @SerialName("display_name") val displayName: String = "",
    val kind: IncidentKind,
    val status: IncidentStatus,
    @SerialName("detected_at") val detectedAt: ApiInstant,
    /** Where the incident was detected (null if no fix was available). */
    val lat: Double? = null,
    val lng: Double? = null,
    /** Newest trail point (live updates after detection). */
    @SerialName("last_location") val lastLocation: PointDto? = null,
    @SerialName("share_expires_at") val shareExpiresAt: ApiInstant? = null,
    @SerialName("resolved_at") val resolvedAt: ApiInstant? = null,
    val resolution: Resolution? = null,
    @SerialName("is_mine") val isMine: Boolean = false,
    @SerialName("my_ack") val myAck: AckState? = null,
)

@Serializable
data class AckDto(
    @SerialName("incident_id") val incidentId: String,
    @SerialName("user_id") val userId: String,
    @SerialName("display_name") val displayName: String = "",
    val state: AckState,
    val at: ApiInstant,
)

/** `GET /v1/incidents/{id}`: the summary fields (embedded by the server) plus details, trail and acks. */
@Serializable
data class IncidentDetailDto(
    val id: String,
    @SerialName("user_id") val userId: String,
    @SerialName("display_name") val displayName: String = "",
    val kind: IncidentKind,
    val status: IncidentStatus,
    @SerialName("detected_at") val detectedAt: ApiInstant,
    val lat: Double? = null,
    val lng: Double? = null,
    @SerialName("last_location") val lastLocation: PointDto? = null,
    @SerialName("share_expires_at") val shareExpiresAt: ApiInstant? = null,
    @SerialName("resolved_at") val resolvedAt: ApiInstant? = null,
    val resolution: Resolution? = null,
    @SerialName("is_mine") val isMine: Boolean = false,
    @SerialName("my_ack") val myAck: AckState? = null,
    val details: JsonObject = JsonObject(emptyMap()),
    @SerialName("resolved_by") val resolvedBy: String? = null,
    /** Oldest first. */
    val trail: List<TrailPointDto> = emptyList(),
    /** The owner sees every ack; a viewer only their own. */
    val acks: List<AckDto> = emptyList(),
) {
    val summary: IncidentSummaryDto
        get() = IncidentSummaryDto(
            id, userId, displayName, kind, status, detectedAt, lat, lng, lastLocation, shareExpiresAt,
            resolvedAt, resolution, isMine, myAck,
        )
}

/** `GET /s/{token}/data`: the public share feed. Carries no user identity by design. */
@Serializable
data class SharedIncidentDto(
    val kind: IncidentKind,
    val status: IncidentStatus,
    @SerialName("detected_at") val detectedAt: ApiInstant,
    @SerialName("share_expires_at") val shareExpiresAt: ApiInstant,
    @SerialName("resolved_at") val resolvedAt: ApiInstant? = null,
    val resolution: Resolution? = null,
    @SerialName("incident_location") val incidentLocation: TrailPointDto? = null,
    val latest: TrailPointDto? = null,
    val trail: List<TrailPointDto> = emptyList(),
    @SerialName("server_time") val serverTime: ApiInstant,
)

/** `GET /healthz`: `{"status":"ok"}` (503 with `"unavailable"` when the database is down). */
@Serializable
data class HealthDto(val status: String)
