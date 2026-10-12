package com.hereliesaz.conveyance.compose

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.hereliesaz.conveyance.ElementId
import com.hereliesaz.conveyance.Place
import com.hereliesaz.conveyance.PlaceId
import com.hereliesaz.conveyance.Practice
import com.hereliesaz.conveyance.SubjectId
import com.hereliesaz.conveyance.Weight
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Whether a place actually grows out of the thing that was touched.
 *
 * Enter and Return were declared in the grammar long before anything rendered them, which meant Law
 * 2 — continuity — had a name and no code. These tests are the difference: they measure the moving
 * place's own bounds at both ends of the journey, so "it grows from the element" is a number rather
 * than a claim in a comment.
 */
@OptIn(ExperimentalTestApi::class)
class PlacesTest {

    private val card = ElementId("card")
    private val inside = ElementId("inside")
    private val detail = Place.from("detail", origin = card, subject = SubjectId("photo.1"))

    /** Everything a test needs to reach into a running Places host. */
    private class Reach {
        var places: PlacesState? = null
        var scope: CoroutineScope? = null

        fun state() = requireNotNull(places) { "The host did not publish its state." }
        fun run(block: suspend () -> Unit) = requireNotNull(scope).launch { block() }
    }

    /**
     * A host with one card in a corner, and a detail place that grows out of it.
     *
     * [cornered] chooses which corner the card sits in, so a test can move the ground while the
     * person is away and see where the way back leads.
     */
    private fun ComposeUiTest.host(reach: Reach, cornered: () -> Alignment): ElementRegistry {
        val registry = ElementRegistry()
        setContent {
            CompositionLocalProvider(
                LocalElements provides registry,
                LocalPractice provides Practice(),
                LocalGhosts provides Ghosts(),
            ) {
                reach.scope = rememberCoroutineScope()
                Box(Modifier.size(400.dp)) {
                    Places(root = Place.root("home")) { place ->
                        reach.places = LocalPlaces.current
                        if (place.isRoot) {
                            Box(Modifier.fillMaxSize()) {
                                Box(Modifier.align(cornered()).size(80.dp).element(card))
                            }
                        } else {
                            Box(Modifier.fillMaxSize().element(inside))
                        }
                    }
                }
            }
        }
        return registry
    }

    private fun ElementRegistry.rect(id: ElementId): Rect =
        assertNotNull(bounds(id), "$id is not on screen.")

    /**
     * The claim the framework opens with: a person never arrives somewhere from nowhere.
     *
     * Sampled one frame in, the new place is the size of the card that was touched. That is the
     * whole of it — the card did not disappear and get replaced by a screen, it became the screen.
     */
    @Test
    fun `a place grows out of the element it was entered from`() = runComposeUiTest {
        val reach = Reach()
        val screen = host(reach) { Alignment.BottomEnd }
        mainClock.autoAdvance = false
        waitForIdle()

        val origin = screen.rect(card)
        runOnUiThread { reach.run { reach.state().enter(detail, Weight.Heavy) } }
        mainClock.advanceTimeBy(16)
        waitForIdle()

        val born = screen.rect(inside)
        assertTrue(
            abs(born.width - origin.width) < origin.width * 0.35f,
            "A place should be born the size of what was touched, not the size of the window " +
                "(${born.width} vs ${origin.width}).",
        )
        assertTrue(
            born.center.distanceTo(origin.center) < origin.width,
            "A place should be born where the person's finger was.",
        )

        mainClock.advanceTimeBy(8_000)
        waitForIdle()

        val arrived = screen.rect(inside)
        assertTrue(
            arrived.width > born.width * 3f,
            "Having arrived, the place should fill the window (${arrived.width}).",
        )
    }

    /**
     * The half that is easy to get wrong: a place shrinks into where its element *now* is.
     *
     * While the person was away the ground moved — the card is in the opposite corner. A framework
     * that captured the origin on the way out would fly them back to a corner with nothing in it,
     * which is worse than no motion at all, because it teaches a false map.
     */
    @Test
    fun `returning shrinks into where the element now sits`() = runComposeUiTest {
        val reach = Reach()
        val moved = mutableStateOf(false)
        val screen = host(reach) { if (moved.value) Alignment.TopStart else Alignment.BottomEnd }
        mainClock.autoAdvance = false
        waitForIdle()

        val before = screen.rect(card).center
        runOnUiThread { reach.run { reach.state().enter(detail, Weight.Heavy) } }
        mainClock.advanceTimeBy(8_000)
        waitForIdle()
        assertEquals(2, reach.state().depth, "Entering should put the person somewhere new.")

        // The ground moves while they are away.
        moved.value = true
        mainClock.advanceTimeBy(16)
        waitForIdle()
        val after = screen.rect(card).center
        assertTrue(after.distanceTo(before) > 100f, "The test needs the card to have actually moved.")

        runOnUiThread { reach.run { reach.state().back(Weight.Heavy) } }

        // Follow it all the way out and keep the last frame it was still visible on, rather than
        // guessing at a timestamp. Where a motion *ends up* is the claim; how long the spring takes
        // to get there is a tuning decision this test has no business asserting.
        var receding: Offset? = null
        repeat(80) {
            mainClock.advanceTimeBy(25)
            waitForIdle()
            screen.bounds(inside)?.let { rect -> receding = rect.center }
        }
        val landed = assertNotNull(receding, "The place should have been drawn on its way out.")
        assertTrue(
            landed.distanceTo(after) < landed.distanceTo(before),
            "The way out should lead to where the card is now, not where it used to be.",
        )
        assertEquals(1, reach.state().depth, "Returning should put the person back where they were.")
    }

    /**
     * The status vocabulary a gate would read.
     *
     * A product wiring `Gate("in detail") { places.isActive(detailId) }` needs this to be true the
     * instant the stack changes -- before any animation has settled, because a gate is a
     * precondition, not a report on where the motion currently is.
     */
    @Test
    fun `active names every place on the stack, the instant it changes`() = runComposeUiTest {
        val reach = Reach()
        host(reach) { Alignment.BottomEnd }
        waitForIdle()

        assertEquals(setOf(PlaceId("home")), reach.state().active)
        assertTrue(reach.state().isActive(PlaceId("home")))
        assertFalse(reach.state().isActive(detail.id))

        runOnUiThread { reach.run { reach.state().enter(detail, Weight.Heavy) } }
        waitForIdle()

        assertEquals(setOf(PlaceId("home"), detail.id), reach.state().active)
        assertTrue(reach.state().isActive(detail.id), "Entered before the animation settles.")
    }

    /** There is nowhere behind the beginning, and the host says so rather than emptying the stack. */
    @Test
    fun `there is no way back from the root`() = runComposeUiTest {
        val reach = Reach()
        host(reach) { Alignment.BottomEnd }
        waitForIdle()

        var went = true
        runOnUiThread { reach.run { went = reach.state().back(Weight.Heavy) } }
        waitForIdle()

        assertFalse(went, "Backing out of the root should report that there was nowhere to go.")
        assertEquals(1, reach.state().depth, "The root must survive a back gesture.")
    }

    /**
     * The claim continuity actually rests on: a place surviving the transition it is having.
     *
     * The first version of [Places] rendered the moving place from two textually distinct
     * `Box { content(moving) } ` call sites -- one for mid-transition, one for settled -- chosen
     * by an `if`/`else`. Compose groups a branch by its source position, so the instant `extent`
     * crossed 1f and the branch flipped, the entire subtree the person had just arrived at was
     * disposed and rebuilt fresh: any `remember` inside it -- scroll position, focus, an
     * in-progress field -- reset at the exact moment Law 2 promises continuity. This counts how
     * many times a `remember { }` block inside the entered place actually runs its initializer; it
     * must run exactly once, however many frames the transition takes.
     */
    @Test
    fun `local state inside the entered place survives the settle transition`() = runComposeUiTest {
        var initializerRuns = 0
        val reach = Reach()
        val registry = ElementRegistry()
        setContent {
            CompositionLocalProvider(
                LocalElements provides registry,
                LocalPractice provides Practice(),
                LocalGhosts provides Ghosts(),
            ) {
                reach.scope = rememberCoroutineScope()
                Box(Modifier.size(400.dp)) {
                    Places(root = Place.root("home")) { place ->
                        reach.places = LocalPlaces.current
                        if (place.isRoot) {
                            Box(Modifier.size(80.dp).element(card))
                        } else {
                            remember { initializerRuns++ }
                        }
                    }
                }
            }
        }
        waitForIdle()
        mainClock.autoAdvance = false

        runOnUiThread { reach.run { reach.state().enter(detail, Weight.Heavy) } }
        // Drive well past the point the transition settles.
        repeat(200) {
            mainClock.advanceTimeBy(16)
            waitForIdle()
        }

        assertEquals(
            1,
            initializerRuns,
            "remember{} ran $initializerRuns times; the entered place's subtree was torn down " +
                "and rebuilt when the transition settled, rather than surviving it.",
        )
    }

    /**
     * A back gesture landing while a person's own tap is still mid-[PlacesState.enter] is not
     * exotic timing — a system back button and a UI tap are two independent input sources with no
     * shared clock. Both [PlacesState.enter] and [PlacesState.back] are `suspend` and both mutate
     * the same stack, [PlacesState.extent] and `receding` across several suspension points; with
     * nothing serializing them, an interleaved [PlacesState.back] and [PlacesState.enter] could
     * corrupt each other's motion. This launches both as close to simultaneously as the coroutine
     * scheduler allows and asserts the outcome is the one coherent, sequential result rather than
     * a corrupted depth.
     */
    @Test
    fun `a back gesture landing mid-enter does not corrupt the stack`() = runComposeUiTest {
        val reach = Reach()
        host(reach) { Alignment.BottomEnd }
        waitForIdle()
        mainClock.autoAdvance = false

        val second = Place.from("second", origin = card, subject = SubjectId("photo.2"))

        runOnUiThread { reach.run { reach.state().enter(detail, Weight.Heavy) } }
        repeat(80) { mainClock.advanceTimeBy(16); waitForIdle() }
        assertEquals(2, reach.state().depth, "Precondition: one place deep before the race.")

        // Launched back-to-back, before either has had a chance to run past its first suspension
        // point -- the scenario a Mutex exists to serialize rather than leave to scheduling luck.
        runOnUiThread {
            reach.run { reach.state().back(Weight.Heavy) }
            reach.run { reach.state().enter(second, Weight.Heavy) }
        }
        repeat(200) { mainClock.advanceTimeBy(16); waitForIdle() }

        assertEquals(
            2,
            reach.state().depth,
            "One place removed, one added: the net depth is unambiguous however they interleaved.",
        )
        assertEquals(
            second.id,
            reach.state().current.id,
            "back() must have finished -- home on top, receding cleared -- before enter() started " +
                "mutating the same stack, or this would land somewhere else entirely.",
        )
    }

    /**
     * The scenario `anchor()` and the whole tenancy rework exist for, and the one no other test
     * exercised: a detail place that shows the same subject as the element it grew out of, so both
     * legitimately answer to the identical [ElementId] -- the real shape of the Gallery demo, where
     * a thumbnail and its detail portrait both register `subjectElement(subject)`. Asking the
     * ordinary "where is this address" question here would have the geometry resolve to the moving
     * place's own bounds and chase its own tail; `anchor()` has to reach *underneath* it, to the
     * thumbnail, instead.
     */
    @Test
    fun `a place grows from the thumbnail underneath it, even when both share one address`() = runComposeUiTest {
        val shared = ElementId("subject:photo.1")
        val detailPlace = Place.from("detail", origin = shared, subject = SubjectId("photo.1"))
        val reach = Reach()
        var registry: ElementRegistry? = null

        setContent {
            val local = remember { ElementRegistry() }
            registry = local
            CompositionLocalProvider(
                LocalElements provides local,
                LocalPractice provides Practice(),
                LocalGhosts provides Ghosts(),
            ) {
                reach.scope = rememberCoroutineScope()
                Box(Modifier.size(400.dp)) {
                    Places(root = Place.root("tray")) { place ->
                        reach.places = LocalPlaces.current
                        if (place.isRoot) {
                            Box(Modifier.fillMaxSize()) {
                                Box(Modifier.align(Alignment.BottomEnd).size(80.dp).element(shared))
                            }
                        } else {
                            // The detail place answers to the SAME address as the thumbnail above.
                            Box(Modifier.fillMaxSize().element(shared))
                        }
                    }
                }
            }
        }
        mainClock.autoAdvance = false
        waitForIdle()

        val thumbnail = requireNotNull(registry).bounds(shared)!!
        runOnUiThread { reach.run { reach.state().enter(detailPlace, Weight.Heavy) } }
        mainClock.advanceTimeBy(16)
        waitForIdle()

        val born = requireNotNull(registry).bounds(shared)!!
        assertTrue(
            abs(born.width - thumbnail.width) < thumbnail.width * 0.35f,
            "The place should be born the size of the thumbnail underneath it, not chase its own " +
                "claim on the same address (thumbnail=${thumbnail.width}, born=${born.width}).",
        )

        // Advance well past the first frame, into the middle of the growth -- at extent 0 the
        // detail's own just-written bounds are numerically indistinguishable from the thumbnail's
        // (lerp at t=0 reproduces its input bit-for-bit), so only once extent has genuinely moved
        // does "the tenant underneath" and "the newest tenant" actually diverge and this become a
        // real test rather than a coincidence.
        repeat(10) { mainClock.advanceTimeBy(16); waitForIdle() }

        // By now the detail has composed and claimed `shared` too, so the address has two tenants
        // -- exactly the state anchor() exists to resolve correctly. Asked directly, it must still
        // answer with the stable thumbnail underneath, not the detail's own just-written bounds:
        // the newest tenant asking "where is my address" would otherwise be answered with itself.
        assertEquals(
            thumbnail,
            requireNotNull(registry).anchor(shared),
            "anchor() must resolve to the tenant underneath, not the newest one -- which, once the " +
                "detail has composed and moved on from the thumbnail's size, is its own claim on " +
                "the address it shares with the thumbnail.",
        )
    }

    private fun Offset.distanceTo(other: Offset): Float = (this - other).getDistance()
}
