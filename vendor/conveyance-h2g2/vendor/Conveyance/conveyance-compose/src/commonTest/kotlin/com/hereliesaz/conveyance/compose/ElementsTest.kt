package com.hereliesaz.conveyance.compose

import androidx.compose.animation.core.SpringSpec
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.hereliesaz.conveyance.ElementId
import com.hereliesaz.conveyance.Weight
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The registry is the load-bearing piece of the binding: an escort that cannot find its gate, or a
 * morph that aims at where a row used to be, are the two ways this framework fails visibly. These
 * tests are the ones that would have caught either.
 */
@OptIn(ExperimentalTestApi::class)
class ElementsTest {

    private val field = ElementId("recipient.field")

    @Test
    fun `an element takes an address and gives it up when it leaves`() = runComposeUiTest {
        val registry = ElementRegistry()
        var present by mutableStateOf(true)

        setContent {
            CompositionLocalProvider(LocalElements provides registry) {
                if (present) Box(Modifier.size(40.dp).element(field))
            }
        }
        waitForIdle()
        assertTrue(registry.resolves(field), "A composed element must be reachable by address.")

        present = false
        waitForIdle()
        assertFalse(
            registry.resolves(field),
            "An address must not outlive its element, or an escort travels to a ghost.",
        )
    }

    @Test
    fun `bounds follow the element when it moves`() = runComposeUiTest {
        val registry = ElementRegistry()
        var offset by mutableStateOf(0.dp)

        setContent {
            CompositionLocalProvider(LocalElements provides registry) {
                Box(Modifier.padding(top = offset).size(40.dp).element(field))
            }
        }
        waitForIdle()
        val before = assertNotNull(registry.bounds(field))

        offset = 60.dp
        waitForIdle()
        val after = assertNotNull(registry.bounds(field))

        assertTrue(after.top > before.top, "Bounds went stale when the element moved.")
    }

    /**
     * The case that matters most in practice. A blocked act's gate is very often a field further
     * down a form, and the escort has to travel to where it is *now*, not where it was laid out.
     */
    @Test
    fun `bounds follow the element through a scroll`() = runComposeUiTest {
        val registry = ElementRegistry()
        val scroll = ScrollState(0)

        setContent {
            CompositionLocalProvider(LocalElements provides registry) {
                Column(Modifier.height(120.dp).verticalScroll(scroll)) {
                    Spacer(Modifier.height(200.dp))
                    Box(Modifier.size(40.dp).element(field))
                    Spacer(Modifier.height(200.dp))
                }
            }
        }
        waitForIdle()
        val before = assertNotNull(registry.bounds(field))
        assertTrue(before.height > 0f, "An off-screen element still has a real position.")
        assertFalse(registry.visible(field), "It is below the fold and must not be reported visible.")

        scroll.dispatchRawDelta(150f)
        waitForIdle()
        val after = assertNotNull(registry.bounds(field))

        assertTrue(after.top < before.top, "Bounds did not track the scroll; an escort would miss.")
        assertTrue(registry.visible(field), "It is on screen now and must be reported visible.")
    }

    @Test
    fun `two elements hold distinct addresses`() = runComposeUiTest {
        val registry = ElementRegistry()
        val other = ElementId("recipient.avatar")

        setContent {
            CompositionLocalProvider(LocalElements provides registry) {
                Column {
                    Box(Modifier.size(40.dp).element(field))
                    Box(Modifier.size(40.dp).element(other))
                }
            }
        }
        waitForIdle()

        val a = assertNotNull(registry.bounds(field))
        val b = assertNotNull(registry.bounds(other))
        assertTrue(b.top >= a.bottom, "Distinct addresses resolved to overlapping places.")
        assertTrue(registry.placed.containsAll(setOf(field, other)))
    }

    /**
     * Product code cannot name a spring, so this is the only place the three weights can be shown
     * to actually differ in the hand rather than merely in an enum.
     */
    @Test
    fun `heavier weights are slacker and never overshoot`() {
        val light = Motion.spec<Float>(Weight.Light) as SpringSpec
        val medium = Motion.spec<Float>(Weight.Medium) as SpringSpec
        val heavy = Motion.spec<Float>(Weight.Heavy) as SpringSpec

        assertTrue(light.stiffness > medium.stiffness)
        assertTrue(medium.stiffness > heavy.stiffness)
        assertTrue(light.dampingRatio < medium.dampingRatio, "Light should feel eager.")

        assertTrue(Motion.overshoots(Weight.Light))
        assertFalse(Motion.overshoots(Weight.Heavy), "Consequence must not bounce.")
    }
}
