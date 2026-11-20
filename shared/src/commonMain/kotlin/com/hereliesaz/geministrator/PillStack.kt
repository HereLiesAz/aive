package com.hereliesaz.geministrator

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * One pill of a [PillStack]. An item with [children] is a host: choosing it opens them (HG2Gui's pill
 * menu, docs/DESIGN.md §2); an item without is chosen on the spot.
 */
internal data class PillStackItem(
    val key: String,
    val label: String,
    val endCap: String? = null,
    val children: List<PillStackItem> = emptyList(),
)

/**
 * Where each breadcrumb step stands across the screen (its left edge, in the window), by step key, so a
 * stack can hang under the step it belongs to. Filled by the breadcrumb, read by [PillStack].
 */
internal val LocalCrumbAnchors = staticCompositionLocalOf<MutableMap<String, Float>?> { null }

/** A fresh map for the breadcrumb and the stack beneath it to share. */
@Composable
internal fun rememberCrumbAnchors(): MutableMap<String, Float> = remember { mutableStateMapOf() }

/**
 * HG2Gui's pill menu, shaped for The Aive (docs/DESIGN.md §1-4, with one difference the keeper asked for:
 * it is not right-anchored, it hangs under the breadcrumb step it belongs to, [anchorKey]).
 *
 * The stack is a column of pills at HG2Gui's 48dp pitch. Choosing a host moves the stack aside: the host
 * parks at the bottom as the anchor for the next layer, its children cascade upward from it, and the
 * path chosen so far rides beside it as overlapping trail crumbs. A crumb returns to that point; the host
 * itself steps back one layer. [selectedKey] is the leaf that is open now, drawn selected.
 */
@OptIn(ExperimentalAnimationApi::class)
@Composable
internal fun PillStack(
    items: List<PillStackItem>,
    selectedKey: String?,
    onSelect: (String) -> Unit,
    seedPrefix: String,
    modifier: Modifier = Modifier,
    /** The breadcrumb step this stack hangs under; its left edge is the stack's. Null leaves it at the page's own edge. */
    anchorKey: String? = null,
    /** Hosts opened to start with, outermost first (the layer the keeper left it on). */
    initialPath: List<String> = emptyList(),
) {
    var path by remember(items.map { it.key }) { mutableStateOf(initialPath) }
    val anchors = LocalCrumbAnchors.current
    var originX by remember { mutableStateOf(0f) }
    val density = LocalDensity.current
    val indent = anchorKey?.let { anchors?.get(it) }?.let { crumbX -> with(density) { (crumbX - originX).coerceAtLeast(0f).toDp() } }
        ?: 0.dp
    Column(
        modifier = modifier
            .onGloballyPositioned { originX = it.positionInRoot().x }
            .padding(start = indent),
    ) {
        AnimatedContent(
            targetState = path,
            transitionSpec = {
                // Structural, not a fade (DESIGN.md §4): a deeper layer's children rise from the host, a shallower one drops back.
                val deeper = targetState.size > initialState.size
                (slideInVertically(tween(180)) { if (deeper) it / 3 else -it / 3 } togetherWith
                    slideOutVertically(tween(150)) { if (deeper) -it / 3 else it / 3 })
                    .using(SizeTransform(clip = false))
            },
            label = "pill-stack",
        ) { shownPath ->
            val hosts = hostsAlong(items, shownPath)
            val level = hosts.lastOrNull()?.children ?: items
            Column(Modifier.selectableGroup()) {
                level.forEach { item ->
                    AzphaltPill(
                        label = item.label,
                        seed = "$seedPrefix-${item.key}",
                        selected = item.key == selectedKey,
                        endCap = item.endCap ?: if (item.children.isNotEmpty()) "›" else null,
                        onClick = {
                            if (item.children.isNotEmpty()) path = shownPath + item.key else onSelect(item.key)
                        },
                    )
                }
                if (hosts.isNotEmpty()) {
                    // The host parked below its children, with the trail of how it was reached beside it.
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        val host = hosts.last()
                        AzphaltPill(
                            label = host.label,
                            seed = "$seedPrefix-host-${host.key}",
                            selected = true,
                            onClick = { path = shownPath.dropLast(1) },
                            description = host.label,
                        )
                        hosts.dropLast(1).forEachIndexed { index, crumb ->
                            // Earlier crumbs sit under later ones: shingled, as HG2Gui's trail is.
                            AzphaltPill(
                                label = crumb.label,
                                seed = "$seedPrefix-trail-${crumb.key}",
                                onClick = { path = shownPath.take(index + 1) },
                                modifier = Modifier.offset(x = (-14).dp * (index + 1)),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** The hosts named by [path], outermost first; a key that no longer exists ends the walk. */
internal fun hostsAlong(items: List<PillStackItem>, path: List<String>): List<PillStackItem> {
    val hosts = mutableListOf<PillStackItem>()
    var level = items
    for (key in path) {
        val next = level.firstOrNull { it.key == key && it.children.isNotEmpty() } ?: break
        hosts += next
        level = next.children
    }
    return hosts
}
