package com.hereliesaz.conveyance.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import com.hereliesaz.conveyance.ElementId
import com.hereliesaz.conveyance.Form

/**
 * A [Form]'s live fill state -- which [com.hereliesaz.conveyance.FormField]s currently hold a
 * value, from any source: a person's own typing, a platform autofill, or a draft this same state
 * is restoring right now. [Form] itself can't hold this: it's the same fixed value for every
 * person who ever fills it out, while this is one person's progress through it.
 *
 * Wraps the [MutableState] [rememberFormState] gets from `rememberSaveable` rather than copying
 * its value into a state of its own -- [mark] has to write through to the exact instance that
 * gets saved, or "form recovery" would restore whatever the form looked like when this state was
 * first remembered and silently ignore everything filled in since.
 *
 * This only ever persists *that* a field is filled, never the value itself -- deliberately, since
 * a value's own storage is the host's field's problem, not this class's (see [Form]'s own KDoc).
 * That makes [mark] the one place a host can get recovery quietly wrong: call it once, on a
 * one-shot event like focus loss, and a field whose own value failed to restore (non-saveable
 * state, a host bug) is still reported filled here after recovery, with nothing visibly there.
 * The safe pattern is to call [mark] reactively off the field's own *current* value --
 * `LaunchedEffect(value) { formState.mark(field.id, value.isNotBlank()) }` -- so a value that
 * comes back empty after restore re-derives `filled = false` on the very next composition, rather
 * than trusting a boolean that was true before the process died.
 */
class FormState internal constructor(private val form: Form, private val filledState: MutableState<Set<ElementId>>) {
    val filled: Set<ElementId> get() = filledState.value

    val percentComplete: Float get() = form.percentComplete(filled)
    val isComplete: Boolean get() = form.isComplete(filled)

    /** Call this reactively off the field's own current value -- see this class's own KDoc for why a one-shot call is unsafe across recovery. */
    fun mark(field: ElementId, hasValue: Boolean = true) {
        filledState.value = if (hasValue) filledState.value + field else filledState.value - field
    }

    fun clear() {
        filledState.value = emptySet()
    }
}

/**
 * Remembers a [FormState] for [form], recovered automatically across whatever this platform
 * already recovers a plain [rememberSaveable] value across -- a configuration change, a process
 * death, a closed and reopened tab. That is the whole of what "form recovery" needs to mean:
 * the filled set is the only thing that was ever mutable, so restoring it restores everything a
 * person would recognise as their progress. No separate draft-persistence mechanism is layered on
 * top, because one already exists and reinventing it here would just be a second place for the
 * two copies to disagree.
 */
@Composable
fun rememberFormState(form: Form): FormState {
    val filledState = rememberSaveable(form.id.value, saver = filledFieldsSaver) {
        mutableStateOf(emptySet())
    }
    return remember(form, filledState) { FormState(form, filledState) }
}

private val filledFieldsSaver: Saver<MutableState<Set<ElementId>>, List<String>> =
    Saver(
        save = { it.value.map(ElementId::value) },
        restore = { saved -> mutableStateOf(saved.map(::ElementId).toSet()) },
    )
