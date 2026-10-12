/*
 * HTTP client for the Eldering API (SPEC "HTTP API"; base URL from AppConfig.baseUrl = BuildConfig.BASE_URL).
 *
 * - ElderingApi: Retrofit interface with every endpoint; ApiModels: kotlinx.serialization wire models.
 * - apiCall/ApiResult/ApiError: every failure mapped to Network / Http(status, code) / SignedOut / Unexpected.
 * - AuthInterceptor + TokenAuthenticator + TokenRefresher: bearer tokens with proactive and reactive,
 *   single-flight refresh-token rotation. ElderingNetwork wires the stack; NetworkModule holds timeouts/logging.
 */
package com.eldering.safety.network
