package com.eldering.safety.network

import com.eldering.safety.core.TimeSource
import okhttp3.Authenticator
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route
import retrofit2.Invocation

private const val AUTHORIZATION = "Authorization"
private const val BEARER = "Bearer "

/** True for Retrofit calls to functions annotated with [Unauthenticated]. */
internal fun Request.isUnauthenticatedEndpoint(): Boolean =
    tag(Invocation::class.java)?.method()?.isAnnotationPresent(Unauthenticated::class.java) == true

/**
 * Adds `Authorization: Bearer <access token>` to every authenticated endpoint and refreshes proactively when the
 * token is about to expire (saves the 401 round trip; a refresh still happens reactively in [TokenAuthenticator]
 * if the server rejects a token early, e.g. after a key rotation).
 *
 * Throws [NotSignedInException] (an IOException, so OkHttp propagates it cleanly) when there is no session.
 */
class AuthInterceptor(
    private val sessions: SessionProvider,
    private val refresher: TokenRefresher,
    private val time: TimeSource = TimeSource.System,
    private val refreshMarginMs: Long = DEFAULT_REFRESH_MARGIN_MS,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.isUnauthenticatedEndpoint()) {
            return chain.proceed(request.newBuilder().removeHeader(AUTHORIZATION).build())
        }
        val session = sessions.currentSessionBlocking() ?: throw NotSignedInException()
        var token = session.accessToken
        if (session.accessExpiresWithin(refreshMarginMs, time.nowMillis())) {
            when (val outcome = refresher.refresh(staleAccessToken = token)) {
                is TokenRefresher.Outcome.Refreshed -> token = outcome.accessToken
                TokenRefresher.Outcome.SignedOut -> throw NotSignedInException()
                // Offline or server trouble: send the old token anyway. The request most likely fails the same
                // way; if it reaches the server and gets a 401, the authenticator retries the refresh.
                is TokenRefresher.Outcome.Failed -> Unit
            }
        }
        return chain.proceed(request.newBuilder().header(AUTHORIZATION, BEARER + token).build())
    }

    companion object {
        const val DEFAULT_REFRESH_MARGIN_MS: Long = 30_000
    }
}

/**
 * Reactive refresh: on a 401 from an authenticated endpoint, rotates the tokens once (single-flight through
 * [TokenRefresher]) and retries the request with the new access token.
 *
 * - Unauthenticated endpoints (login's `invalid_credentials`) are never retried.
 * - A request is retried at most once; a second 401 is final.
 * - If the refresh token is rejected the session is cleared and the call fails with [NotSignedInException]
 *   (→ [ApiError.SignedOut]); if the refresh fails transiently the call fails with that IOException
 *   (→ [ApiError.Network]) and the session is kept.
 */
class TokenAuthenticator(private val refresher: TokenRefresher) : Authenticator {

    override fun authenticate(route: Route?, response: Response): Request? {
        val request = response.request
        if (request.isUnauthenticatedEndpoint()) return null
        val sentToken = request.header(AUTHORIZATION)
            ?.takeIf { it.startsWith(BEARER, ignoreCase = true) }
            ?.substring(BEARER.length)
            ?.trim()
            ?: return null
        if (response.priorResponse?.code == 401) return null // already retried with a refreshed token

        return when (val outcome = refresher.refresh(staleAccessToken = sentToken)) {
            is TokenRefresher.Outcome.Refreshed ->
                if (outcome.accessToken == sentToken) {
                    null
                } else {
                    request.newBuilder().header(AUTHORIZATION, BEARER + outcome.accessToken).build()
                }
            TokenRefresher.Outcome.SignedOut -> throw NotSignedInException()
            is TokenRefresher.Outcome.Failed -> throw outcome.cause
        }
    }
}
