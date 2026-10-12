package com.eldering.safety.core

import com.eldering.safety.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AppConfigTest {

    @Test
    fun `build config points at the public API and keeps the release package name`() {
        val config = AppConfig.fromBuildConfig()

        assertEquals(BuildConfig.BASE_URL, config.baseUrl)
        assertEquals("https://tiles.openfreemap.org/styles/liberty", config.mapStyleUrl)
        // No applicationIdSuffix in debug: adb/debug-inject commands rely on this exact package.
        assertEquals("com.eldering.safety", BuildConfig.APPLICATION_ID)
    }

    @Test
    fun `rejects cleartext or trailing-slash base URLs`() {
        assertThrows(IllegalArgumentException::class.java) {
            AppConfig(baseUrl = "http://example.test", mapStyleUrl = "", isDebug = true)
        }
        assertThrows(IllegalArgumentException::class.java) {
            AppConfig(baseUrl = "https://example.test/", mapStyleUrl = "", isDebug = true)
        }
    }
}
