package com.eldering.safety.core

import com.eldering.safety.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AppConfigTest {

    @Test
    fun `build config provides a valid API origin and keeps the release package name`() {
        val config = AppConfig.fromBuildConfig()

        assertEquals(BuildConfig.BASE_URL, config.baseUrl)
        assertEquals(BuildConfig.DEBUG, config.isDebug)
        assertEquals("https://tiles.openfreemap.org/styles/liberty", config.mapStyleUrl)
        // No applicationIdSuffix in debug: adb/debug-inject commands rely on this exact package.
        assertEquals("com.eldering.safety", BuildConfig.APPLICATION_ID)
        // Only a debug build may carry a cleartext (local backend) origin.
        if (!BuildConfig.DEBUG) assertTrue(BuildConfig.BASE_URL, BuildConfig.BASE_URL.startsWith("https://"))
    }

    @Test
    fun `release builds require https for every host`() {
        for (url in listOf(
            "http://example.test",
            "http://10.0.2.2",
            "http://10.0.2.2:8095",
            "http://localhost",
            "http://localhost:8095",
            "ftp://example.test",
            "example.test",
        )) {
            assertThrows(url, IllegalArgumentException::class.java) { release(url) }
        }
        assertEquals("https://example.test", release("https://example.test").baseUrl)
        assertEquals("https://example.test:8443", release("https://example.test:8443").baseUrl)
    }

    @Test
    fun `debug builds may use http only for the emulator host or localhost`() {
        for (url in listOf(
            "http://10.0.2.2",
            "http://10.0.2.2:8095",
            "http://localhost",
            "http://localhost:8095",
            "http://localhost:65535",
            "https://example.test",
        )) {
            assertEquals(url, debug(url).baseUrl)
        }
    }

    @Test
    fun `debug builds reject cleartext to any other host and malformed local origins`() {
        for (url in listOf(
            "http://example.test",
            "http://127.0.0.1:8095", // not in the debug network security config
            "http://10.0.2.20:8095",
            "http://110.0.2.2:8095",
            "http://10.0.2.2.example.test",
            "http://localhost.example.test:8095",
            "http://localhost@example.test",
            "http://10.0.2.2:8095/v1", // origin only, no path
            "http://10.0.2.2:8095?x=1",
            "http://10.0.2.2:",
            "http://10.0.2.2:0",
            "http://10.0.2.2:65536",
            "http://10.0.2.2:123456",
            "HTTP://10.0.2.2:8095",
            " http://10.0.2.2:8095",
            "ftp://10.0.2.2",
        )) {
            assertThrows(url, IllegalArgumentException::class.java) { debug(url) }
            assertFalse(url, AppConfig.isLocalDebugOrigin(url))
        }
    }

    @Test
    fun `rejects a trailing slash in any build`() {
        for (url in listOf("https://example.test/", "http://10.0.2.2:8095/", "http://localhost/")) {
            assertThrows(url, IllegalArgumentException::class.java) { debug(url) }
            assertThrows(url, IllegalArgumentException::class.java) { release(url) }
        }
    }

    private fun debug(baseUrl: String) = AppConfig(baseUrl = baseUrl, mapStyleUrl = "", isDebug = true)

    private fun release(baseUrl: String) = AppConfig(baseUrl = baseUrl, mapStyleUrl = "", isDebug = false)
}
