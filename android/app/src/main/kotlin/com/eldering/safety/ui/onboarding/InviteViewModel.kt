package com.eldering.safety.ui.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.eldering.safety.data.repository.FamilyRepository
import com.eldering.safety.network.ApiResult
import com.eldering.safety.network.FamilyDto
import com.eldering.safety.network.FamilyRole
import com.eldering.safety.ui.common.UiText
import com.eldering.safety.ui.common.toUiText
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class InviteUiState(
    val loading: Boolean = true,
    val familyName: String? = null,
    val isAdmin: Boolean = false,
    val code: String? = null,
    /** 0 = unknown. */
    val expiresAtMs: Long = 0L,
    /** Who to ask for a code when I am not an admin. */
    val adminNames: List<String> = emptyList(),
    val creating: Boolean = false,
    val error: UiText? = null,
)

/**
 * Shows an invite code. Admins get one (the one just created with the family, or a fresh one); other members
 * are told which admin to ask (SPEC: only admins create invites).
 */
class InviteViewModel(
    private val requestedFamilyId: String?,
    initialCode: String?,
    initialExpiresAtMs: Long,
    private val families: FamilyRepository,
) : ViewModel() {
    private val state = MutableStateFlow(
        InviteUiState(loading = true, code = initialCode, expiresAtMs = initialExpiresAtMs),
    )
    val uiState: StateFlow<InviteUiState> = state.asStateFlow()

    private var familyId: String? = requestedFamilyId

    init {
        viewModelScope.launch { load(createIfMissing = initialCode == null) }
    }

    fun retry() {
        viewModelScope.launch { load(createIfMissing = state.value.code == null) }
    }

    /** Admin: replace the code with a fresh one (48 h). */
    fun newCode() {
        val id = familyId ?: return
        if (state.value.creating) return
        state.update { it.copy(creating = true, error = null) }
        viewModelScope.launch { createCode(id) }
    }

    private suspend fun load(createIfMissing: Boolean) {
        state.update { it.copy(loading = true, error = null) }
        val cached = families.families.first()
        val list = if (cached.isNotEmpty()) cached else when (val r = families.refresh()) {
            is ApiResult.Success -> r.value
            is ApiResult.Failure -> {
                state.update { it.copy(loading = false, error = r.error.toUiText()) }
                return
            }
        }
        val family = pickFamily(list, requestedFamilyId)
        if (family == null) {
            state.update { it.copy(loading = false) }
            return
        }
        familyId = family.id
        val isAdmin = family.role == FamilyRole.ADMIN
        state.update { it.copy(familyName = family.name, isAdmin = isAdmin) }
        if (isAdmin) {
            if (createIfMissing) createCode(family.id) else state.update { it.copy(loading = false) }
        } else {
            val admins = (families.members(family.id) as? ApiResult.Success)?.value
                ?.filter { it.role == FamilyRole.ADMIN && !it.isMe }
                ?.map { it.displayName }
                .orEmpty()
            state.update { it.copy(loading = false, adminNames = admins) }
        }
    }

    private suspend fun createCode(id: String) {
        when (val result = families.createInvite(id)) {
            is ApiResult.Success -> state.update {
                it.copy(loading = false, creating = false, code = result.value.code, expiresAtMs = result.value.expiresAt.toEpochMilli())
            }
            is ApiResult.Failure -> state.update { it.copy(loading = false, creating = false, error = result.error.toUiText()) }
        }
    }

    companion object {
        /** The requested family, else the first one I administer, else the first one. */
        fun pickFamily(families: List<FamilyDto>, requestedId: String?): FamilyDto? =
            families.firstOrNull { it.id == requestedId }
                ?: families.firstOrNull { it.role == FamilyRole.ADMIN }
                ?: families.firstOrNull()

        /** "ABCD1234" → "ABCD 1234" for reading aloud and typing. */
        fun displayCode(code: String): String = if (code.length == 8) code.substring(0, 4) + " " + code.substring(4) else code
    }
}
