package com.eldering.safety.ui.familyalert

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.eldering.safety.core.TimeSource
import com.eldering.safety.data.repository.ContactsRepository
import com.eldering.safety.data.repository.IncidentRepository
import com.eldering.safety.data.repository.LocationRepository
import com.eldering.safety.network.AckState
import com.eldering.safety.network.ApiError
import com.eldering.safety.network.ApiResult
import com.eldering.safety.network.IncidentDetailDto
import com.eldering.safety.network.IncidentStatus
import com.eldering.safety.ui.common.UiText
import com.eldering.safety.ui.common.toUiText
import com.eldering.safety.ui.platform.PlaceNamer
import com.eldering.safety.ui.trip.TrailSample
import com.eldering.safety.ui.trip.TripViewModel
import com.eldering.safety.data.trail.Geo
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class FamilyAlertUiState(
    val loading: Boolean = true,
    val detail: IncidentDetailDto? = null,
    val trail: List<TrailSample> = emptyList(),
    /** Place name of the newest point (sender landmark nearby, or geocoded). */
    val place: String? = null,
    val batteryPct: Int? = null,
    /** On-device emergency contact with the person's name, to pre-fill "Call". */
    val phone: String? = null,
    val acting: Boolean = false,
    val error: UiText? = null,
    /** I tapped "Dad is safe" (handled) in this session. */
    val handledByMe: Boolean = false,
) {
    val latest: TrailSample? get() = trail.lastOrNull()
    val isActive: Boolean get() = detail?.status == IncidentStatus.ACTIVE
}

/**
 * A family member's accident as I (a viewer) see it. Refreshed every 15 s while visible; opening it tells the
 * person's phone I have seen the alert (ack `seen`, which never downgrades `on_my_way`).
 */
class FamilyAlertViewModel(
    private val incidentId: String,
    private val incidents: IncidentRepository,
    private val locations: LocationRepository,
    private val contacts: ContactsRepository,
    private val placeNamer: PlaceNamer,
    private val time: TimeSource = TimeSource.System,
    private val pollIntervalMs: Long = 15_000L,
) : ViewModel() {
    private val state = MutableStateFlow(FamilyAlertUiState())
    val uiState: StateFlow<FamilyAlertUiState> = state.asStateFlow()

    private var seenSent = false
    private var placeFor: TrailSample? = null

    suspend fun pollWhileVisible() {
        while (true) {
            refresh()
            delay(pollIntervalMs)
        }
    }

    suspend fun refresh() {
        when (val result = incidents.detail(incidentId)) {
            is ApiResult.Success -> {
                val detail = result.value
                val trail = TripViewModel.samplesOf(detail)
                val battery = locations.familyLocations.value.firstOrNull { it.userId == detail.userId }?.batteryPct
                val phone = contacts.all().firstOrNull { it.name.trim().equals(detail.displayName.trim(), ignoreCase = true) }?.phone
                state.update {
                    it.copy(loading = false, detail = detail, trail = trail, batteryPct = battery, phone = phone, error = null)
                }
                acknowledgeSeen(detail)
                namePlace(trail)
            }
            is ApiResult.Failure -> state.update { it.copy(loading = false, error = messageFor(result.error)) }
        }
    }

    /** "I'm going there". */
    fun onMyWay() = act {
        when (val r = incidents.acknowledge(incidentId, AckState.ON_MY_WAY)) {
            is ApiResult.Success -> state.update { s -> s.copy(detail = s.detail?.copy(myAck = AckState.ON_MY_WAY)) }
            is ApiResult.Failure -> state.update { it.copy(error = messageFor(r.error)) }
        }
    }

    /** "Dad is safe": closes the incident for everyone (`handled`). */
    fun markSafe() = act {
        when (val r = incidents.markHandled(incidentId)) {
            is ApiResult.Success -> state.update { s ->
                s.copy(handledByMe = true, detail = s.detail?.copy(status = r.value.status, resolution = r.value.resolution, resolvedAt = r.value.resolvedAt))
            }
            is ApiResult.Failure -> {
                // 409: it was already closed (e.g. the person said they're OK) — show the fresh state.
                if ((r.error as? ApiError.Http)?.isConflict == true) refresh() else state.update { it.copy(error = messageFor(r.error)) }
            }
        }
    }

    fun dismissError() = state.update { it.copy(error = null) }

    private fun act(block: suspend () -> Unit) {
        if (state.value.acting) return
        state.update { it.copy(acting = true) }
        viewModelScope.launch {
            try {
                block()
            } finally {
                state.update { it.copy(acting = false) }
            }
        }
    }

    private suspend fun acknowledgeSeen(detail: IncidentDetailDto) {
        if (seenSent || detail.isMine || detail.myAck != null || detail.status != IncidentStatus.ACTIVE) return
        seenSent = true
        if (incidents.acknowledge(incidentId, AckState.SEEN) is ApiResult.Success) {
            state.update { s -> s.copy(detail = s.detail?.copy(myAck = s.detail.myAck ?: AckState.SEEN)) }
        } else {
            seenSent = false // try again on the next refresh
        }
    }

    private fun namePlace(trail: List<TrailSample>) {
        val latest = trail.lastOrNull() ?: return
        val previous = placeFor
        if (previous != null && Geo.distanceM(previous.lat, previous.lng, latest.lat, latest.lng) < 50.0) return
        placeFor = latest
        // A landmark the sender named near the newest point is better than a fresh geocode.
        val named = trail.lastOrNull { it.landmark != null && Geo.distanceM(it.lat, it.lng, latest.lat, latest.lng) < 150.0 }?.landmark
        if (named != null) {
            state.update { it.copy(place = named) }
            return
        }
        viewModelScope.launch {
            placeNamer.nameFor(latest.lat, latest.lng)?.let { name -> state.update { it.copy(place = name) } }
        }
    }

    private fun messageFor(error: ApiError): UiText = error.toUiText()

    fun nowMs(): Long = time.nowMillis()
}
