package com.hereliesaz.geministrator

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.hereliesaz.conveyance.h2g2.menu.H2g2PillMenu
import com.hereliesaz.conveyance.h2g2.menu.MenuNode
import com.hereliesaz.conveyance.h2g2.menu.Pill
import com.hereliesaz.conveyance.h2g2.menu.PillMetrics
import com.hereliesaz.conveyance.h2g2.menu.PillMenuPlacement
import com.hereliesaz.geministrator.resources.Res
import com.hereliesaz.geministrator.resources.crumb_here
import com.hereliesaz.geministrator.resources.crumb_label
import org.jetbrains.compose.resources.stringResource

/** Crumb positions in root coordinates, shared by the navigation trail and its menu host. */
internal val LocalCrumbAnchors = staticCompositionLocalOf<MutableMap<String, Float>?> { null }

@Composable
internal fun rememberCrumbAnchors(): MutableMap<String, Float> = remember { mutableStateMapOf() }

/**
 * Position the published h2g2 stack under its breadcrumb trigger. h2g2 owns the entire menu,
 * including gestures, host/trail, expansion, animation and scrolling. A terminal leaf contributes
 * its stable navigation key, not its localized label.
 */
@Composable
internal fun BreadcrumbMenu(
    host: ShellCrumb,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val anchors = LocalCrumbAnchors.current
    val origin = remember { mutableStateOf(0f) }
    val density = LocalDensity.current
    val roots = listOf(MenuNode(id = host.seed, label = host.label,
        children = host.menu.orEmpty(), emitsToken = false))
    BoxWithConstraints(modifier.fillMaxWidth().onGloballyPositioned { origin.value = it.positionInRoot().x }) {
        val desired = anchors?.get(host.seed)?.let {
            with(density) { (it - origin.value).coerceAtLeast(0f).toDp() }
        } ?: 0.dp
        val indent = desired.coerceAtMost((maxWidth - 220.dp).coerceAtLeast(0.dp))
        H2g2PillMenu(
            roots = roots,
            modifier = Modifier.padding(start = indent).fillMaxWidth().height(420.dp),
            placement = PillMenuPlacement.TopLeft,
            onRun = { tokens, terminal -> if (terminal) tokens.lastOrNull()?.let(onSelect) },
        )
    }
}

/**
 * Persistent trail shared by phone and desktop, made of the published h2g2 Pill.
 * It represents the app's actual navigation state, independent of the library menu's transient
 * picks. The District glyph is decorative; its shared pill supplies the accessible name and
 * click target. The current crumb remains visible when the trail overflows.
 */
@Composable
internal fun Breadcrumb(
    crumbs: List<ShellCrumb>,
    openMenu: String?,
    onMenu: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val anchors = LocalCrumbAnchors.current
    val label = stringResource(Res.string.crumb_label)
    val here = stringResource(Res.string.crumb_here)
    val scroll = rememberScrollState()
    LaunchedEffect(crumbs.map { it.label }, scroll.maxValue) { scroll.scrollTo(scroll.maxValue) }
    Row(
        modifier = modifier.semantics { contentDescription = label }.horizontalScroll(scroll),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy((-8).dp),
    ) {
        crumbs.forEachIndexed { index, crumb ->
            val last = index == crumbs.lastIndex
            val click = if (crumb.menu != null) ({ onMenu(if (openMenu == crumb.seed) null else crumb.seed) }) else crumb.onClick
            val selected = last || openMenu == crumb.seed
            val fill = if (selected) azphaltStrong() else Azphalt.hue(crumb.seed)
            val foreground = fill.onOpposite()
            Box(
                Modifier.zIndex((crumbs.size - index).toFloat())
                    .onGloballyPositioned { anchors?.put(crumb.seed, it.positionInRoot().x) },
                contentAlignment = Alignment.Center,
            ) {
                Pill(
                    label = if (crumb.icon == null) crumb.label else "",
                    cap = null,
                    hue = Azphalt.hueIndex(crumb.seed),
                    selected = selected,
                    modifier = if (crumb.icon != null) Modifier.width(PillMetrics.TouchTarget + 8.dp) else Modifier,
                    onClick = click,
                    anchor = Alignment.CenterStart,
                    stretch = crumb.icon != null,
                    extraEndPadding = 8.dp,
                    fillOverride = fill,
                    textOverride = foreground,
                    spokenLabel = crumb.label,
                    spokenState = if (last) here else null,
                )
                crumb.icon?.let { ShellGlyph(it, foreground, size = 24.dp, modifier = Modifier.padding(end = 8.dp)) }
            }
        }
    }
}
