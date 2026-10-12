package com.eldering.safety.core

import com.eldering.safety.BuildConfig

/** Build-time configuration, read once from [BuildConfig] so the rest of the app never touches it directly. */
data class AppConfig(
    /** API origin without a trailing slash, e.g. `https://safety.example.com`. */
    val baseUrl: String,
    /** MapLibre style URL (OpenFreeMap Liberty, no API key). */
    val mapStyleUrl: String,
    val isDebug: Boolean,
) {
    init {
        require(baseUrl.startsWith("https://")) { "baseUrl must use https: $baseUrl" }
        require(!baseUrl.endsWith("/")) { "baseUrl must not end with '/': $baseUrl" }
    }

    companion object {
        fun fromBuildConfig(): AppConfig = AppConfig(
            baseUrl = BuildConfig.BASE_URL,
            mapStyleUrl = BuildConfig.MAP_STYLE_URL,
            isDebug = BuildConfig.DEBUG,
        )
    }
}
