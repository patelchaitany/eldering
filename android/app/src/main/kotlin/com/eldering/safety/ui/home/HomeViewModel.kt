package com.eldering.safety.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.eldering.safety.core.TimeSource
import com.eldering.safety.data.db.EmergencyContact
import com.eldering.safety.data.db.OwnIncidentStatus
import com.eldering.safety.data.repository.AuthRepository
import com.eldering.safety.data.repository.ContactsRepository
import com.eldering.safety.data.repository.FamilyPhoneBook
import com.eldering.safety.data.repository.FamilyRepository
import com.eldering.safety.data.repository.IncidentRepository
import com.eldering.safety.data.repository.LocationRepository
import com.eldering.safety.network.ApiError
import com.eldering.safety.network.ApiResult
import com.eldering.safety.network.GrantLevel
import com.eldering.safety.network.IncidentStatus
import com.eldering.safety.network.IncidentSummaryDto
import com.eldering.safety.network.MemberLocationDto
import com.eldering.safety.network.Resolution
import com.eldering.safety.ui.common.Formats
import com.eldering.safety.ui.common.toUiText
import com.eldering.safety.ui.map.CameraRequest
import com.eldering.safety.ui.map.CameraTarget
import com.eldering.safety.ui.map.GeoPoint
import com.eldering.safety.ui.platform.BatteryLevelSource
import com.eldering.safety.ui.platform.MyFix
import com.eldering.safety.ui.platform.MyLocationSource
import com.eldering.safety.ui.platform.PlaceNamer
import com.eldering.safety.ui.platform.ProtectionStatusSource
import java.util.Locale
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Home / family map. Data is refreshed by [pollWhileVisible], which the screen runs only while it is at least
 * STARTED (lifecycle-aware): `/v1/family/locations` and active incidents every [pollIntervalMs] (15 s), my profile
 * and the members' grants every [fullRefreshEvery] rounds. Nothing polls while the app is in the background.
 */
class HomeViewModel(
    private val auth: AuthRepository,
    private val families: FamilyRepository,
    private val locations: LocationRepository,
    private val incidents: IncidentRepository,
    private val contacts: ContactsRepository,
    private val myLocation: MyLocationSource,
    private val protection: ProtectionStatusSource,
    private val battery: BatteryLevelSource,
    private val placeNamer: PlaceNamer,
    private val time: TimeSource = TimeSource.System,
    private val pollIntervalMs: Long = POLL_INTERVAL_MS,
    private val fullRefreshEvery: Int = FULL_REFRESH_EVERY,
    /** "I'm safe now" on my open incident (through the alert pipeline when it still runs it). */
    private val resolveOwn: suspend (incidentKey: String) -> Unit = { key -> incidents.resolveOwn(key, Resolution.USER_OK) },
    private val phoneBook: FamilyPhoneBook? = null,
    /** Keeps the countdown's "To Mom, Dad (app)" list fresh while the app is open. */
    private val refreshRecipients: suspend () -> Unit = {},
) : ViewModel() {

    private val state = MutableStateFlow(HomeUiState(nowMs = time.nowMillis()))
    val uiState: StateFlow<HomeUiState> = state.asStateFlow()

    private var lastLocations: List<MemberLocationDto> = emptyList()
    private var lastIncidents: List<IncidentSummaryDto> = emptyList()
    private var theirGrants: Map<String, GrantLevel> = emptyMap()
    private var myFix: MyFix? = null
    private var contactList: List<EmergencyContact> = emptyList()
    private val places = mutableMapOf<String, String>()
    private val placeCells = mutableMapOf<String, String>()
    private var round = 0
    private var cameraSeq = 0
    /** Who was on the map when the camera last fitted everyone (me = [HomeUiState.ME]). */
    private var fittedIds: Set<String>? = null

    init {
        viewModelScope.launch {
            incidents.observeOwnIncidents().collect { list ->
                val open = list.firstOrNull { it.status == OwnIncidentStatus.QUEUED || it.status == OwnIncidentStatus.ACTIVE }
                state.update { it.copy(ownIncident = open) }
            }
        }
        viewModelScope.launch {
            contacts.contacts.collect { list ->
                contactList = list
                rebuild()
            }
        }
        viewModelScope.launch {
            auth.me.collect { me ->
                if (me != null) {
                    state.update {
                        it.copy(
                            me = it.me.copy(
                                name = me.user.displayName,
                                sharing = me.settings.effectiveSharingState,
                                pausedUntilMs = me.settings.pausedUntil?.toEpochMilli(),
                            ),
                            hasFamily = me.families.isNotEmpty(),
                        )
                    }
                }
            }
        }
    }

    /** Runs until cancelled; call it from `repeatOnLifecycle(STARTED)`. */
    suspend fun pollWhileVisible() {
        while (true) {
            refresh(full = round % fullRefreshEvery == 0)
            round++
            delay(pollIntervalMs)
        }
    }

    /** One refresh; [full] also reloads my profile and everyone's grants. */
    suspend fun refresh(full: Boolean) = coroutineScope {
        val locationsCall = async { locations.refreshFamilyLocations() }
        val incidentsCall = async { incidents.active() }
        val meCall = if (full) async { auth.refreshMe() } else null
        val fix = async { myLocation.lastKnown() }
        val status = async { protection.current() }

        var error: ApiError? = null
        when (val r = locationsCall.await()) {
            is ApiResult.Success -> lastLocations = r.value
            is ApiResult.Failure -> error = r.error
        }
        when (val r = incidentsCall.await()) {
            is ApiResult.Success -> lastIncidents = r.value.filter { it.status == IncidentStatus.ACTIVE }
            is ApiResult.Failure -> error = error ?: r.error
        }
        meCall?.await()?.let { r ->
            if (r is ApiResult.Success) {
                theirGrants = loadTheirGrants(r.value.families.map { it.id })
                refreshRecipients()
            }
        }
        myFix = fix.await()
        val protectionStatus = status.await()
        state.update {
            it.copy(
                loading = false,
                protection = protectionStatus,
                me = it.me.copy(
                    position = myFix?.let { f -> GeoPoint(f.lat, f.lng) },
                    recordedAtMs = myFix?.recordedAtMs,
                    activity = protectionStatus.activity ?: myFix?.activity,
                    speedKmh = Formats.speedKmh(myFix?.speedMps),
                    batteryPct = battery.percent(),
                    place = places[HomeUiState.ME],
                ),
                error = error?.takeUnless { e -> e is ApiError.SignedOut }?.toUiText(),
                nowMs = time.nowMillis(),
            )
        }
        rebuild()
        fitCameraToEveryone()
        nameNewPlaces()
    }

    fun select(id: String) = state.update { it.copy(selectedId = if (it.selectedId == id) null else id) }

    /** "Center on me": my position, or everyone if mine is unknown. */
    fun centerOnMe() {
        val me = state.value.me.position
        val target = if (me != null) CameraTarget.Center(me, zoom = 15.0) else CameraTarget.Fit(allPositions())
        state.update { it.copy(camera = CameraRequest(target, id = ++cameraSeq)) }
    }

    /** Shows a member on the map and selects them (marker tap or list tap). */
    fun focus(id: String) {
        select(id)
        val position = if (id == HomeUiState.ME) state.value.me.position else state.value.members.firstOrNull { it.userId == id }?.position
        if (position != null && state.value.selectedId == id) {
            state.update { it.copy(camera = CameraRequest(CameraTarget.Center(position, zoom = 15.0), id = ++cameraSeq)) }
        }
    }

    /** "I'm safe now" on my own open SOS/accident (the screen asked for confirmation). */
    fun resolveOwnIncident() {
        val key = state.value.ownIncident?.incidentKey ?: return
        viewModelScope.launch { resolveOwn(key) }
    }

    /** "Call" had no number for [userId]: remember the one picked from the address book. */
    fun rememberPhone(userId: String, phone: String) {
        phoneBook?.remember(userId, phone)
        rebuild()
    }

    /** "Retry" on the error banner: refresh everything now instead of waiting for the next poll. */
    fun retry() {
        state.update { it.copy(error = null) }
        viewModelScope.launch { refresh(full = true) }
    }

    private suspend fun loadTheirGrants(familyIds: List<String>): Map<String, GrantLevel> {
        val perFamily = familyIds.map { id ->
            when (val r = families.members(id)) {
                is ApiResult.Success -> r.value.filterNot { it.isMe }.associate { it.userId to it.theirGrant }
                is ApiResult.Failure -> return theirGrants // keep what we had
            }
        }
        return MemberMapper.strongestGrants(perFamily)
    }

    private fun rebuild() {
        val phones = lastLocations.mapNotNull { m ->
            (phoneBook?.phoneFor(m.userId, m.displayName, contactList)
                ?: contactList.firstOrNull { it.name.trim().equals(m.displayName.trim(), ignoreCase = true) }?.phone)
                ?.let { m.userId to it }
        }.toMap()
        val members = MemberMapper.map(lastLocations, theirGrants, lastIncidents, myFix, places, phones)
        state.update { s ->
            s.copy(
                members = members,
                familyAlerts = lastIncidents.filterNot { it.isMine }.sortedByDescending { it.detectedAt },
                selectedId = s.selectedId?.takeIf { id -> id == HomeUiState.ME || members.any { it.userId == id } },
            )
        }
    }

    private fun allPositions(): List<GeoPoint> =
        listOfNotNull(state.value.me.position) + state.value.members.mapNotNull { it.position }

    /**
     * Fits everyone on the map: once at the start, and again whenever somebody who was not on the map appears (a
     * member switched to Live, or their first fix arrived), so nobody stays off-screen until the next app start.
     * Members moving or disappearing do not move the camera (the user may be looking at something).
     */
    private fun fitCameraToEveryone() {
        val visible = buildSet {
            if (state.value.me.position != null) add(HomeUiState.ME)
            state.value.members.forEach { m -> if (m.position != null) add(m.userId) }
        }
        val previous = fittedIds
        val decision = MapFit.decide(previous, visible)
        fittedIds = decision.fitted
        if (!decision.refit) return
        state.update {
            it.copy(camera = CameraRequest(CameraTarget.Fit(allPositions()), id = ++cameraSeq, animate = previous != null))
        }
    }

    /** Geocodes positions that moved to a new ~100 m cell; results arrive in the background. */
    private fun nameNewPlaces() {
        val targets = buildList {
            state.value.me.position?.let { add(HomeUiState.ME to it) }
            state.value.members.forEach { m -> m.position?.let { add(m.userId to it) } }
        }
        targets.forEach { (id, point) ->
            val cell = String.format(Locale.ROOT, "%.3f,%.3f", point.lat, point.lng)
            if (placeCells[id] == cell) return@forEach
            placeCells[id] = cell
            viewModelScope.launch {
                val name = placeNamer.nameFor(point.lat, point.lng) ?: return@launch
                places[id] = name
                if (id == HomeUiState.ME) state.update { it.copy(me = it.me.copy(place = name)) } else rebuild()
            }
        }
    }

    companion object {
        const val POLL_INTERVAL_MS = 15_000L
        const val FULL_REFRESH_EVERY = 8 // ≈ every 2 minutes
    }
}
