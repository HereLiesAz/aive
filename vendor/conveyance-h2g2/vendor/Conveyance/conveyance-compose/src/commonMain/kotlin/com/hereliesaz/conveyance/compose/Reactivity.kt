package com.hereliesaz.conveyance.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.merge

/**
 * How often a value that might be backed by something outside the snapshot system is re-read.
 *
 * A [com.hereliesaz.conveyance.Gate] condition is `() -> Boolean`, deliberately unconstrained -- it
 * can read a plain `var`, a `StateFlow.value`, anything. Most of the time it reads Compose snapshot
 * state and needs no polling at all; this exists for the minority of cases that don't, so a gate
 * never silently freezes because the world it is watching has no way to tell Compose it changed.
 */
internal const val REACTIVITY_POLL_MS = 300L

/**
 * Keeps [read] current, however the thing it reads is backed.
 *
 * Reading a value directly in a composable's body subscribes to snapshot state -- and only
 * snapshot state. A predicate backed by a plain `var` or a `StateFlow.value` changes with nothing
 * to tell Compose to look again, and this framework accepts exactly that kind of unconstrained
 * `() -> Boolean` from a caller in more than one place. This merges an immediate
 * snapshot-triggered re-read with a slow poll, so both sources of truth reactivate: fast when
 * Compose can see the change, and within one poll interval when it cannot.
 */
@Composable
internal fun <T> rememberLive(key: Any?, read: () -> T): State<T> {
    val value = remember(key) { mutableStateOf(read()) }
    val current = rememberUpdatedState(read)
    LaunchedEffect(key) {
        val evaluate = { current.value() }
        merge(
            snapshotFlow { evaluate() },
            flow { while (true) { emit(evaluate()); delay(REACTIVITY_POLL_MS) } },
        ).distinctUntilChanged().collect { value.value = it }
    }
    return value
}
