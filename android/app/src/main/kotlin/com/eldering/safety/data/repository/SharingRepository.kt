package com.eldering.safety.data.repository

import com.eldering.safety.network.ApiResult
import com.eldering.safety.network.ElderingApi
import com.eldering.safety.network.GrantDto
import com.eldering.safety.network.GrantLevel
import com.eldering.safety.network.PauseEnd
import com.eldering.safety.network.SetGrantRequest
import com.eldering.safety.network.SettingsDto
import com.eldering.safety.network.SettingsPatch
import com.eldering.safety.network.SharingState
import com.eldering.safety.network.apiCall
import com.eldering.safety.network.map
import com.eldering.safety.network.onSuccess
import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * "Sharing & privacy": my global sharing switch (on / paused / off, always-alert-on-accident) and my per-viewer
 * grants (live / emergency only / off). Only I can change what I share (SPEC RBAC).
 */
class SharingRepository(
    private val api: ElderingApi,
    private val meStore: MeStore,
    /** Kept in step with every grants load and change (the accident countdown reads it offline). */
    private val recipients: AlertRecipientsRepository? = null,
) {
    /** From the cached `/v1/me`; null until loaded. */
    val settings: Flow<SettingsDto?> = meStore.me.map { it?.settings }.distinctUntilChanged()

    suspend fun refresh(): ApiResult<SettingsDto> = apiCall { api.me() }.onSuccess(meStore::set).map { it.settings }

    suspend fun updateSettings(patch: SettingsPatch): ApiResult<SettingsDto> {
        require(!patch.isEmpty) { "Empty settings patch" }
        return apiCall { api.updateSettings(patch.toJson()) }
            .onSuccess { settings ->
                meStore.update { it.copy(settings = settings) }
                recipients?.onSettingsChanged()
            }
    }

    suspend fun turnSharingOn(): ApiResult<SettingsDto> = updateSettings(SettingsPatch(sharingState = SharingState.ON))

    /** Off also deletes my latest location on the server. */
    suspend fun turnSharingOff(): ApiResult<SettingsDto> = updateSettings(SettingsPatch(sharingState = SharingState.OFF))

    /** Pauses live sharing until [until] (at most 30 days ahead), or until switched back on when null. */
    suspend fun pauseSharing(until: Instant?): ApiResult<SettingsDto> = updateSettings(
        SettingsPatch(sharingState = SharingState.PAUSED, pausedUntil = until?.let(PauseEnd::At) ?: PauseEnd.Indefinite),
    )

    /** Whether family is alerted about my accidents even while sharing is paused or off. */
    suspend fun setAlwaysAlertOnAccident(enabled: Boolean): ApiResult<SettingsDto> =
        updateSettings(SettingsPatch(alwaysAlertOnAccident = enabled))

    /** My outgoing grants to every co-member (implicit defaults included, `is_default = true`). */
    suspend fun grants(): ApiResult<List<GrantDto>> = apiCall { api.grants() }.onSuccess { recipients?.onGrantsLoaded(it) }

    /** Sets what [viewerId] (a co-member) may see of me. */
    suspend fun setGrant(viewerId: String, level: GrantLevel): ApiResult<GrantDto> =
        apiCall { api.setGrant(viewerId, SetGrantRequest(level)) }.alsoUpdate(recipients)
}
