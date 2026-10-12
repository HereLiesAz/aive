package com.hereliesaz.conveyance.compose

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.hereliesaz.conveyance.Act
import com.hereliesaz.conveyance.ActState
import com.hereliesaz.conveyance.ElementId
import com.hereliesaz.conveyance.Gate
import com.hereliesaz.conveyance.Practice
import com.hereliesaz.conveyance.Refusal
import com.hereliesaz.conveyance.SubjectId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.awaitCancellation

@OptIn(ExperimentalTestApi::class)
class OfferTest {

    private val invoice = SubjectId("invoice.41")
    private val avatar = ElementId("recipient.avatar")
    private val field = ElementId("recipient.field")

    /**
     * The framework's entire replacement for the disabled state, end to end.
     *
     * A greyed-out button announces a rule and abandons the person. This asserts the opposite
     * happens: the gate is off-screen, engaging the blocked act brings it into view, and it is the
     * thing being emphasised when they arrive.
     */
    @Test
    fun `a blocked act escorts the person to a gate below the fold`() = runComposeUiTest {
        val registry = ElementRegistry()
        val practice = Practice()
        val gate = Gate("recipient.chosen", livesAt = field) { false }
        val send = Act.send("invoice.send", invoice, avatar, requires = listOf(gate))
        var scope: ActScope? = null

        setContent {
            CompositionLocalProvider(
                LocalElements provides registry,
                LocalPractice provides practice,
            ) {
                Column(Modifier.height(120.dp).verticalScroll(rememberScrollState())) {
                    Offer(send) {
                        scope = this
                        Box(Modifier.size(40.dp))
                    }
                    Spacer(Modifier.height(400.dp))
                    Box(Modifier.size(40.dp).element(field))
                }
            }
        }
        waitForIdle()

        assertIs<ActState.Blocked>(scope!!.state, "An unmet gate must read as Blocked, not disabled.")
        assertFalse(registry.visible(field), "Precondition: the gate starts below the fold.")

        runOnUiThread { scope!!.engage() }
        waitForIdle()

        assertTrue(registry.visible(field), "The escort must bring the gate into view.")
        assertEquals(field, registry.articulating, "The gate must be what is emphasised on arrival.")
    }

    @Test
    fun `a blocked act is never recorded as practice`() = runComposeUiTest {
        val registry = ElementRegistry()
        val practice = Practice()
        val gate = Gate("never", livesAt = field) { false }
        val send = Act.send("invoice.send", invoice, avatar, requires = listOf(gate))
        var scope: ActScope? = null

        setContent {
            CompositionLocalProvider(LocalElements provides registry, LocalPractice provides practice) {
                Column {
                    Offer(send) { scope = this; Box(Modifier.size(40.dp)) }
                    Box(Modifier.size(40.dp).element(field))
                }
            }
        }
        waitForIdle()
        runOnUiThread { scope!!.engage() }
        waitForIdle()

        assertEquals(0, practice.count(send.id), "Reaching for a thing is not doing it.")
        assertTrue(scope!!.owesTell, "An act never performed still owes its Tell.")
    }

    /** A gate satisfied elsewhere unblocks this control with nothing for the person to dismiss. */
    @Test
    fun `satisfying a gate elsewhere quietly unblocks the act`() = runComposeUiTest {
        val registry = ElementRegistry()
        val chosen = mutableStateOf(false)
        val gate = Gate("recipient.chosen", livesAt = field) { chosen.value }
        val send = Act.send("invoice.send", invoice, avatar, requires = listOf(gate))
        var scope: ActScope? = null

        setContent {
            CompositionLocalProvider(LocalElements provides registry, LocalPractice provides Practice()) {
                Offer(send) { scope = this; Box(Modifier.size(40.dp)) }
            }
        }
        waitForIdle()
        assertIs<ActState.Blocked>(scope!!.state)

        chosen.value = true
        waitForIdle()

        assertEquals(ActState.Ready, scope!!.state, "The world changed; the control should just be live.")
    }

    @Test
    fun `an engaged act settles and earns its practice`() = runComposeUiTest {
        val registry = ElementRegistry()
        val practice = Practice()
        val send = Act.send("invoice.send", invoice, avatar)
        var scope: ActScope? = null

        setContent {
            CompositionLocalProvider(LocalElements provides registry, LocalPractice provides practice) {
                Offer(send) { scope = this; Box(Modifier.size(40.dp)) }
            }
        }
        waitForIdle()
        assertTrue(scope!!.owesTell, "Before first use, the control owes its half-rep.")

        runOnUiThread { scope!!.engage() }
        waitForIdle()

        assertEquals(ActState.Settled, scope!!.state)
        assertEquals(1, practice.count(send.id))
        assertFalse(scope!!.owesTell, "Once done, the Tell is spent and must not repeat.")
    }

    /**
     * The other half of the Escort: the emphasis is supposed to stop once the person has acted on
     * the thing it carried them to, not linger forever as a stale pointer nobody clears.
     */
    @Test
    fun `acting on the escorted-to element settles its own articulation`() = runComposeUiTest {
        val registry = ElementRegistry()
        val practice = Practice()
        val chosen = mutableStateOf(false)
        val gate = Gate("recipient.chosen", livesAt = field) { chosen.value }
        val send = Act.send("invoice.send", invoice, avatar, requires = listOf(gate))
        val choose = Act.alter("recipient.choose", invoice, "recipient", field) {
            chosen.value = true
            com.hereliesaz.conveyance.Outcome.Done
        }
        var reaching: ActScope? = null
        var choosing: ActScope? = null

        setContent {
            CompositionLocalProvider(LocalElements provides registry, LocalPractice provides practice) {
                Column {
                    Offer(send) { reaching = this; Box(Modifier.size(40.dp)) }
                    Offer(choose, element = field) { choosing = this; Box(Modifier.size(40.dp)) }
                }
            }
        }
        waitForIdle()

        runOnUiThread { requireNotNull(reaching).engage() }
        waitForIdle()
        assertEquals(field, registry.articulating, "The escort should have landed here.")

        runOnUiThread { requireNotNull(choosing).engage() }
        waitForIdle()

        assertEquals(
            null,
            registry.articulating,
            "Acting on the escorted-to element should settle its own emphasis.",
        )
    }

    /**
     * The same act legitimately offered from two elements at once -- a list row and the detail
     * place growing out of it, mid-transition, the identical reason element addresses are
     * tenanted rather than owned ([ElementRegistry]'s own `tenancy`). Before this was tenanted the
     * same way, [ElementRegistry.offer]/[ElementRegistry.withdraw] shared one flat entry per
     * [com.hereliesaz.conveyance.ActId]: the second `Offer` to mount silently overwrote the first's
     * claim, and *either one* leaving the composition erased the entry outright -- even while the
     * other was still mounted and still offering it.
     */
    @Test
    fun `one of two offers on the same act leaving does not erase the surviving one`() = runComposeUiTest {
        val registry = ElementRegistry()
        val send = Act.send("invoice.send", invoice, avatar)
        val rowElement = ElementId("row.invoice.41")
        val detailElement = ElementId("detail.invoice.41")
        var rowPresent by mutableStateOf(true)

        setContent {
            CompositionLocalProvider(LocalElements provides registry) {
                Column {
                    if (rowPresent) Offer(send, element = rowElement) { Box(Modifier.size(40.dp)) }
                    Offer(send, element = detailElement) { Box(Modifier.size(40.dp)) }
                }
            }
        }
        waitForIdle()
        assertNotNull(
            registry.offering(detailElement),
            "The detail's own Offer should be registered before the row ever leaves.",
        )

        rowPresent = false
        waitForIdle()

        assertNotNull(
            registry.offering(detailElement),
            "The row's Offer left the composition, but the detail's Offer for the identical act " +
                "is still mounted; its registration must survive the row's departure.",
        )
        assertEquals(1, registry.census().acts, "One act, still offered once, however many claimed it.")
    }

    /**
     * [com.hereliesaz.conveyance.Job.Interrupt]'s live binding, end to end: an act genuinely
     * in flight, stopped from outside, reporting through the exact vocabulary
     * [com.hereliesaz.conveyance.Act.engage] already had for it -- [Refusal.Interrupted], "it
     * stopped part-way, through no decision of anyone's" -- rather than hanging forever or dying
     * silently.
     */
    @Test
    fun `interrupting a yielding act settles it as refused and interrupted`() = runComposeUiTest {
        val registry = ElementRegistry()
        val practice = Practice()
        // Suspends forever until cancelled -- the only way this ever returns is interruption,
        // which is exactly what isolates the path this test exists to prove.
        val send = Act.send("invoice.send", invoice, avatar) { awaitCancellation() }
        var scope: ActScope? = null

        setContent {
            CompositionLocalProvider(LocalElements provides registry, LocalPractice provides practice) {
                Offer(send) { scope = this; Box(Modifier.size(40.dp)) }
            }
        }
        waitForIdle()

        runOnUiThread { requireNotNull(scope).engage() }
        waitForIdle()
        assertIs<ActState.Yielding>(requireNotNull(scope).state, "Should be genuinely in flight.")

        runOnUiThread { requireNotNull(scope).interrupt() }
        waitForIdle()

        val terminal = assertIs<ActState.Refused>(
            requireNotNull(scope).state,
            "Interrupting in-flight work should settle it as refused, not leave it hanging.",
        )
        assertEquals(Refusal.Interrupted, terminal.refusal)
        assertTrue(terminal.retryable, "An interruption is nobody's decision, so it's retryable.")
    }

    /** Interrupting something that was never started, or has already finished, does nothing. */
    @Test
    fun `interrupting an act with nothing in flight is a no-op`() = runComposeUiTest {
        val registry = ElementRegistry()
        val practice = Practice()
        val send = Act.send("invoice.send", invoice, avatar)
        var scope: ActScope? = null

        setContent {
            CompositionLocalProvider(LocalElements provides registry, LocalPractice provides practice) {
                Offer(send) { scope = this; Box(Modifier.size(40.dp)) }
            }
        }
        waitForIdle()

        runOnUiThread { requireNotNull(scope).interrupt() }
        waitForIdle()

        assertEquals(
            ActState.Ready,
            requireNotNull(scope).state,
            "Nothing was running, so interrupting it must not conjure a Refused state from nowhere.",
        )
    }
}
