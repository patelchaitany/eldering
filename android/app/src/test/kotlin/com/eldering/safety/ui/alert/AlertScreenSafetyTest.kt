package com.eldering.safety.ui.alert

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.eldering.safety.network.Activity
import com.eldering.safety.network.IncidentKind
import com.eldering.safety.ui.theme.ElderingTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * E2E: a tap meant for "I'm OK" landed just after the countdown expired and hit "Call 112", which sat in the same
 * place on the "sent" screen (the dialer opened with 112, twice). And "I'm safe now" ended the alert with one tap.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], qualifiers = "w411dp-h891dp")
class AlertScreenSafetyTest {

    @get:Rule
    val compose = createComposeRule()

    private var dialed = 0
    private var safe = 0
    private var phase by mutableStateOf(AlertPhase.COUNTING)

    private fun show() {
        compose.setContent {
            ElderingTheme {
                AccidentAlertScreen(
                    state = AccidentAlertUiState(
                        info = AccidentAlertInfo(IncidentKind.FALL, 0L, 15_000L, Activity.WALKING, listOf("Mom", "Dad"), 1),
                        phase = phase,
                        secondsLeft = if (phase == AlertPhase.COUNTING) 3 else 0,
                        deliveries = emptyList(),
                    ),
                    onImOk = { safe++ },
                    onSendNow = {},
                    onCallEmergency = { dialed++ },
                    onFeedback = {},
                    onClose = {},
                )
            }
        }
    }

    @Test
    fun `the countdown names family and contacts`() {
        show()
        compose.onNodeWithText("· To Mom, Dad (app) and 1 emergency contact (SMS)").assertIsDisplayed()
    }

    @Test
    fun `taps right after the countdown runs out are ignored, then the buttons work`() {
        show()
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        compose.runOnUiThread {
            phase = AlertPhase.SENT
            Snapshot.sendApplyNotifications()
        }
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithText("Help is on the way".uppercase()).assertExists()
        compose.onNodeWithTag(TAP_SHIELD_TAG).assertExists()
        compose.onNodeWithText("Call 112").performClick()
        compose.onNodeWithText("I'm safe now — tell family").performClick()
        compose.mainClock.advanceTimeByFrame()
        assertEquals("no dialer from a late tap", 0, dialed)
        compose.onNodeWithText("Tell your family you're safe?").assertDoesNotExist()

        compose.mainClock.advanceTimeBy(AlertScreenTiming.TAP_GUARD_MS + 100)
        compose.onNodeWithTag(TAP_SHIELD_TAG).assertDoesNotExist()
        compose.onNodeWithText("Call 112").performClick()
        assertEquals(1, dialed)
    }

    @Test
    fun `I'm safe now sits where I'm OK was and asks before ending the alert`() {
        phase = AlertPhase.SENT // opened in the sent state: no guard
        show()
        compose.onNodeWithTag(TAP_SHIELD_TAG).assertDoesNotExist()
        val safeTop = compose.onNodeWithText("I'm safe now — tell family").fetchSemanticsNode().boundsInRoot.top
        val callTop = compose.onNodeWithText("Call 112").fetchSemanticsNode().boundsInRoot.top
        assert(safeTop < callTop) { "Call 112 must be the bottom button (where the countdown had its Call 112)" }

        compose.onNodeWithText("I'm safe now — tell family").performClick()
        assertEquals(0, safe)
        compose.onNodeWithText("Cancel").performClick()
        assertEquals(0, safe)
        compose.onNodeWithText("I'm safe now — tell family").performClick()
        compose.onNodeWithText("Yes, I'm safe").performClick()
        assertEquals(1, safe)
    }
}
