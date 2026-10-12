package com.eldering.safety.network

import android.util.Log
import com.eldering.safety.core.TimeSource
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

/** HTTP timeouts. Emergency uploads must fail fast enough to fall back to SMS and the outbox. */
object NetworkTimeouts {
    const val CONNECT_S = 15L
    const val READ_S = 20L
    const val WRITE_S = 20L
    /** Whole call, including the redirect/refresh follow-up. */
    const val CALL_S = 45L
}

/**
 * The API client stack: one shared [OkHttpClient] (connection pool, dispatcher) with an authenticated variant,
 * a Retrofit [ElderingApi] and the single-flight [TokenRefresher].
 *
 * @param baseUrl API origin, e.g. `AppConfig.baseUrl` (`https://…`, no trailing slash) or a MockWebServer URL.
 */
class ElderingNetwork(
    baseUrl: String,
    sessions: SessionProvider,
    time: TimeSource = TimeSource.System,
    baseClient: OkHttpClient = NetworkModule.baseClient(debugLogging = false, userAgent = null),
    json: Json = ApiJson,
) {
    private val normalizedBaseUrl = baseUrl.trimEnd('/') + "/"

    /** Client without auth: refresh calls and public endpoints only. */
    private val refreshRetrofit = NetworkModule.retrofit(normalizedBaseUrl, baseClient, json)

    val refresher: TokenRefresher = TokenRefresher(sessions, refreshRetrofit.create(TokenRefreshApi::class.java), time)

    val okHttpClient: OkHttpClient = baseClient.newBuilder()
        .addInterceptor(AuthInterceptor(sessions, refresher, time))
        .authenticator(TokenAuthenticator(refresher))
        .build()

    val api: ElderingApi = NetworkModule.retrofit(normalizedBaseUrl, okHttpClient, json).create(ElderingApi::class.java)
}

object NetworkModule {

    private val SHARE_TOKEN_IN_PATH = Regex("/s/[A-Za-z0-9_-]+")

    /**
     * The base client: timeouts, User-Agent and (debug builds only) request logging at BASIC level, which logs
     * method, URL and status but never headers or bodies, so tokens and locations stay out of logcat. Share
     * tokens in `/s/{token}` URLs are redacted as well.
     */
    fun baseClient(debugLogging: Boolean, userAgent: String?): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(NetworkTimeouts.CONNECT_S, TimeUnit.SECONDS)
        .readTimeout(NetworkTimeouts.READ_S, TimeUnit.SECONDS)
        .writeTimeout(NetworkTimeouts.WRITE_S, TimeUnit.SECONDS)
        .callTimeout(NetworkTimeouts.CALL_S, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .apply {
            if (userAgent != null) addInterceptor(userAgentInterceptor(userAgent))
            if (debugLogging) {
                val logger = HttpLoggingInterceptor { message ->
                    Log.d("ElderingHttp", message.replace(SHARE_TOKEN_IN_PATH, "/s/<redacted>"))
                }
                logger.level = HttpLoggingInterceptor.Level.BASIC
                logger.redactHeader("Authorization")
                addInterceptor(logger)
            }
        }
        .build()

    fun retrofit(baseUrl: String, client: OkHttpClient, json: Json = ApiJson): Retrofit = Retrofit.Builder()
        .baseUrl(baseUrl)
        .client(client)
        .addConverterFactory(json.asConverterFactory("application/json; charset=UTF-8".toMediaType()))
        .build()

    private fun userAgentInterceptor(userAgent: String) = Interceptor { chain ->
        chain.proceed(chain.request().newBuilder().header("User-Agent", userAgent).build())
    }
}
