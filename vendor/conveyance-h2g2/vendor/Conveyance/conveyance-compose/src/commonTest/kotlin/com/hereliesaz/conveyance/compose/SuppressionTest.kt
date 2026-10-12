package com.hereliesaz.conveyance.compose

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.hereliesaz.conveyance.Act
import com.hereliesaz.conveyance.ElementId
import com.hereliesaz.conveyance.Gate
import com.hereliesaz.conveyance.Practice
import com.hereliesaz.conveyance.SubjectId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * An escort that would otherwise fire the instant a blocked act is engaged, held back while a
 * gesture is in progress elsewhere on the surface.
 *
 * The resistance at the point of contact is not suppressed -- that is the person feeling their own
 * touch refused, which should never wait on anything. Only the carry waits, because arriving
 * somewhere new out from under an active drag is not being escorted, it is having the ground pulled
 * out from under you.
 */
@OptIn(ExperimentalTestApi::class)
class SuppressionTest {

    private val avatar = ElementId("recipient.avatar")
    private val field = ElementId("recipient.field")

    @Test
    fun `an escort holds while a registered gesture is active, and carries once it ends`() = runComposeUiTest {
        val registry = ElementRegistry()
        val suppression = Suppression()
        val dragging = mutableStateOf(true)
        val gate = Gate("recipient.chosen", livesAt = field) { false }
        val send = Act.send("invoice.send", SubjectId("invoice.41"), avatar, requires = listOf(gate))
        var scope: ActScope? = null

        setContent {
            CompositionLocalProvider(
                LocalElements provides registry,
                LocalPractice provides Practice(),
                LocalSuppression provides suppression,
            ) {
                Box(Modifier.size(40.dp).suppressEscort(settleMs = 200L) { dragging.value })
                Offer(send) { scope = this; Box(Modifier.size(40.dp)) }
                Box(Modifier.size(40.dp).element(field))
            }
        }
        waitForIdle()

        runOnUiThread { requireNotNull(scope).engage() }
        waitForIdle()

        assertNull(registry.articulating, "The carry must not begin while the gesture is active.")

        // Still nothing, well past the point an unsuppressed escort would have long since landed.
        mainClock.advanceTimeBy(600L)
        waitForIdle()
        assertNull(registry.articulating, "Held, not merely delayed by one frame.")

        dragging.value = false
        // Short of the 200ms settle window -- if the carry ignored the settle window entirely
        // and fired the instant the gesture ended, this is exactly where it would be caught.
        mainClock.advanceTimeBy(80L)
        waitForIdle()
        assertNull(registry.articulating, "The gesture just ended; the settle window has not passed yet.")

        mainClock.advanceTimeBy(1_000L)
        waitForIdle()

        assertEquals(field, registry.articulating, "Once the gesture ends and the settle window passes.")
    }

    @Test
    fun `with nothing suppressing, the escort fires exactly as before`() = runComposeUiTest {
        val registry = ElementRegistry()
        val gate = Gate("recipient.chosen", livesAt = field) { false }
        val send = Act.send("invoice.send", SubjectId("invoice.41"), avatar, requires = listOf(gate))
        var scope: ActScope? = null

        setContent {
            CompositionLocalProvider(LocalElements provides registry, LocalPractice provides Practice()) {
                Offer(send) { scope = this; Box(Modifier.size(40.dp)) }
                Box(Modifier.size(40.dp).element(field))
            }
        }
        waitForIdle()
        runOnUiThread { requireNotNull(scope).engage() }
        waitForIdle()

        assertEquals(field, registry.articulating)
    }
}
