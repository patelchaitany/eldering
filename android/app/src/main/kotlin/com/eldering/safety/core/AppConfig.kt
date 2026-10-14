package com.eldering.safety.core

import com.eldering.safety.BuildConfig

/** Build-time configuration, read once from [BuildConfig] so the rest of the app never touches it directly. */
data class AppConfig(
    /**
     * API origin without a trailing slash, e.g. `https://safety.example.com`. Always https, except that debug builds
     * may point at a local backend over http on the emulator host (`http://10.0.2.2[:port]`) or on `localhost[:port]`
     * (`adb reverse`), the only cleartext hosts the debug network security config allows.
     */
    val baseUrl: String,
    /** MapLibre style URL (OpenFreeMap Liberty, no API key). */
    val mapStyleUrl: String,
    val isDebug: Boolean,
) {
    init {
        require(!baseUrl.endsWith("/")) { "baseUrl must not end with '/': $baseUrl" }
        require(baseUrl.startsWith("https://") || (isDebug && isLocalDebugOrigin(baseUrl))) {
            if (isDebug) {
                "baseUrl must use https, or http://10.0.2.2[:port] / http://localhost[:port] in debug builds: $baseUrl"
            } else {
                "baseUrl must use https: $baseUrl"
            }
        }
    }

    companion object {
        fun fromBuildConfig(): AppConfig = AppConfig(
            baseUrl = BuildConfig.BASE_URL,
            mapStyleUrl = BuildConfig.MAP_STYLE_URL,
            isDebug = BuildConfig.DEBUG,
        )

        /** Cleartext origins a debug build may use; keep in sync with src/debug/res/xml/network_security_config.xml. */
        private val LOCAL_DEBUG_ORIGIN = Regex("""http://(?:10\.0\.2\.2|localhost)(?::(\d{1,5}))?""")

        /** `http://10.0.2.2` or `http://localhost`, with an optional port in 1..65535 and nothing else (no path). */
        internal fun isLocalDebugOrigin(url: String): Boolean {
            val match = LOCAL_DEBUG_ORIGIN.matchEntire(url) ?: return false
            val port = match.groupValues[1]
            return port.isEmpty() || port.toInt() in 1..65535
        }
    }
}
