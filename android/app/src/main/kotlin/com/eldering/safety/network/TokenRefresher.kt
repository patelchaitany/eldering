package com.eldering.safety.network

import android.util.Log
import com.eldering.safety.core.TimeSource
import java.io.IOException
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Single-flight refresh-token rotation (`POST /v1/auth/refresh`).
 *
 * Refresh tokens rotate and the server treats reuse of a rotated token as theft (it revokes every session of the
 * user). So at most one refresh may be in flight per process, and a caller that waited while another thread
 * refreshed must reuse that result instead of refreshing again. Callers pass the access token their request was
 * sent with ([refresh]'s `staleAccessToken`): if the stored token already differs, someone else refreshed.
 *
 * Blocking; called only from OkHttp threads ([AuthInterceptor], [TokenAuthenticator]).
 */
class TokenRefresher(
    private val sessions: SessionProvider,
    private val refreshApi: TokenRefreshApi,
    private val time: TimeSource = TimeSource.System,
) {
    sealed interface Outcome {
        /** A valid (possibly just rotated) access token. */
        data class Refreshed(val accessToken: String) : Outcome

        /** No session, or the server rejected the refresh token; the session has been cleared. */
        data object SignedOut : Outcome

        /** Transient failure (network, 429, 5xx); the session is kept. */
        data class Failed(val cause: IOException) : Outcome
    }

    private val lock = ReentrantLock()

    fun refresh(staleAccessToken: String?): Outcome = lock.withLock {
        val current = sessions.currentSessionBlocking() ?: return Outcome.SignedOut
        if (staleAccessToken != null && current.accessToken != staleAccessToken) {
            return Outcome.Refreshed(current.accessToken) // another caller already rotated
        }

        val response = try {
            refreshApi.refresh(RefreshTokenRequest(current.refreshToken)).execute()
        } catch (e: IOException) {
            return Outcome.Failed(e)
        } catch (e: RuntimeException) { // malformed 2xx body (SerializationException)
            Log.w(TAG, "Malformed refresh response", e)
            return Outcome.Failed(IOException("Malformed refresh response", e))
        }

        val body = response.body()
        when {
            response.isSuccessful && body != null -> {
                val next = Session.from(body, time.nowMillis())
                if (!sessions.replaceSessionBlocking(current.refreshToken, next)) {
                    // Logged out (or another account logged in) while the refresh was in flight.
                    return Outcome.SignedOut
                }
                Outcome.Refreshed(next.accessToken)
            }
            response.code() == 408 || response.code() == 429 || response.code() >= 500 -> {
                response.errorBody()?.close()
                Outcome.Failed(IOException("Token refresh failed with HTTP ${response.code()}"))
            }
            else -> {
                // 401 invalid_refresh_token (expired, revoked or reused) or another client error: the session is
                // unusable. Only clear it if nobody replaced it meanwhile.
                response.errorBody()?.close()
                Log.i(TAG, "Refresh token rejected (HTTP ${response.code()}); signing out")
                sessions.clearSessionBlocking(current.refreshToken)
                Outcome.SignedOut
            }
        }
    }

    private companion object {
        const val TAG = "TokenRefresher"
    }
}
