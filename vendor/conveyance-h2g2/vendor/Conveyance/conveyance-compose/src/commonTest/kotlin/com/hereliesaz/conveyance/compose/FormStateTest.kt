package com.hereliesaz.conveyance.compose

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import com.hereliesaz.conveyance.ElementId
import com.hereliesaz.conveyance.Form
import com.hereliesaz.conveyance.FormField
import com.hereliesaz.conveyance.Label
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class FormStateTest {

    private val name = FormField(ElementId("form.name"), Label("Name"))
    private val email = FormField(ElementId("form.email"), Label("Email"))
    private val signup = Form(ElementId("form.signup"), listOf(name, email))

    @Test
    fun `a fresh form state starts empty`() = runComposeUiTest {
        lateinit var state: FormState
        setContent { state = rememberFormState(signup) }
        waitForIdle()

        assertEquals(0f, state.percentComplete)
        assertFalse(state.isComplete)
    }

    @Test
    fun `marking a field filled updates percentComplete immediately`() = runComposeUiTest {
        lateinit var state: FormState
        setContent { state = rememberFormState(signup) }
        waitForIdle()

        state.mark(name.id)
        waitForIdle()
        assertEquals(0.5f, state.percentComplete)

        state.mark(email.id)
        waitForIdle()
        assertTrue(state.isComplete)
    }

    @Test
    fun `unmarking a field removes it from the filled set`() = runComposeUiTest {
        lateinit var state: FormState
        setContent { state = rememberFormState(signup) }
        waitForIdle()

        state.mark(name.id)
        state.mark(name.id, hasValue = false)
        waitForIdle()
        assertEquals(0f, state.percentComplete)
    }

    @Test
    fun `clear empties the filled set`() = runComposeUiTest {
        lateinit var state: FormState
        setContent { state = rememberFormState(signup) }
        waitForIdle()

        state.mark(name.id)
        state.mark(email.id)
        state.clear()
        waitForIdle()
        assertEquals(0f, state.percentComplete)
        assertTrue(state.filled.isEmpty())
    }

    /**
     * The exact bug an early draft of [FormState] had: constructing a fresh [FormState] wrapping
     * a *copy* of the saved value on every recomposition, rather than the live [rememberSaveable]
     * [androidx.compose.runtime.MutableState] itself, so [FormState.mark] would write to a
     * throwaway state nothing ever read back. Recomposing here (via marking a field, which
     * triggers one) and reading the state afterward is what that bug would have failed.
     */
    @Test
    fun `state survives recomposition rather than resetting to its initial value`() = runComposeUiTest {
        var recomposeTrigger by mutableStateOf(0)
        lateinit var state: FormState
        setContent {
            recomposeTrigger
            state = rememberFormState(signup)
        }
        waitForIdle()

        state.mark(name.id)
        recomposeTrigger += 1
        waitForIdle()

        assertEquals(0.5f, state.percentComplete, "progress should survive an unrelated recomposition")
    }
}
