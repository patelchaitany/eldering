package com.eldering.safety.ui.components

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasSetTextAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.eldering.safety.ui.theme.ElderingTheme
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The field keeps its own text (E2E: the first character came out doubled with a StateFlow-backed value). */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class FormFieldTest {

    @get:Rule
    val compose = createComposeRule()

    private val form = MutableStateFlow("")

    private fun show(transform: (String) -> String = { it }) {
        compose.setContent {
            ElderingTheme {
                val value by form.collectAsState()
                FormField(value = value, onValueChange = { form.value = transform(it) }, label = "Name")
            }
        }
    }

    private fun field() = compose.onNode(hasSetTextAction())

    @Test
    fun `typing goes through once and reaches the caller`() {
        show()
        field().performTextInput("T")
        field().performTextInput("est")
        compose.waitForIdle()
        assertEquals("Test", form.value)
        field().assertTextEquals("Name", "Test")
    }

    @Test
    fun `changes made by the caller replace the text`() {
        show { it.uppercase() }
        field().performTextInput("ab")
        compose.waitForIdle()
        field().assertTextEquals("Name", "AB")
        form.value = "" // e.g. the form was reset
        compose.waitForIdle()
        field().assertTextEquals("Name", "")
        form.value = "Picked Name" // e.g. filled from the contact picker
        compose.waitForIdle()
        compose.onNodeWithText("Picked Name").assertExists()
    }
}
