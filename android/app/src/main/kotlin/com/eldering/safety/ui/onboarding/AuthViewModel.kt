package com.eldering.safety.ui.onboarding

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.eldering.safety.R
import com.eldering.safety.data.repository.AuthRepository
import com.eldering.safety.data.settings.AppPreferences
import com.eldering.safety.network.ApiError
import com.eldering.safety.network.ApiResult
import com.eldering.safety.ui.common.UiText
import com.eldering.safety.ui.common.toUiText
import com.eldering.safety.ui.navigation.StartDestination
import com.eldering.safety.ui.navigation.StartViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class AuthUiState(
    val register: Boolean,
    val name: String = "",
    val email: String = "",
    val password: String = "",
    @StringRes val nameError: Int? = null,
    @StringRes val emailError: Int? = null,
    @StringRes val passwordError: Int? = null,
    val submitting: Boolean = false,
    val error: UiText? = null,
)

/** Register / log in. Credentials only ever go to [AuthRepository]; nothing is logged or kept in memory after. */
class AuthViewModel(
    register: Boolean,
    private val auth: AuthRepository,
    private val preferences: AppPreferences,
) : ViewModel() {
    private val state = MutableStateFlow(AuthUiState(register = register))
    val uiState: StateFlow<AuthUiState> = state.asStateFlow()

    private val done = Channel<StartDestination>(Channel.BUFFERED)

    /** Where to go after a successful sign-in. */
    val signedIn: Flow<StartDestination> = done.receiveAsFlow()

    fun setMode(register: Boolean) = state.update {
        it.copy(register = register, error = null, nameError = null, emailError = null, passwordError = null)
    }

    fun onName(value: String) = state.update { it.copy(name = value.take(MAX_NAME + 10), nameError = null) }

    fun onEmail(value: String) = state.update { it.copy(email = value.trim().take(254), emailError = null, error = null) }

    fun onPassword(value: String) = state.update { it.copy(password = value.take(MAX_PASSWORD), passwordError = null, error = null) }

    fun submit() {
        val s = state.value
        if (s.submitting) return
        val nameError = if (s.register) validateName(s.name) else null
        val emailError = validateEmail(s.email)
        val passwordError = validatePassword(s.password, strict = s.register)
        if (nameError != null || emailError != null || passwordError != null) {
            state.update { it.copy(nameError = nameError, emailError = emailError, passwordError = passwordError) }
            return
        }
        state.update { it.copy(submitting = true, error = null) }
        viewModelScope.launch {
            val result = if (s.register) auth.register(s.email, s.password, s.name) else auth.login(s.email, s.password)
            when (result) {
                is ApiResult.Success -> {
                    val next = StartViewModel.resolve(auth, preferences)
                    state.update { it.copy(submitting = false, password = "") }
                    done.send(next)
                }
                is ApiResult.Failure -> state.update { applyError(it.copy(submitting = false), result.error) }
            }
        }
    }

    private fun applyError(s: AuthUiState, error: ApiError): AuthUiState {
        val http = error as? ApiError.Http
        return when (http?.code) {
            "email_taken" -> s.copy(emailError = R.string.auth_error_email_taken)
            "invalid_email" -> s.copy(emailError = R.string.auth_error_email)
            "invalid_password" -> s.copy(passwordError = R.string.auth_error_password_length)
            "invalid_display_name" -> s.copy(nameError = R.string.auth_error_name)
            "invalid_credentials" -> s.copy(error = UiText.res(R.string.auth_error_credentials))
            else -> s.copy(error = error.toUiText())
        }
    }

    companion object {
        const val MIN_PASSWORD = 8
        const val MAX_PASSWORD = 128
        const val MAX_NAME = 80

        @StringRes
        fun validateName(name: String): Int? = when {
            name.isBlank() -> R.string.auth_error_name_blank
            name.trim().length > MAX_NAME -> R.string.auth_error_name
            else -> null
        }

        /** Light client-side check; the server is authoritative. */
        @StringRes
        fun validateEmail(email: String): Int? {
            val at = email.indexOf('@')
            return if (email.length in 3..254 && at > 0 && at < email.length - 1 && email.count { it == '@' } == 1 && ' ' !in email) {
                null
            } else {
                R.string.auth_error_email
            }
        }

        @StringRes
        fun validatePassword(password: String, strict: Boolean): Int? = when {
            password.isEmpty() -> R.string.auth_error_password_blank
            strict && password.length !in MIN_PASSWORD..MAX_PASSWORD -> R.string.auth_error_password_length
            else -> null
        }
    }
}
