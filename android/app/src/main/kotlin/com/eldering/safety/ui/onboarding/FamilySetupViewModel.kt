package com.eldering.safety.ui.onboarding

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.eldering.safety.R
import com.eldering.safety.data.repository.FamilyRepository
import com.eldering.safety.data.settings.AppPreferences
import com.eldering.safety.network.ApiError
import com.eldering.safety.network.ApiResult
import com.eldering.safety.ui.common.UiText
import com.eldering.safety.ui.common.toUiText
import com.eldering.safety.ui.navigation.Route
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class FamilySetupUiState(
    val familyName: String = "",
    val code: String = "",
    @StringRes val nameError: Int? = null,
    @StringRes val codeError: Int? = null,
    val creating: Boolean = false,
    val joining: Boolean = false,
    val error: UiText? = null,
) {
    val busy: Boolean get() = creating || joining
}

/** Onboarding step 2: create a family (I become admin and get an invite code) or join one with a code. */
class FamilySetupViewModel(
    private val families: FamilyRepository,
    private val preferences: AppPreferences,
) : ViewModel() {
    private val state = MutableStateFlow(FamilySetupUiState())
    val uiState: StateFlow<FamilySetupUiState> = state.asStateFlow()

    private val next = Channel<Route>(Channel.BUFFERED)

    /** Where to go once the family is set up. */
    val navigation: Flow<Route> = next.receiveAsFlow()

    fun onFamilyName(value: String) = state.update { it.copy(familyName = value.take(90), nameError = null, error = null) }

    /** Keeps what people type readable ("abcd 1234"); normalization happens on submit. */
    fun onCode(value: String) = state.update {
        it.copy(code = value.uppercase().filter { c -> c.isLetterOrDigit() || c == ' ' || c == '-' }.take(11), codeError = null, error = null)
    }

    fun create() {
        val name = state.value.familyName.trim()
        if (state.value.busy) return
        val nameError = when {
            name.isEmpty() -> R.string.family_error_name_blank
            name.length > 80 -> R.string.family_error_name_long
            else -> null
        }
        if (nameError != null) {
            state.update { it.copy(nameError = nameError) }
            return
        }
        state.update { it.copy(creating = true, error = null) }
        viewModelScope.launch {
            when (val result = families.create(name)) {
                is ApiResult.Success -> {
                    state.update { it.copy(creating = false) }
                    val invite = result.value.invite
                    next.send(Route.Invite(result.value.id, invite.code, invite.expiresAt.toEpochMilli(), onboarding = true))
                }
                is ApiResult.Failure -> state.update { it.copy(creating = false, error = result.error.toUiText()) }
            }
        }
    }

    fun join() {
        if (state.value.busy) return
        val code = FamilyRepository.normalizeInviteCode(state.value.code)
        if (code.length != INVITE_CODE_LENGTH) {
            state.update { it.copy(codeError = R.string.family_error_code_format) }
            return
        }
        state.update { it.copy(joining = true, error = null) }
        viewModelScope.launch {
            when (val result = families.join(code)) {
                is ApiResult.Success -> {
                    state.update { it.copy(joining = false) }
                    val done = preferences.permissionsWalkthroughDone.first()
                    next.send(if (done) Route.Home else Route.Permissions(onboarding = true))
                }
                is ApiResult.Failure -> {
                    val http = result.error as? ApiError.Http
                    state.update {
                        if (http?.isNotFound == true || http?.code == "invite_invalid") {
                            it.copy(joining = false, codeError = R.string.family_error_code_invalid)
                        } else {
                            it.copy(joining = false, error = result.error.toUiText())
                        }
                    }
                }
            }
        }
    }

    companion object {
        const val INVITE_CODE_LENGTH = 8
    }
}
