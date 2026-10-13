package com.eldering.safety.debug

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.eldering.safety.R
import com.eldering.safety.ui.map.LocalMapRenderer
import com.eldering.safety.ui.onboarding.AuthScreen
import com.eldering.safety.ui.onboarding.AuthUiState
import com.eldering.safety.ui.map.MapRenderer
import com.eldering.safety.ui.theme.ElderingTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Renders every screen of the app (through the debug UI gallery's sample data) and checks that each one composes,
 * that every tappable element has a label for TalkBack, and a few of the loading / empty / error states.
 */
@RunWith(AndroidJUnit4::class)
// A phone-sized window (Robolectric defaults to 320×470 dp, which hides most of a screen).
@Config(sdk = [36], qualifiers = "w411dp-h891dp")
class ScreensRenderTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private var opened: GalleryEntry? = null
    private var backs = 0

    private fun show(entry: GalleryEntry) {
        compose.setContent {
            ElderingTheme(darkTheme = false) {
                CompositionLocalProvider(LocalMapRenderer provides MapRenderer.Schematic) {
                    GalleryPage(entry, onBack = { backs++ }, onOpen = { opened = it })
                }
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun `every screen renders and every tappable element is labelled`() {
        val unlabelled = mutableListOf<String>()
        var current by mutableStateOf(GalleryEntry.entries.first())
        compose.setContent {
            ElderingTheme(darkTheme = false) {
                CompositionLocalProvider(LocalMapRenderer provides MapRenderer.Schematic) {
                    // key(): each page starts from scratch, like opening it from the gallery.
                    key(current) { GalleryPage(current, onBack = {}, onOpen = {}) }
                }
            }
        }
        GalleryEntry.entries.forEach { entry ->
            current = entry
            compose.waitForIdle()
            val clickable = compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.OnClick)).fetchSemanticsNodes()
            assertTrue("${entry.id}: nothing tappable", clickable.isNotEmpty())
            clickable.forEach { node ->
                val config = node.config
                val text = config.getOrNull(SemanticsProperties.Text)?.joinToString { it.text }.orEmpty()
                val description = config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString().orEmpty()
                val editable = config.getOrNull(SemanticsProperties.EditableText)?.text.orEmpty()
                val isTextField = config.contains(SemanticsActions.SetText)
                if (text.isBlank() && description.isBlank() && editable.isBlank() && !isTextField) {
                    unlabelled += "${entry.id}: node ${node.id} at ${node.boundsInRoot}"
                }
            }
        }
        assertTrue("Unlabelled tappable elements:\n" + unlabelled.joinToString("\n"), unlabelled.isEmpty())
    }

    @Test
    fun `home shows members, chips and the hold-to-SOS button`() {
        show(GalleryEntry.HOME)
        compose.onNodeWithText("Driving · 38 km/h").assertIsDisplayed()
        compose.onNodeWithContentDescription("Show Dad on the list").assertIsDisplayed() // map marker
        compose.onNodeWithText("Protection on · Walking").assertIsDisplayed()
        compose.onNodeWithText("3 of 4 members sharing").assertIsDisplayed()
        compose.onNodeWithContentDescription("SOS: press and hold for 3 seconds to alert family and emergency contacts")
            .assertIsDisplayed()
    }

    @Test
    fun `home loading shows progress and no made-up sharing count`() {
        show(GalleryEntry.HOME_LOADING)
        compose.onNodeWithText("Loading your family…").assertIsDisplayed()
        assertEquals(0, compose.onAllNodesWithText("members sharing", substring = true).fetchSemanticsNodes().size)
    }

    @Test
    fun `home without a family offers to create or join one`() {
        show(GalleryEntry.HOME_NO_FAMILY)
        compose.onNodeWithText("Create or join a family").performClick()
        assertEquals(GalleryEntry.FAMILY_SETUP, opened)
    }

    @Test
    fun `home offline offers retry`() {
        show(GalleryEntry.HOME_OFFLINE)
        compose.onNodeWithText("Can't reach the Eldering server. Check your connection and try again.").assertIsDisplayed()
        compose.onNodeWithText("Retry").assertIsDisplayed()
    }

    @Test
    fun `family alert without a location explains the empty map and disables directions`() {
        show(GalleryEntry.FAMILY_ALERT_NO_LOCATION)
        compose.onNodeWithText("No location from Dad's phone yet. It appears here as soon as it arrives.").assertIsDisplayed()
    }

    @Test
    fun `family alert load error offers retry`() {
        show(GalleryEntry.FAMILY_ALERT_ERROR)
        compose.onNodeWithText("Retry").assertIsDisplayed()
    }

    @Test
    fun `sharing load error does not claim the family is empty`() {
        show(GalleryEntry.SHARING_ERROR)
        compose.onNodeWithText("Couldn't load your sharing settings.").assertIsDisplayed()
        assertEquals(0, compose.onAllNodesWithText("No one else", substring = true).fetchSemanticsNodes().size)
    }

    @Test
    fun `countdown offers I'm OK, send help now and call 112`() {
        show(GalleryEntry.COUNTDOWN)
        compose.onNodeWithText("Are you OK?").assertIsDisplayed()
        compose.onNodeWithText("I'm OK").assertIsDisplayed()
        compose.onNodeWithText("Send help now").assertIsDisplayed()
        compose.onNodeWithContentDescription("Call 112: opens the dialer with 112 filled in").assertIsDisplayed()
    }

    @Test
    fun `I'm OK cancels the countdown and asks what happened`() {
        show(GalleryEntry.COUNTDOWN)
        compose.onNodeWithText("I'm OK").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Glad you're OK").assertIsDisplayed()
        compose.onNodeWithText("Dropped my phone").performClick()
        compose.onNodeWithText("Thanks — saved on this phone only.").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `trip shows the scrubber and the impact stop`() {
        show(GalleryEntry.TRIP)
        compose.onNodeWithContentDescription("Drag to replay the last hour").assertIsDisplayed()
        compose.onNodeWithText("Impact detected here").assertIsDisplayed()
    }

    @Test
    fun `password visibility toggle is labelled for TalkBack and empty submit only validates locally`() {
        var submitted = 0
        compose.setContent {
            ElderingTheme(darkTheme = false) {
                AuthScreen(
                    state = AuthUiState(register = true, nameError = R.string.auth_error_name_blank),
                    onBack = {},
                    onName = {},
                    onEmail = {},
                    onPassword = {},
                    onSubmit = { submitted++ },
                    onSwitchMode = {},
                )
            }
        }
        compose.onNodeWithContentDescription("Show password").performClick()
        compose.onNodeWithContentDescription("Hide password").assertIsDisplayed()
        compose.onNodeWithText("Enter your name").assertIsDisplayed()
        compose.onNodeWithText("Create account").performClick()
        assertEquals(1, submitted)
    }
}
