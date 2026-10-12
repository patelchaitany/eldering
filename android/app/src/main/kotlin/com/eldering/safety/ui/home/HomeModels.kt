package com.eldering.safety.ui.home

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import com.eldering.safety.data.db.OwnIncident
import com.eldering.safety.network.Activity
import com.eldering.safety.network.GrantLevel
import com.eldering.safety.network.IncidentSummaryDto
import com.eldering.safety.network.LocationStatus
import com.eldering.safety.network.MemberLocationDto
import com.eldering.safety.network.SharingState
import com.eldering.safety.ui.common.UiText
import com.eldering.safety.ui.components.initialsOf
import com.eldering.safety.ui.map.CameraRequest
import com.eldering.safety.ui.map.GeoPoint
import com.eldering.safety.ui.platform.MyFix
import com.eldering.safety.ui.platform.ProtectionStatus
import com.eldering.safety.ui.theme.MemberColors
import com.eldering.safety.data.trail.Geo

/** What I can see of a family member right now. */
enum class MemberVisibility {
    /** Live location with coordinates. */
    LIVE,

    /** Shares live with me, but no position has been reported yet. */
    LIVE_NO_FIX,

    /** They paused sharing. */
    PAUSED,

    /** They share with me only during an accident or SOS. */
    EMERGENCY_ONLY,

    /** They don't share their location with me. */
    NOT_SHARING,
}

@Immutable
data class MemberUi(
    val userId: String,
    val name: String,
    val initials: String,
    val color: Color,
    val visibility: MemberVisibility,
    val activity: Activity? = null,
    val speedKmh: Int? = null,
    val position: GeoPoint? = null,
    val accuracyM: Float? = null,
    val recordedAtMs: Long? = null,
    val batteryPct: Int? = null,
    /** Reverse-geocoded place, filled in asynchronously. */
    val place: String? = null,
    /** Straight-line distance from me, if both positions are known. */
    val distanceM: Double? = null,
    /** Their active incident visible to me, if any. */
    val activeIncidentId: String? = null,
    /** On-device emergency contact with the same name (used to pre-fill "Call"). */
    val phone: String? = null,
) {
    val isSharingLive: Boolean get() = visibility == MemberVisibility.LIVE || visibility == MemberVisibility.LIVE_NO_FIX
}

@Immutable
data class MeUi(
    val name: String,
    val position: GeoPoint? = null,
    val recordedAtMs: Long? = null,
    val activity: Activity? = null,
    val batteryPct: Int? = null,
    val place: String? = null,
    /** Effective state from `/v1/me` (null until loaded). */
    val sharing: SharingState? = null,
    val pausedUntilMs: Long? = null,
)

@Immutable
data class HomeUiState(
    val loading: Boolean = true,
    val me: MeUi = MeUi(name = ""),
    val members: List<MemberUi> = emptyList(),
    /** [ME] or a member's user id. */
    val selectedId: String? = null,
    val protection: ProtectionStatus? = null,
    /** Active incidents of family members (not mine), newest first. */
    val familyAlerts: List<IncidentSummaryDto> = emptyList(),
    /** My own accident/SOS that is still open on this phone. */
    val ownIncident: OwnIncident? = null,
    val hasFamily: Boolean = true,
    val error: UiText? = null,
    val nowMs: Long = 0L,
    val camera: CameraRequest? = null,
) {
    /** "3 of 4 members sharing": members whose live location I can see, plus me when I share. */
    val sharingCount: Int get() = members.count { it.isSharingLive } + if (me.sharing == SharingState.ON) 1 else 0
    val totalCount: Int get() = members.size + 1

    companion object {
        const val ME = "me"
    }
}

/** Battery-use label on Home (SPEC battery targets: driving ≈1–2 %/h, otherwise ≤0.5 %/h). */
enum class BatteryUse { LOW, MODERATE }

fun batteryUseFor(activity: Activity?): BatteryUse = when (activity) {
    Activity.VEHICLE, Activity.CYCLING, Activity.RUNNING -> BatteryUse.MODERATE
    else -> BatteryUse.LOW
}

/** Pure mapping from server data to list rows (unit-tested). */
object MemberMapper {

    fun map(
        locations: List<MemberLocationDto>,
        theirGrants: Map<String, GrantLevel>,
        incidents: List<IncidentSummaryDto>,
        me: MyFix?,
        places: Map<String, String>,
        phones: Map<String, String>,
    ): List<MemberUi> = locations.map { dto ->
        val position = if (dto.hasPosition) GeoPoint(dto.lat!!, dto.lng!!) else null
        val visibility = when (dto.status) {
            LocationStatus.LIVE -> if (position != null) MemberVisibility.LIVE else MemberVisibility.LIVE_NO_FIX
            LocationStatus.PAUSED -> MemberVisibility.PAUSED
            LocationStatus.HIDDEN -> when (theirGrants[dto.userId]) {
                GrantLevel.EMERGENCY -> MemberVisibility.EMERGENCY_ONLY
                else -> MemberVisibility.NOT_SHARING
            }
        }
        MemberUi(
            userId = dto.userId,
            name = dto.displayName,
            initials = initialsOf(dto.displayName),
            color = if (visibility == MemberVisibility.LIVE || visibility == MemberVisibility.LIVE_NO_FIX) {
                MemberColors.forUser(dto.userId)
            } else {
                MemberColors.unavailable
            },
            visibility = visibility,
            activity = dto.activity.takeIf { position != null },
            speedKmh = com.eldering.safety.ui.common.Formats.speedKmh(dto.speedMps).takeIf { position != null },
            position = position,
            accuracyM = dto.accuracyM,
            recordedAtMs = dto.recordedAt?.toEpochMilli(),
            batteryPct = dto.batteryPct.takeIf { position != null },
            place = places[dto.userId],
            distanceM = if (position != null && me != null) Geo.distanceM(me.lat, me.lng, position.lat, position.lng) else null,
            activeIncidentId = incidents.firstOrNull { it.userId == dto.userId && !it.isMine }?.id,
            phone = phones[dto.displayName.trim().lowercase()],
        )
    }.sortedWith(compareBy<MemberUi>({ it.activeIncidentId == null }, { it.visibility.ordinal }, { it.name.lowercase() }))

    /**
     * The most generous thing a member shares with me across all families (`their_grant`). Only meaningful while
     * we are co-members, which the server guarantees for the members list.
     */
    fun strongestGrants(grantsPerFamily: List<Map<String, GrantLevel?>>): Map<String, GrantLevel> {
        val result = mutableMapOf<String, GrantLevel>()
        grantsPerFamily.forEach { family ->
            family.forEach { (userId, level) ->
                if (level != null) {
                    val current = result[userId]
                    if (current == null || level.ordinal < current.ordinal) result[userId] = level
                }
            }
        }
        return result
    }
}
