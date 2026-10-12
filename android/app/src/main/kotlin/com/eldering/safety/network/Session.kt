package com.eldering.safety.network

import kotlinx.serialization.Serializable

/**
 * The signed-in user's credentials. Persisted encrypted by `data.session.SessionStore`; never logged
 * ([toString] redacts the tokens).
 */
@Serializable
data class Session(
    val userId: String,
    val email: String,
    val displayName: String,
    val accessToken: String,
    /** Local wall-clock time after which the access token must be refreshed. */
    val accessExpiresAtMs: Long,
    val refreshToken: String,
    /** Informational: server-reported refresh-token expiry; the server alone decides validity. */
    val refreshExpiresAtMs: Long? = null,
) {
    fun accessExpiresWithin(marginMs: Long, nowMs: Long): Boolean = accessExpiresAtMs - marginMs <= nowMs

    override fun toString(): String =
        "Session(userId=$userId, email=$email, displayName=$displayName, accessToken=██, " +
            "accessExpiresAtMs=$accessExpiresAtMs, refreshToken=██)"

    companion object {
        /** Fallback when the server does not report a lifetime (SPEC: access tokens live 15 min). */
        const val DEFAULT_ACCESS_TTL_MS: Long = 15 * 60 * 1000L

        /**
         * Builds a session from an auth response received at [receivedAtMs].
         *
         * The access expiry is derived from the relative `expires_in` and the local clock, not from the server's
         * absolute `access_expires_at`: a phone whose clock is off by more than 15 minutes would otherwise refresh
         * before every request (or never).
         */
        fun from(response: AuthResponse, receivedAtMs: Long): Session {
            val accessExpiry = when {
                response.expiresIn > 0 -> receivedAtMs + response.expiresIn * 1000
                else -> receivedAtMs + DEFAULT_ACCESS_TTL_MS
            }
            return Session(
                userId = response.user.id,
                email = response.user.email,
                displayName = response.user.displayName,
                accessToken = response.accessToken,
                accessExpiresAtMs = accessExpiry,
                refreshToken = response.refreshToken,
                refreshExpiresAtMs = response.refreshExpiresAt?.toEpochMilli(),
            )
        }
    }
}

/**
 * Session storage as seen by the HTTP auth layer. Blocking on purpose: it is called from OkHttp's
 * interceptor/authenticator threads, never from the main thread.
 */
interface SessionProvider {
    fun currentSessionBlocking(): Session?

    /**
     * Replaces the session only if its refresh token is still [expectedRefreshToken] (so a refresh that raced with
     * a logout or a new login never resurrects or clobbers a session). Returns whether it was replaced.
     */
    fun replaceSessionBlocking(expectedRefreshToken: String, session: Session): Boolean

    /** Clears the session only if its refresh token is still [expectedRefreshToken]. */
    fun clearSessionBlocking(expectedRefreshToken: String): Boolean
}
