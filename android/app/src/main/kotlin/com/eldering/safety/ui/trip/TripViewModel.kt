package com.eldering.safety.ui.trip

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.eldering.safety.core.TimeSource
import com.eldering.safety.data.db.OwnIncident
import com.eldering.safety.data.db.OwnIncidentStatus
import com.eldering.safety.data.repository.IncidentRepository
import com.eldering.safety.data.trail.TrailStore
import com.eldering.safety.network.Activity
import com.eldering.safety.network.ApiError
import com.eldering.safety.network.ApiJson
import com.eldering.safety.network.ApiResult
import com.eldering.safety.network.IncidentDetailDto
import com.eldering.safety.network.IncidentKind
import com.eldering.safety.network.IncidentStatus
import com.eldering.safety.ui.common.IncidentDetailsReader
import com.eldering.safety.ui.common.UiText
import com.eldering.safety.ui.common.toUiText
import com.eldering.safety.ui.map.GeoPoint
import com.eldering.safety.ui.platform.PlaceNamer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive

/** Why there is nothing to replay. */
enum class TripEmpty {
    /** My own trail is empty (protection was off, or no fixes yet). */
    NO_LOCAL_TRAIL,

    /** A family member without an accident/SOS: their trail never leaves their phone. */
    NOT_SHARED,
}

data class TripUiState(
    val loading: Boolean = true,
    /** Null = me. */
    val personName: String? = null,
    val points: List<TrailSample> = emptyList(),
    val summary: TripSummary? = null,
    /** Geocoded names for landmark indices without a sender-provided name. */
    val placeNames: Map<Int, String> = emptyMap(),
    val cursor: Int = 0,
    val empty: TripEmpty? = null,
    val error: UiText? = null,
    /** Kind of the incident whose trail this is (null for my own last hour). */
    val incidentKind: IncidentKind? = null,
    /** My own open incident's public link, if this is my trail and one exists (for "Share trail"). */
    val shareUrl: String? = null,
) {
    val cursorPoint: TrailSample? get() = points.getOrNull(cursor)

    fun landmarkTitle(landmark: Landmark): String? = landmark.name ?: placeNames[landmark.index]
}

/**
 * "Trip to past". My own last hour comes from the on-device ring buffer ([TrailStore]); a family member's comes
 * only from an incident they raised (the server keeps no location history otherwise, by design).
 */
class TripViewModel(
    private val userId: String?,
    private val incidentId: String?,
    private val name: String?,
    private val trailStore: TrailStore,
    private val incidents: IncidentRepository,
    private val placeNamer: PlaceNamer,
    private val time: TimeSource = TimeSource.System,
) : ViewModel() {
    private val state = MutableStateFlow(TripUiState(personName = name))
    val uiState: StateFlow<TripUiState> = state.asStateFlow()

    init {
        load()
    }

    fun load() {
        state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            if (userId == null) loadMine() else loadMember(userId)
        }
    }

    /** Scrubber / pin / row selection. */
    fun moveCursor(index: Int) = state.update { s ->
        if (s.points.isEmpty()) s else s.copy(cursor = index.coerceIn(0, s.points.lastIndex))
    }

    private suspend fun loadMine() {
        val points = trailStore.recent().map {
            TrailSample(it.lat, it.lng, it.recordedAtMs, it.speedMps, it.accuracyM, it.activity)
        }
        // My own accident/SOS in this hour gets the same "!" stop as the family's view of it.
        val incident = ownIncidentWithin(points)
        val kind = incident?.let { parseKind(it.kind) }
        if (kind != null) state.update { it.copy(incidentKind = kind) }
        show(points, incidentAtMs = incident?.detectedAtMs?.takeIf { kind != null }, shareUrl = openShareUrl())
    }

    /** The newest of my incidents detected while [points] were recorded (a minute of slack either side). */
    private suspend fun ownIncidentWithin(points: List<TrailSample>): OwnIncident? {
        val from = points.firstOrNull()?.tMs ?: return null
        val to = points.last().tMs
        return incidents.observeOwnIncidents().first()
            .filter { it.detectedAtMs in (from - INCIDENT_SLACK_MS)..(to + INCIDENT_SLACK_MS) }
            .maxByOrNull { it.detectedAtMs }
    }

    private suspend fun loadMember(userId: String) {
        val id = incidentId ?: when (val active = incidents.active()) {
            is ApiResult.Success -> active.value.firstOrNull { it.userId == userId && it.status == IncidentStatus.ACTIVE }?.id
            is ApiResult.Failure -> return fail(active.error)
        }
        if (id == null) {
            state.update { it.copy(loading = false, empty = TripEmpty.NOT_SHARED) }
            return
        }
        when (val detail = incidents.detail(id)) {
            is ApiResult.Success -> {
                state.update { it.copy(personName = name ?: detail.value.displayName, incidentKind = detail.value.kind) }
                // Server trails carry no activity; the detector's mode fills the subtitle ("… · Driving · 18 km").
                show(
                    samplesOf(detail.value),
                    incidentAtMs = detail.value.detectedAt.toEpochMilli(),
                    shareUrl = null,
                    fallbackActivity = IncidentDetailsReader.mode(detail.value.details)?.takeIf { it != Activity.UNKNOWN },
                )
            }
            is ApiResult.Failure -> fail(detail.error)
        }
    }

    private fun fail(error: ApiError) = state.update { it.copy(loading = false, error = error.toUiText()) }

    private fun show(points: List<TrailSample>, incidentAtMs: Long?, shareUrl: String?, fallbackActivity: Activity? = null) {
        if (points.isEmpty()) {
            state.update { it.copy(loading = false, empty = if (userId == null) TripEmpty.NO_LOCAL_TRAIL else TripEmpty.NOT_SHARED) }
            return
        }
        val summary = TrailSummarizer.summarize(points, incidentAtMs)
            ?.let { if (it.dominantActivity == null && fallbackActivity != null) it.copy(dominantActivity = fallbackActivity) else it }
        val cursor = summary?.incidentIndex ?: points.lastIndex
        state.update {
            it.copy(loading = false, points = points, summary = summary, cursor = cursor, empty = null, shareUrl = shareUrl)
        }
        nameLandmarks(points, summary)
    }

    private fun nameLandmarks(points: List<TrailSample>, summary: TripSummary?) {
        val unnamed = summary?.landmarks?.filter { it.name == null }.orEmpty()
        if (unnamed.isEmpty()) return
        viewModelScope.launch {
            unnamed.forEach { landmark ->
                val p = points[landmark.index]
                val place = placeNamer.nameFor(p.lat, p.lng) ?: return@forEach
                state.update { it.copy(placeNames = it.placeNames + (landmark.index to place)) }
            }
        }
    }

    /** Only a still-open incident's link is worth sharing (it expires 72 h after creation). */
    private suspend fun openShareUrl(): String? {
        val now = time.nowMillis()
        return incidents.observeOwnIncidents().first()
            .firstOrNull { it.status == OwnIncidentStatus.ACTIVE && it.shareUrl != null && (it.shareExpiresAtMs ?: 0L) > now }
            ?.shareUrl
    }

    companion object {
        private const val INCIDENT_SLACK_MS = 60_000L

        /** [OwnIncident.kind] is the wire name ("fall", "vehicle_crash", …). */
        fun parseKind(wire: String): IncidentKind? = try {
            ApiJson.decodeFromJsonElement(IncidentKind.serializer(), JsonPrimitive(wire))
        } catch (e: IllegalArgumentException) { // SerializationException extends it
            null
        }

        /** Server trail (oldest first) plus the latest live point if it is newer than the trail. */
        fun samplesOf(detail: IncidentDetailDto): List<TrailSample> {
            val trail = detail.trail.map { TrailSample(it.lat, it.lng, it.recordedAt.toEpochMilli(), accuracyM = it.accuracyM, landmark = it.landmark) }
            val latest = detail.lastLocation
            val withLatest = if (latest != null && (trail.isEmpty() || latest.recordedAt.toEpochMilli() > trail.last().tMs)) {
                trail + TrailSample(latest.lat, latest.lng, latest.recordedAt.toEpochMilli(), accuracyM = latest.accuracyM)
            } else {
                trail
            }
            if (withLatest.isNotEmpty() || detail.lat == null || detail.lng == null) return withLatest
            return listOf(TrailSample(detail.lat, detail.lng, detail.detectedAt.toEpochMilli()))
        }

        fun toGeo(sample: TrailSample) = GeoPoint(sample.lat, sample.lng)
    }
}
