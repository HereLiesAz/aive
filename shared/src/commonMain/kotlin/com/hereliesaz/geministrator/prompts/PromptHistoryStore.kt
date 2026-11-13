package com.hereliesaz.geministrator.prompts

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.hereliesaz.geministrator.DurableUiState
import com.hereliesaz.geministrator.DurableUiStateStore
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * Remembered text, kept with the app's other interface drafts (`aive.ui-state.v1`): on this device only, never
 * exported in a setup file, never sent anywhere. One JSON list per [PromptKind], so the keys are fixed and short
 * (`aive.ui-state.v1.prompts.v1.swarm-draft` is the longest, 41 characters, far under the 80 a JVM Preferences
 * key may have). No key carries a project or hive id. Remembering is on until the keeper turns it off; turning it
 * off, or clearing, deletes every list.
 */
@OptIn(ExperimentalTime::class)
internal class PromptHistoryStore(
    private val store: DurableUiStateStore = DurableUiState.store,
    private val clock: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    /** Bumped on every change so composables that read the store recompose. */
    private var revision by mutableIntStateOf(0)

    /** Whether text is remembered and suggested at all. On by default. */
    fun enabled(): Boolean {
        revision
        return store.getBoolean(ENABLED_KEY) ?: true
    }

    /** Turning it off deletes everything already remembered. */
    fun setEnabled(on: Boolean) {
        store.putBoolean(ENABLED_KEY, on)
        if (!on) removeAll()
        revision++
    }

    fun entries(kind: PromptKind): List<PromptEntry> {
        revision
        return if (store.getBoolean(ENABLED_KEY) == false) emptyList() else PromptHistoryRules.decode(store.getString(keyOf(kind)))
    }

    /** Remembers [text] for [kind], if remembering is on and the text is fit to keep. */
    fun record(kind: PromptKind, text: String) {
        if (store.getBoolean(ENABLED_KEY) == false) return
        val before = PromptHistoryRules.decode(store.getString(keyOf(kind)))
        val after = PromptHistoryRules.record(before, text, clock())
        if (after == before) return
        store.putString(keyOf(kind), PromptHistoryRules.encode(after))
        revision++
    }

    /** Deletes every remembered text. The on/off choice stays. */
    fun clearAll() {
        removeAll()
        revision++
    }

    private fun removeAll() {
        PromptKind.entries.forEach { store.remove(keyOf(it)) }
    }

    companion object {
        const val ENABLED_KEY: String = "prompts.v1.enabled"

        fun keyOf(kind: PromptKind): String = "prompts.v1.${kind.key}"
    }
}

/** The app's one remembered-text store. */
internal object PromptHistory {
    val store: PromptHistoryStore by lazy { PromptHistoryStore() }
}

/** What a screen needs of one kind's history: the entries to suggest from and a way to remember a launch. */
internal class PromptHistoryHandle(private val kind: PromptKind, private val store: PromptHistoryStore) {
    val enabled: Boolean get() = store.enabled()

    /** Newest first; empty when remembering is off. */
    val entries: List<PromptEntry> get() = store.entries(kind)

    /** Call when the text was used (a launch that went through, a save), never on a keystroke or a failed launch. */
    fun record(text: String) = store.record(kind, text)
}

@Composable
internal fun rememberPromptHistory(kind: PromptKind): PromptHistoryHandle =
    remember(kind) { PromptHistoryHandle(kind, PromptHistory.store) }
