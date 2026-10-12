package com.eldering.safety.data.repository

import com.eldering.safety.core.TimeSource
import com.eldering.safety.data.session.SessionStore
import com.eldering.safety.network.ApiResult
import com.eldering.safety.network.AuthResponse
import com.eldering.safety.network.ElderingApi
import com.eldering.safety.network.LoginRequest
import com.eldering.safety.network.MeResponse
import com.eldering.safety.network.RefreshTokenRequest
import com.eldering.safety.network.RegisterRequest
import com.eldering.safety.network.Session
import com.eldering.safety.network.apiCall
import com.eldering.safety.network.map
import com.eldering.safety.network.onSuccess
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Account and session lifecycle: register, login, logout, and the `/v1/me` profile.
 *
 * Token refresh is not done here: it happens transparently inside the HTTP stack (single-flight, see
 * `network.TokenRefresher`). When the server rejects the refresh token, [session] emits null.
 *
 * @param onAccountDataDiscarded wipes per-account local data (pending uploads, own incidents) when a user signs
 *   out or a different account signs in on this device.
 * @param onSignedIn runs after a successful register/login (e.g. resume queued uploads of this account).
 */
class AuthRepository(
    private val api: ElderingApi,
    private val sessionStore: SessionStore,
    private val meStore: MeStore,
    private val time: TimeSource = TimeSource.System,
    private val onAccountDataDiscarded: suspend (userId: String) -> Unit = {},
    private val onSignedIn: suspend (Session) -> Unit = {},
) {
    /** Null while signed out. */
    val session: Flow<Session?> get() = sessionStore.session

    val isSignedIn: Flow<Boolean> get() = sessionStore.session.map { it != null }.distinctUntilChanged()

    /** Last loaded `/v1/me`; call [refreshMe] to load it. */
    val me: StateFlow<MeResponse?> get() = meStore.me

    suspend fun currentSession(): Session? = sessionStore.current()

    suspend fun register(email: String, password: String, displayName: String): ApiResult<Session> =
        apiCall { api.register(RegisterRequest(email.trim(), password, displayName.trim())) }
            .map { startSession(it) }

    /** 401 `invalid_credentials` for a wrong email or password; 429 `rate_limited` (see `retryAfter`). */
    suspend fun login(email: String, password: String): ApiResult<Session> =
        apiCall { api.login(LoginRequest(email.trim(), password)) }
            .map { startSession(it) }

    suspend fun refreshMe(): ApiResult<MeResponse> = apiCall { api.me() }.onSuccess(meStore::set)

    /**
     * Signs out on this device immediately, then revokes the refresh token on the server (best effort, bounded:
     * logout must work offline too).
     */
    suspend fun logout() {
        val session = sessionStore.current() ?: return
        sessionStore.clear()
        meStore.clear()
        onAccountDataDiscarded(session.userId)
        withContext(NonCancellable) {
            withTimeoutOrNull(LOGOUT_REVOKE_TIMEOUT_MS) {
                apiCall { api.logout(RefreshTokenRequest(session.refreshToken)) }
            }
        }
    }

    private suspend fun startSession(response: AuthResponse): Session {
        val session = Session.from(response, time.nowMillis())
        val previous = sessionStore.current()
        if (previous != null && previous.userId != session.userId) {
            onAccountDataDiscarded(previous.userId)
        }
        meStore.clear()
        sessionStore.save(session)
        onSignedIn(session)
        return session
    }

    private companion object {
        const val LOGOUT_REVOKE_TIMEOUT_MS = 5_000L
    }
}
