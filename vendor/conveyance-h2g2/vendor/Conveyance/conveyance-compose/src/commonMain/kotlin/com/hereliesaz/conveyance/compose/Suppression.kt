package com.hereliesaz.conveyance.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch

/**
 * A window a host can hold open around an escort.
 *
 * A blocked act escorts the instant it is engaged — that is the whole of the framework's
 * replacement for a disabled control — but "instant" is wrong the moment a person's finger is
 * still on the glass doing something else: a drag in progress, a sheet still settling. Arriving
 * somewhere new out from under an active gesture is not carrying someone, it is snatching the
 * screen away from them.
 *
 * Nothing is cancelled. The escort still happens; it waits.
 */
class Suppression {
    internal val holds = mutableStateMapOf<Any, Pair<Long, () -> Boolean>>()

    internal fun register(owner: Any, settleMs: Long, active: () -> Boolean) {
        holds[owner] = settleMs to active
    }

    internal fun unregister(owner: Any) {
        holds.remove(owner)
    }
}

val LocalSuppression = staticCompositionLocalOf { Suppression() }

/** Whether anything currently registered says a gesture is in progress. A throwing predicate counts false. */
private fun anyHeld(suppression: Suppression): Boolean =
    suppression.holds.values.any { (_, predicate) -> runCatching { predicate() }.getOrDefault(false) }

/**
 * Hold escorts back while [active] is true, and for [settleMs] after it goes false.
 *
 * Applied to whatever is performing the gesture — a slider's drag handle, a sheet mid-animation —
 * not to the thing that might get escorted. Suppression is a property of the moment, not of any one
 * control, and more than one source may hold it open at once; the longest [settleMs] among them
 * governs how long the tail lasts.
 */
@Composable
fun Modifier.suppressEscort(settleMs: Long = 400L, active: () -> Boolean): Modifier {
    val suppression = LocalSuppression.current
    val owner = remember { Any() }
    val currentActive = rememberUpdatedState(active)
    DisposableEffect(suppression, owner) {
        suppression.register(owner, settleMs) { currentActive.value() }
        onDispose { suppression.unregister(owner) }
    }
    return this
}

/**
 * Whether an escort should hold off right now.
 *
 * Rising edge is immediate: the moment any registered predicate goes true, this is true. Falling
 * edge waits the largest registered settle window before flipping back, so a carry never begins
 * over the tail of a gesture that has only just finished — and a fresh suppression arriving mid-wait
 * re-holds immediately rather than letting the stale timer win.
 */
@Composable
internal fun rememberEscortSuppressed(suppression: Suppression): State<Boolean> {
    val suppressed = remember { mutableStateOf(anyHeld(suppression)) }
    LaunchedEffect(suppression) {
        val rawOf = { anyHeld(suppression) }
        val settleOf = { suppression.holds.values.maxOfOrNull { it.first }?.coerceAtLeast(0L) ?: 0L }
        var settleJob: Job? = null
        merge(
            snapshotFlow { rawOf() },
            flow { while (true) { emit(rawOf()); delay(REACTIVITY_POLL_MS) } },
        ).distinctUntilChanged().collect { on ->
            if (on) {
                settleJob?.cancel()
                settleJob = null
                suppressed.value = true
            } else {
                settleJob?.cancel()
                settleJob = launch { delay(settleOf()); suppressed.value = false }
            }
        }
    }
    return suppressed
}
