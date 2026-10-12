package com.eldering.safety.network

import java.io.IOException
import java.io.InterruptedIOException
import java.time.Duration
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import retrofit2.HttpException

/** Outcome of an API call: a value or a mapped [ApiError]. Never throws except for coroutine cancellation. */
sealed interface ApiResult<out T> {
    data class Success<out T>(val value: T) : ApiResult<T>

    data class Failure(val error: ApiError) : ApiResult<Nothing>
}

val ApiResult<*>.isSuccess: Boolean get() = this is ApiResult.Success

fun <T> ApiResult<T>.getOrNull(): T? = (this as? ApiResult.Success)?.value

fun ApiResult<*>.errorOrNull(): ApiError? = (this as? ApiResult.Failure)?.error

fun <T> ApiResult<T>.getOrThrow(): T = when (this) {
    is ApiResult.Success -> value
    is ApiResult.Failure -> throw error
}

inline fun <T, R> ApiResult<T>.map(transform: (T) -> R): ApiResult<R> = when (this) {
    is ApiResult.Success -> ApiResult.Success(transform(value))
    is ApiResult.Failure -> this
}

inline fun <T> ApiResult<T>.onSuccess(action: (T) -> Unit): ApiResult<T> {
    if (this is ApiResult.Success) action(value)
    return this
}

inline fun <T> ApiResult<T>.onFailure(action: (ApiError) -> Unit): ApiResult<T> {
    if (this is ApiResult.Failure) action(error)
    return this
}

/**
 * Why an API call failed. [isRetryable] tells the outbox whether the same request may succeed later unchanged.
 */
sealed class ApiError(message: String, cause: Throwable? = null) : Exception(message, cause) {

    abstract val isRetryable: Boolean

    /** No connection, DNS failure, TLS failure, timeout or connection reset. */
    class Network(cause: IOException) : ApiError("Network error: ${cause.javaClass.simpleName}", cause) {
        val isTimeout: Boolean get() = cause is InterruptedIOException
        override val isRetryable: Boolean get() = true
    }

    /**
     * The server answered with a non-2xx status. [code] is the envelope's machine-readable code (for example
     * `invalid_credentials`, `email_taken`, `invite_invalid`, `incident_closed`, `rate_limited`), or `http_<status>`
     * if the body was not the SPEC envelope.
     */
    class Http(
        val status: Int,
        val code: String,
        val serverMessage: String,
        /** From `Retry-After` (429, 503). */
        val retryAfter: Duration? = null,
    ) : ApiError("HTTP $status $code: $serverMessage") {
        override val isRetryable: Boolean get() = status == 408 || status == 429 || status >= 500
        val isUnauthorized: Boolean get() = status == 401
        val isForbidden: Boolean get() = status == 403
        /** Also used by the server for "exists but not yours" (RBAC never leaks existence). */
        val isNotFound: Boolean get() = status == 404
        val isConflict: Boolean get() = status == 409
        val isRateLimited: Boolean get() = status == 429
    }

    /** No session (never signed in, signed out, or the refresh token was rejected). */
    class SignedOut : ApiError("Not signed in") {
        override val isRetryable: Boolean get() = false
    }

    /** Malformed response or a client bug; retrying the same request will not help. */
    class Unexpected(cause: Throwable) : ApiError("Unexpected API failure: ${cause.javaClass.simpleName}", cause) {
        override val isRetryable: Boolean get() = false
    }
}

/** Thrown by [AuthInterceptor] when an authenticated endpoint is called without a session. */
class NotSignedInException : IOException("No session for an authenticated request")

/**
 * Runs [block] (one or more [ElderingApi] calls) and maps every failure to an [ApiError].
 * Coroutine cancellation is rethrown, never swallowed.
 */
suspend fun <T> apiCall(json: Json = ApiJson, block: suspend () -> T): ApiResult<T> = try {
    ApiResult.Success(block())
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    ApiResult.Failure(e.toApiError(json))
}

/** Maps any exception thrown by Retrofit/OkHttp/kotlinx.serialization to an [ApiError]. */
fun Throwable.toApiError(json: Json = ApiJson): ApiError = when (this) {
    is ApiError -> this
    is HttpException -> toHttpError(json)
    is NotSignedInException -> ApiError.SignedOut()
    is IOException -> ApiError.Network(this)
    is SerializationException -> ApiError.Unexpected(this)
    else -> ApiError.Unexpected(this)
}

private fun HttpException.toHttpError(json: Json): ApiError.Http {
    val response = response()
    val raw = try {
        response?.errorBody()?.string()
    } catch (_: IOException) {
        null
    }
    val envelope = raw?.takeIf { it.isNotBlank() }?.let {
        try {
            json.decodeFromString(ErrorEnvelope.serializer(), it).error
        } catch (_: SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }
    val retryAfter = response?.headers()?.get("Retry-After")?.trim()?.toLongOrNull()
        ?.takeIf { it >= 0 }
        ?.let(Duration::ofSeconds)
    return ApiError.Http(
        status = code(),
        code = envelope?.code ?: "http_${code()}",
        serverMessage = envelope?.message ?: message().orEmpty(),
        retryAfter = retryAfter,
    )
}
