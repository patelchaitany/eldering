package com.eldering.safety.network

import android.security.NetworkSecurityPolicy
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.eldering.safety.BuildConfig
import com.eldering.safety.ElderingApp
import com.eldering.safety.core.appGraph
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device checks of the debug network setup, run inside the app's own process (so the app's network security
 * config applies):
 *  - cleartext http is permitted only to 10.0.2.2 and localhost (src/debug/res/xml/network_security_config.xml);
 *  - with the instrumentation argument `liveBackend=true`, the app's own API client reaches `BuildConfig.BASE_URL`
 *    (e.g. a local backend at `eldering.debugBaseUrl=http://10.0.2.2:8095`) and gets `/healthz` = ok.
 *
 * To keep the installed app and its data (connectedDebugAndroidTest uninstalls the app afterwards):
 *
 *     ./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
 *     adb install -r app/build/outputs/apk/debug/app-debug.apk
 *     adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
 *     adb shell am instrument -w -e class com.eldering.safety.network.BackendConnectivityTest -e liveBackend true \
 *         com.eldering.safety.test/androidx.test.runner.AndroidJUnitRunner
 */
@RunWith(AndroidJUnit4::class)
class BackendConnectivityTest {

    @Test
    fun cleartextIsPermittedOnlyToTheLocalDebugHosts() {
        val policy = NetworkSecurityPolicy.getInstance()
        assertTrue(policy.isCleartextTrafficPermitted("10.0.2.2"))
        assertTrue(policy.isCleartextTrafficPermitted("localhost"))
        assertFalse(policy.isCleartextTrafficPermitted())
        for (host in listOf("example.com", "127.0.0.1", "10.0.2.20", "sub.localhost", "tiles.openfreemap.org")) {
            assertFalse(host, policy.isCleartextTrafficPermitted(host))
        }
    }

    @Test
    fun appApiClientReachesTheConfiguredBackend() {
        val live = InstrumentationRegistry.getArguments().getString("liveBackend")?.toBoolean() ?: false
        assumeTrue("pass -e liveBackend true to call ${BuildConfig.BASE_URL}/healthz", live)

        val graph = ApplicationProvider.getApplicationContext<ElderingApp>().appGraph
        assertEquals(BuildConfig.BASE_URL, graph.config.baseUrl)
        val health = runBlocking { apiCall { graph.api.health() } }
        assertEquals("ok", health.getOrThrow().status)
    }
}
