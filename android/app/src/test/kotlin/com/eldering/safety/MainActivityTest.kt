package com.eldering.safety

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.eldering.safety.core.appGraph
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class MainActivityTest {

    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun `launches and shows the app name`() {
        compose.onNodeWithText("Eldering").assertIsDisplayed()
    }

    @Test
    fun `application exposes the app graph`() {
        val app = ApplicationProvider.getApplicationContext<ElderingApp>()
        assertEquals(BuildConfig.BASE_URL, app.appGraph.config.baseUrl)
    }
}
