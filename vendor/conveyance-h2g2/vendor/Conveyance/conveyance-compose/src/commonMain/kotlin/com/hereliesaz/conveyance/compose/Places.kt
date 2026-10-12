package com.hereliesaz.conveyance.compose

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.Constraints
import com.hereliesaz.conveyance.Place
import com.hereliesaz.conveyance.PlaceId
import com.hereliesaz.conveyance.Weight
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Where a person is, and how they got there.
 *
 * Enter and Return are the two verbs the framework declared and never rendered, which meant the law
 * they serve had no code behind it. They are also the two that matter most: continuity between
 * screens is the whole of Law 2, and a person who has been teleported has lost the map they were
 * building and needs words to rebuild it.
 *
 * So a place is not swapped in. It **grows out of the element that was touched**, and on the way
 * back it **shrinks into that element where it now sits** — not where it used to be, because the
 * screen underneath may have moved while the person was away. That is why the origin is resolved
 * from the registry at the moment of return rather than captured on the way out.
 */
@Stable
class PlacesState internal constructor(root: Place, private val scope: CoroutineScope) {

    internal val stack = mutableStateListOf(root)

    /** 0 = collapsed into the origin element, 1 = filling the window. */
    internal val extent = Animatable(1f)

    /** The place on its way out, still drawn until it has finished shrinking. */
    internal var receding by mutableStateOf<Place?>(null)
        private set

    val current: Place get() = receding ?: stack.last()

    val depth: Int get() = stack.size

    /**
     * The status vocabulary this stack publishes, for anything that needs to read where a person
     * is without composing this host itself.
     *
     * A [Gate] is just `() -> Boolean`, so a product can already write
     * `Gate("in detail", livesAt = ...) { places.isActive(detailId) }` without any new type — the
     * whole cost of this being a real capability rather than a wish was making the read reactive,
     * which it already is: [stack] is snapshot state, so a gate whose condition reads [isActive]
     * during composition reactivates the moment the stack changes, the same way any other gate
     * reactivates when the state it reads changes.
     */
    val active: Set<PlaceId> get() = stack.mapTo(mutableSetOf()) { it.id }

    /** Whether [id] is currently on the stack -- entered, even if not the place on top. */
    fun isActive(id: PlaceId): Boolean = stack.any { it.id == id }

    /** The place underneath, which stays put while the one above it moves. */
    internal val beneath: Place?
        get() = if (receding != null) stack.lastOrNull() else stack.getOrNull(stack.lastIndex - 1)

    /**
     * Serializes [enter] and [back].
     *
     * Both are `suspend` and both mutate [stack], [extent] and [receding] across several
     * suspension points. Nothing about a system back gesture guarantees it cannot land while a
     * person's own tap is still mid-[enter] -- and two interleaved writers driving one
     * [Animatable] and one stack is not a state a person could ever have asked for, just one nobody
     * ruled out. A journey is serialized the way an act already is: the second one waits, rather
     * than the two of them corrupting each other's motion.
     */
    private val inFlight = Mutex()

    internal suspend fun enter(place: Place, weight: Weight): Unit = inFlight.withLock {
        stack += place
        extent.snapTo(0f)
        extent.animateTo(1f, Motion.spec(weight))
    }

    /**
     * Begin entering on this state's own scope, rather than the caller's.
     *
     * The caller is the control that was just touched -- and the growth this starts is what, on
     * its very next recomposition, replaces that exact control with the place growing out of it.
     * A coroutine scoped to a composable that is about to leave composition as a direct
     * consequence of running is a coroutine scoped to be cancelled mid-flight: [enter] would push
     * the place, snap [extent] to zero, and then lose the coroutine driving it back to one before
     * a single further frame ran, leaving the arrival permanently the size of the thumbnail it
     * grew from. [Places] keeps this scope alive for exactly as long as the stack it drives does,
     * which is exactly the lifetime the animation needs and the touched control does not have.
     * [enter] itself stays a plain suspend function for anything that wants to await it directly.
     */
    internal fun enterAsync(place: Place, weight: Weight) {
        scope.launch { enter(place, weight) }
    }

    /**
     * Go back, and report whether there was anywhere to go.
     *
     * Returning false rather than throwing lets a host decide what a back gesture means at the
     * root, which is a question about the product rather than about this framework.
     */
    suspend fun back(weight: Weight = Weight.Heavy): Boolean = inFlight.withLock {
        if (stack.size <= 1) return@withLock false
        receding = stack.removeAt(stack.lastIndex)
        extent.snapTo(1f)
        extent.animateTo(0f, Motion.spec(weight))
        receding = null
        extent.snapTo(1f)
        true
    }
}

val LocalPlaces = staticCompositionLocalOf<PlacesState?> { null }

/**
 * Render wherever the person currently is.
 *
 * The place beneath is drawn first and stays still. The place in motion is drawn on top, laid out
 * between its origin element's bounds and the whole window, so entering and returning are the same
 * geometry run in opposite directions — which is exactly what the grammar says they are.
 */
@Composable
fun Places(
    root: Place,
    modifier: Modifier = Modifier,
    content: @Composable (Place) -> Unit,
) {
    val registry = LocalElements.current
    val scope = rememberCoroutineScope()
    val places = remember(root.id) { PlacesState(root, scope) }
    var window by remember { mutableStateOf(Rect.Zero) }

    CompositionLocalProvider(LocalPlaces provides places) {
        Box(
            modifier
                .fillMaxSize()
                .onGloballyPositioned { window = it.boundsInRoot() },
        ) {
            places.beneath?.let { content(it) }

            val moving = places.current
            // The claim *underneath* the moving place, never the moving place's own. A detail
            // place that shows the same subject legitimately answers to the same address, and
            // asking the ordinary question would have the geometry chasing itself.
            val origin = moving.origin?.let { registry.anchor(it) }
            val extent = places.extent.value

            // One call site for content(moving), always -- the branch decides which Modifier to
            // hand it, never which composable to call. The first version branched on an if/else
            // around two textually distinct `Box { content(moving) }` calls, and Compose groups a
            // branch by its source position: the instant extent crossed 1f the whole subtree the
            // person had just arrived at was disposed under the abandoned branch and rebuilt fresh
            // under the other one, silently resetting any local state inside it (scroll position,
            // focus, an in-progress field) at the exact moment Law 2 promises continuity. A
            // Modifier is just a value; swapping which one a single call site receives does not
            // restructure the composition, so the subtree is never torn down.
            val motion = if (origin == null || extent >= 1f || window.isEmpty) {
                // Nothing to grow from, or already arrived. A root place is always simply here.
                Modifier.fillMaxSize()
            } else {
                Modifier.layout { measurable, _ ->
                    val left = lerp(origin.left, window.left, extent)
                    val top = lerp(origin.top, window.top, extent)
                    val width = lerp(origin.width, window.width, extent)
                    val height = lerp(origin.height, window.height, extent)
                    val placeable = measurable.measure(
                        Constraints.fixed(
                            width.toInt().coerceAtLeast(1),
                            height.toInt().coerceAtLeast(1),
                        ),
                    )
                    layout(placeable.width, placeable.height) {
                        placeable.place(left.toInt(), top.toInt())
                    }
                }
            }
            Box(motion) { content(moving) }
        }
    }
}

private fun lerp(from: Float, to: Float, t: Float): Float = from + (to - from) * t
