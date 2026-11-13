package com.hereliesaz.geministrator.prompts

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.window.PopupProperties

/** What [PromptSuggestBox] hands the field it wraps: wire these into the existing text field unchanged. */
internal class PromptFieldState(
    val value: TextFieldValue,
    val onValueChange: (TextFieldValue) -> Unit,
    val modifier: Modifier,
)

/**
 * Wraps an existing text field so it fills itself in and offers what was typed before.
 *
 * - **Seed.** An empty field takes [seed] once, with the text selected so typing replaces it. The seed reaches the
 *   caller through [onSeeded], not [onValueChange]: a caller that keeps a durable draft holds it apart from the
 *   draft, so an unedited seed is never written as one. [value] is what the field currently holds (the draft, or
 *   the seed while the draft is empty).
 * - **Menu.** On focus, [choices] (context-built, e.g. a retry line) and remembered entries that match what was
 *   typed, in the dropdown the repository search uses. Picking one fills the field.
 * - **Never for a secret.** With [secret], with remembering off, nothing is seeded or offered.
 *
 * [onValueChange] is called for a keeper's edits and picked choices only. The caller records the text with
 * [PromptHistoryHandle.record] when it is used. [resetKey] starts the box over for a different record.
 */
@Composable
internal fun PromptSuggestBox(
    kind: PromptKind,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    seed: String? = null,
    onSeeded: (String) -> Unit = {},
    choices: List<PromptChoice> = emptyList(),
    secret: Boolean = false,
    resetKey: Any? = null,
    entryFilter: (PromptEntry) -> Boolean = { true },
    /** False when the field already has a menu of its own, which then offers the remembered entries itself. */
    showMenu: Boolean = true,
    content: @Composable (PromptFieldState) -> Unit,
) {
    val store = PromptHistory.store
    val offering = PromptHistoryRules.shouldOffer(secret = secret, enabled = store.enabled())
    val entries = if (offering) store.entries(kind).filter(entryFilter) else emptyList()

    var shown by remember(resetKey) { mutableStateOf(TextFieldValue(value)) }
    var seedSettled by remember(resetKey) { mutableStateOf(false) }
    var edited by remember(resetKey) { mutableStateOf(false) }
    var focused by remember(resetKey) { mutableStateOf(false) }
    var menuOpen by remember(resetKey) { mutableStateOf(false) }

    // Seed once, only into a field nobody has typed in.
    LaunchedEffect(resetKey, offering, seed) {
        if (!seedSettled && offering && !edited && value.isBlank() && !seed.isNullOrBlank()) {
            seedSettled = true
            shown = TextFieldValue(seed, TextRange(0, seed.length))
            onSeeded(seed)
        }
    }
    // The caller changed the value itself (a cleared field after a launch, a clipped edit): follow it.
    LaunchedEffect(value, shown.text) {
        if (value != shown.text) shown = TextFieldValue(value, TextRange(value.length))
    }

    val typed = if (edited) shown.text else ""
    val items = if (offering) {
        (choices + PromptHistoryRules.suggestions(entries, typed).map { PromptChoice(it, it) })
            .filter { it.text != shown.text }
            .distinctBy { it.text }
    } else {
        emptyList()
    }

    Box(modifier) {
        content(
            PromptFieldState(
                value = shown,
                onValueChange = { next ->
                    val changed = next.text != shown.text
                    shown = next
                    if (changed) {
                        edited = true
                        seedSettled = true
                        menuOpen = true
                        onValueChange(next.text)
                    }
                },
                modifier = Modifier.onFocusChanged { focus ->
                    focused = focus.isFocused
                    if (focus.isFocused) menuOpen = true
                },
            ),
        )
        DropdownMenu(
            expanded = showMenu && menuOpen && focused && items.isNotEmpty(),
            onDismissRequest = { menuOpen = false },
            // Offers must not take focus from the field: a focusable popup would swallow the next keystroke.
            properties = PopupProperties(focusable = false),
        ) {
            items.forEach { choice ->
                DropdownMenuItem(
                    text = { Text(choice.label, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                    onClick = {
                        shown = TextFieldValue(choice.text, TextRange(choice.text.length))
                        edited = true
                        seedSettled = true
                        menuOpen = false
                        onValueChange(choice.text)
                    },
                )
            }
        }
    }
}
