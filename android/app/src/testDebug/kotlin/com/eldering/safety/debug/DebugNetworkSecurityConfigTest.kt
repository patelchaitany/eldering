package com.eldering.safety.debug

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.eldering.safety.R
import com.eldering.safety.core.AppConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.xmlpull.v1.XmlPullParser

/**
 * The debug-only network security config (src/debug/res/xml) may open cleartext to exactly the two local hosts that
 * AppConfig accepts for a debug backend, and must keep everything else https-only.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class DebugNetworkSecurityConfigTest {

    private data class DomainConfig(val cleartext: Boolean?, val domains: List<Pair<String, Boolean>>)

    @Test
    fun `cleartext is allowed only for 10_0_2_2 and localhost`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        var baseCleartext: Boolean? = null
        val domainConfigs = mutableListOf<DomainConfig>()
        var current: DomainConfig? = null

        context.resources.getXml(R.xml.network_security_config).use { parser ->
            while (parser.next() != XmlPullParser.END_DOCUMENT) {
                when {
                    parser.eventType == XmlPullParser.START_TAG && parser.name == "base-config" ->
                        baseCleartext = parser.getAttributeValue(null, "cleartextTrafficPermitted")?.toBoolean()
                    parser.eventType == XmlPullParser.START_TAG && parser.name == "domain-config" ->
                        current = DomainConfig(
                            parser.getAttributeValue(null, "cleartextTrafficPermitted")?.toBoolean(),
                            emptyList(),
                        )
                    parser.eventType == XmlPullParser.START_TAG && parser.name == "domain" -> {
                        val subdomains = parser.getAttributeValue(null, "includeSubdomains")?.toBoolean() ?: false
                        val host = parser.nextText().trim()
                        val config = checkNotNull(current) { "<domain> outside <domain-config>" }
                        current = config.copy(domains = config.domains + (host to subdomains))
                    }
                    parser.eventType == XmlPullParser.END_TAG && parser.name == "domain-config" -> {
                        domainConfigs += current!!
                        current = null
                    }
                }
            }
        }

        assertEquals(false, baseCleartext)
        val cleartextHosts = domainConfigs.filter { it.cleartext == true }.flatMap { it.domains }
        assertEquals(listOf("10.0.2.2" to false, "localhost" to false), cleartextHosts)
        // Every cleartext host in the config is one AppConfig accepts for a debug build, and vice versa.
        for ((host, _) in cleartextHosts) {
            assertTrue(host, AppConfig.isLocalDebugOrigin("http://$host:8095"))
        }
    }
}
