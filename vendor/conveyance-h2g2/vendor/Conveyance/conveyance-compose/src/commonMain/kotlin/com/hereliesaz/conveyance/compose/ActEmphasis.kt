package com.hereliesaz.conveyance.compose

import androidx.compose.runtime.Composable
import com.hereliesaz.conveyance.ActEmphasis

/**
 * The emphasis level this Act declares for itself.
 *
 * This is functional metadata on the Act, not employment, visual state, or layout state.
 */
val ActScope.declaredEmphasis: ActEmphasis get() = act.emphasis

/**
 * Resolve this Act's declared emphasis against the other visible Acts on the current screen.
 *
 * Think of the hierarchy like an HTML document outline: Heroic is the page title, then Primary,
 * Secondary, Tertiary, and Supporting descend through the outline. A screen may present at most one
 * Heroic Act. If two or more visible Acts claim Heroic, every visible Act resolves one rung lower.
 *
 * Nothing is mutated. [act] keeps its declared emphasis; this function derives the presentation
 * level from the current set of visible Acts.
 */
@Composable
fun ActScope.resolvedEmphasis(): ActEmphasis = LocalElements.current.resolvedEmphasis(act)
