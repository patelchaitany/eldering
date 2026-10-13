package com.eldering.safety.ui.sharing

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.eldering.safety.core.TimeSource
import com.eldering.safety.data.db.EmergencyContact
import com.eldering.safety.data.repository.ContactsRepository
import com.eldering.safety.data.repository.FamilyRepository
import com.eldering.safety.data.repository.SharingRepository
import com.eldering.safety.network.ApiResult
import com.eldering.safety.network.FamilyRole
import com.eldering.safety.network.GrantDto
import com.eldering.safety.network.GrantLevel
import com.eldering.safety.network.SettingsDto
import com.eldering.safety.ui.common.UiText
import com.eldering.safety.ui.common.toUiText
import com.eldering.safety.ui.components.initialsOf
import com.eldering.safety.ui.theme.MemberColors
import java.time.Instant
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class PersonRole { ADMIN, MEMBER, EMERGENCY_CONTACT }

@Immutable
data class SharingPerson(
    /** A user id, or `contact:<id>` for an SMS-only emergency contact. */
    val key: String,
    val name: String,
    val initials: String,
    val color: Color,
    val role: PersonRole,
    /** What they may see of me. For emergency contacts: EMERGENCY (gets the SMS) or OFF. */
    val level: GrantLevel,
    val maskedPhone: String? = null,
    val saving: Boolean = false,
)

data class SharingUiState(
    val loading: Boolean = true,
    val settings: SettingsDto? = null,
    val people: List<SharingPerson> = emptyList(),
    val savingSettings: Boolean = false,
    val error: UiText? = null,
)

/**
 * "Sharing & privacy": my sharing switch, pause, always-alert-on-accident, and per-person levels. Only I can change
 * what I share (SPEC RBAC). Per-person changes are applied optimistically and rolled back if the server refuses.
 */
class SharingViewModel(
    private val sharing: SharingRepository,
    private val families: FamilyRepository,
    private val contacts: ContactsRepository,
    private val time: TimeSource = TimeSource.System,
) : ViewModel() {
    private val state = MutableStateFlow(SharingUiState())
    val uiState: StateFlow<SharingUiState> = state.asStateFlow()

    private var members: List<SharingPerson> = emptyList()
    private var contactRows: List<SharingPerson> = emptyList()
    private var contactList: List<EmergencyContact> = emptyList()

    init {
        viewModelScope.launch { sharing.settings.collect { s -> if (s != null) state.update { it.copy(settings = s) } } }
        viewModelScope.launch {
            contacts.contacts.collect { list ->
                contactList = list
                contactRows = list.map(::contactPerson)
                publish()
            }
        }
        load()
    }

    fun load() {
        state.update { it.copy(loading = it.people.isEmpty(), error = null) }
        viewModelScope.launch {
            val me = sharing.refresh()
            val grants = sharing.grants()
            val roles = loadRoles()
            when {
                grants is ApiResult.Success -> {
                    members = grants.value.map { personFor(it, roles[it.viewerId]) }
                        .sortedWith(compareBy({ it.role != PersonRole.ADMIN }, { it.name.lowercase() }))
                    publish()
                    state.update { it.copy(loading = false, error = (me as? ApiResult.Failure)?.error?.toUiText()) }
                }
                grants is ApiResult.Failure -> state.update { it.copy(loading = false, error = grants.error.toUiText()) }
            }
        }
    }

    /** The main switch: on → live sharing on; off → sharing off (also deletes my last location on the server). */
    fun setShareLive(on: Boolean) = updateSettings { if (on) sharing.turnSharingOn() else sharing.turnSharingOff() }

    fun pauseOneHour() = updateSettings { sharing.pauseSharing(Instant.ofEpochMilli(time.nowMillis() + ONE_HOUR_MS)) }

    fun pauseUntilResumed() = updateSettings { sharing.pauseSharing(null) }

    fun setAlwaysAlert(enabled: Boolean) = updateSettings { sharing.setAlwaysAlertOnAccident(enabled) }

    fun setLevel(key: String, level: GrantLevel) {
        if (key.startsWith(CONTACT_PREFIX)) {
            setContactLevel(key, level)
            return
        }
        val before = members.firstOrNull { it.key == key } ?: return
        if (before.level == level || before.saving) return
        members = members.map { if (it.key == key) it.copy(level = level, saving = true) else it }
        publish()
        viewModelScope.launch {
            val result = sharing.setGrant(key, level)
            members = members.map {
                when {
                    it.key != key -> it
                    result is ApiResult.Success -> it.copy(level = result.value.level, saving = false)
                    else -> it.copy(level = before.level, saving = false)
                }
            }
            publish()
            if (result is ApiResult.Failure) state.update { it.copy(error = result.error.toUiText()) }
        }
    }

    fun dismissError() = state.update { it.copy(error = null) }

    private fun setContactLevel(key: String, level: GrantLevel) {
        if (level == GrantLevel.LIVE) return // SMS-only contacts never get live location
        val id = key.removePrefix(CONTACT_PREFIX).toLongOrNull() ?: return
        val contact = contactList.firstOrNull { it.id == id } ?: return
        val smsEnabled = level == GrantLevel.EMERGENCY
        if (contact.smsEnabled == smsEnabled) return
        viewModelScope.launch {
            if (contacts.update(contact.copy(smsEnabled = smsEnabled)) !is ContactsRepository.SaveResult.Saved) {
                state.update { it.copy(error = UiText.res(com.eldering.safety.R.string.error_unexpected)) }
            }
        }
    }

    private fun updateSettings(call: suspend () -> ApiResult<SettingsDto>) {
        if (state.value.savingSettings) return
        state.update { it.copy(savingSettings = true, error = null) }
        viewModelScope.launch {
            val result = call()
            state.update {
                when (result) {
                    is ApiResult.Success -> it.copy(savingSettings = false, settings = result.value)
                    is ApiResult.Failure -> it.copy(savingSettings = false, error = result.error.toUiText())
                }
            }
        }
    }

    /** Admin if the person administers any family we share. */
    private suspend fun loadRoles(): Map<String, FamilyRole> {
        val familyList = when (val r = families.refresh()) {
            is ApiResult.Success -> r.value
            is ApiResult.Failure -> return emptyMap()
        }
        val roles = mutableMapOf<String, FamilyRole>()
        familyList.forEach { family ->
            val list = (families.members(family.id) as? ApiResult.Success)?.value ?: return@forEach
            list.forEach { m -> if (roles[m.userId] != FamilyRole.ADMIN) roles[m.userId] = m.role }
        }
        return roles
    }

    private fun publish() = state.update { it.copy(people = members + contactRows) }

    private fun personFor(grant: GrantDto, role: FamilyRole?): SharingPerson = SharingPerson(
        key = grant.viewerId,
        name = grant.displayName,
        initials = initialsOf(grant.displayName),
        color = MemberColors.forUser(grant.viewerId),
        role = if (role == FamilyRole.ADMIN) PersonRole.ADMIN else PersonRole.MEMBER,
        level = grant.level,
    )

    private fun contactPerson(contact: EmergencyContact) = SharingPerson(
        key = CONTACT_PREFIX + contact.id,
        name = contact.name,
        initials = initialsOf(contact.name),
        color = MemberColors.emergencyContact,
        role = PersonRole.EMERGENCY_CONTACT,
        level = if (contact.smsEnabled) GrantLevel.EMERGENCY else GrantLevel.OFF,
        maskedPhone = maskPhone(contact.phone),
    )

    companion object {
        const val CONTACT_PREFIX = "contact:"
        private const val ONE_HOUR_MS = 60 * 60_000L

        /** "+919800000210" → "+91 ••••• ••210" (design); short numbers keep only the last digits. */
        fun maskPhone(phone: String): String {
            val digits = phone.filter { it.isDigit() }
            val last = digits.takeLast(3)
            return when {
                phone.startsWith("+") && digits.length > 7 -> "+${digits.take(2)} ••••• ••$last"
                digits.length > 4 -> "••••• ••$last"
                else -> "••$last"
            }
        }
    }
}
