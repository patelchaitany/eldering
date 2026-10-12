package com.eldering.safety.data.repository

import com.eldering.safety.core.TimeSource
import com.eldering.safety.data.trail.TrailPoint
import com.eldering.safety.network.ApiResult
import com.eldering.safety.network.ElderingApi
import com.eldering.safety.network.HeartbeatRequest
import com.eldering.safety.network.HeartbeatResponse
import com.eldering.safety.network.IncidentSummaryDto
import com.eldering.safety.network.MemberLocationDto
import com.eldering.safety.network.apiCall
import com.eldering.safety.network.onSuccess
import java.time.Instant
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Live location: my heartbeat (the server keeps only my latest point) and the family map.
 *
 * The heartbeat response also carries alerts I can see and have not acknowledged ([pendingAlerts]), so a viewer
 * learns about a family member's accident on their next heartbeat even without push.
 */
class LocationRepository(
    private val api: ElderingApi,
    private val time: TimeSource = TimeSource.System,
) {
    private val familyLocationsState = MutableStateFlow<List<MemberLocationDto>>(emptyList())
    private val pendingAlertsState = MutableStateFlow<List<IncidentSummaryDto>>(emptyList())

    /** Last fetched co-member locations (coordinates only for members whose live location I may see). */
    val familyLocations: StateFlow<List<MemberLocationDto>> = familyLocationsState.asStateFlow()

    /** Unacknowledged family incidents from the last heartbeat. */
    val pendingAlerts: StateFlow<List<IncidentSummaryDto>> = pendingAlertsState.asStateFlow()

    /**
     * Server clock minus device clock in ms, estimated from the last heartbeat (null until one succeeded).
     * The server rejects timestamps more than 5 min in its future, so a large value means the phone clock is off.
     */
    @Volatile
    var serverClockOffsetMs: Long? = null
        private set

    /**
     * Sends my latest fix. Callers decide *whether* to send (SPEC: only while sharing is on; 60 s walking, 30 s
     * vehicle; none while still and unchanged).
     */
    suspend fun sendHeartbeat(point: TrailPoint, batteryPct: Int? = null): ApiResult<HeartbeatResponse> {
        val request = heartbeatRequest(point, batteryPct)
        val sentAt = time.nowMillis()
        return apiCall { api.heartbeat(request) }.onSuccess { response ->
            pendingAlertsState.value = response.pendingAlerts
            val midpoint = sentAt + (time.nowMillis() - sentAt) / 2
            serverClockOffsetMs = response.serverTime.toEpochMilli() - midpoint
        }
    }

    suspend fun refreshFamilyLocations(): ApiResult<List<MemberLocationDto>> =
        apiCall { api.familyLocations() }.onSuccess { familyLocationsState.value = it }

    /** Forgets cached data (sign-out). */
    fun clear() {
        familyLocationsState.value = emptyList()
        pendingAlertsState.value = emptyList()
        serverClockOffsetMs = null
    }

    companion object {
        /** Maps a fix to the heartbeat body, dropping values the server would reject (NaN, out of range). */
        fun heartbeatRequest(point: TrailPoint, batteryPct: Int?): HeartbeatRequest = HeartbeatRequest(
            lat = point.lat,
            lng = point.lng,
            accuracyM = point.accuracyM?.takeIf { it.isFinite() && it in 0f..100_000f },
            speedMps = point.speedMps?.takeIf { it.isFinite() && it in 0f..200f },
            bearingDeg = point.bearingDeg?.takeIf { it.isFinite() }?.let { ((it % 360f) + 360f) % 360f },
            activity = point.activity,
            batteryPct = batteryPct?.coerceIn(0, 100),
            recordedAt = Instant.ofEpochMilli(point.recordedAtMs),
        )
    }
}
