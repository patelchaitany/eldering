package com.eldering.safety.ui.navigation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.eldering.safety.data.repository.AuthRepository
import com.eldering.safety.data.settings.AppPreferences
import com.eldering.safety.network.ApiError
import com.eldering.safety.network.ApiResult
import com.eldering.safety.network.FamilyDto
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Where the app opens. */
enum class StartDestination { WELCOME, FAMILY_SETUP, PERMISSIONS, HOME }

/**
 * Picks the first screen.
 *
 * @param families my families from `/v1/me`, or null if it could not be loaded (offline): then a signed-in user is
 *   assumed to be set up already, so a phone without signal still opens straight to the family map.
 */
fun decideStart(
    signedIn: Boolean,
    families: List<FamilyDto>?,
    meError: ApiError?,
    permissionsDone: Boolean,
): StartDestination = when {
    !signedIn || meError is ApiError.SignedOut -> StartDestination.WELCOME
    families != null && families.isEmpty() -> StartDestination.FAMILY_SETUP
    !permissionsDone -> StartDestination.PERMISSIONS
    else -> StartDestination.HOME
}

fun StartDestination.toRoute(): Route = when (this) {
    StartDestination.WELCOME -> Route.Welcome
    StartDestination.FAMILY_SETUP -> Route.FamilySetup(canGoBack = false)
    StartDestination.PERMISSIONS -> Route.Permissions(onboarding = true)
    StartDestination.HOME -> Route.Home
}

/** Decides the start destination once per activity. */
class StartViewModel(
    private val auth: AuthRepository,
    private val preferences: AppPreferences,
) : ViewModel() {
    private val state = MutableStateFlow<StartDestination?>(null)

    /** Null while deciding (a splash is shown). */
    val start: StateFlow<StartDestination?> = state.asStateFlow()

    init {
        viewModelScope.launch { state.value = resolve(auth, preferences) }
    }

    companion object {
        private const val ME_TIMEOUT_MS = 4_000L

        /** Also used after sign-in, so both paths agree on where a user lands. */
        suspend fun resolve(auth: AuthRepository, preferences: AppPreferences): StartDestination {
            val session = auth.currentSession() ?: return StartDestination.WELCOME
            // Bounded: with no signal the app must still open quickly (the HTTP timeouts are much longer).
            val me = withTimeoutOrNull(ME_TIMEOUT_MS) { auth.refreshMe() }
            val families = (me as? ApiResult.Success)?.value?.families
            val error = (me as? ApiResult.Failure)?.error
            val permissionsDone = preferences.permissionsWalkthroughDone.first()
            return decideStart(signedIn = session.userId.isNotEmpty(), families, error, permissionsDone)
        }
    }
}
