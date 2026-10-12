package com.hereliesaz.conveyance.h2g2

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier

/**
 * A whole-screen [Ground] background, ready to drop a host's own content into.
 *
 * This is deliberately **not** a [Templates.registry] entry: every `kind: "composable"` `.azp`
 * element requires a non-empty `act` (azphalt `spec/composable.md`), and a page background isn't
 * an actionable control -- there is nothing to offer, nothing to engage. [Ground]/[GroundState]/
 * `Modifier.ground` are already a complete, directly callable API (see `Grounds.kt`); this is
 * just the one-line convenience a host reaches for most often, wrapping them for the common case
 * of "paint the ground behind my content."
 */
@Composable
fun H2g2Page(
    state: GroundState = remember { GroundState() },
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit = {},
) {
    Box(modifier = modifier.fillMaxSize().ground(state.current), content = content)
}
