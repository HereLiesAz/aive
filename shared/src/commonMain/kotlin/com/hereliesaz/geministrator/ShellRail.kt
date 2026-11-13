package com.hereliesaz.geministrator

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.hereliesaz.geministrator.tour.TourTargetId
import com.hereliesaz.geministrator.tour.tourTarget
import com.hereliesaz.geministrator.resources.Res
import com.hereliesaz.geministrator.resources.crumb_here
import com.hereliesaz.geministrator.resources.crumb_label
import com.hereliesaz.geministrator.resources.keeper_room
import com.hereliesaz.geministrator.resources.nav_the_aive
import com.hereliesaz.geministrator.resources.rail_brand_the
import com.hereliesaz.geministrator.resources.rail_brand_aive
import com.hereliesaz.geministrator.resources.rail_hive
import com.hereliesaz.geministrator.resources.rail_inbox
import com.hereliesaz.geministrator.resources.rail_keeper
import com.hereliesaz.geministrator.resources.rail_keeper_mark
import com.hereliesaz.geministrator.resources.rail_keeper_you
import com.hereliesaz.geministrator.resources.rail_label
import com.hereliesaz.geministrator.resources.rail_memory
import com.hereliesaz.geministrator.resources.rail_stable
import com.hereliesaz.geministrator.resources.rail_store
import com.hereliesaz.geministrator.resources.rail_roster
import com.hereliesaz.geministrator.resources.rail_unread
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/** The full rail, with labels, from this width up; between [ControlRoomBreakpoints.Wide] and here it is icons only. */
internal val ShellRailLabelledFrom: Dp = 1080.dp

/** The rail's widths: labelled, and icons only. */
internal val ShellRailWidth: Dp = 210.dp
internal val ShellRailNarrowWidth: Dp = 72.dp

/**
 * The places on the wide rail (docs/Keepers-Room.md, Navigation). On a wide window the rail replaces
 * the hive's row of places; phones keep the row. The Keeper's room sits at the rail's foot.
 */
internal enum class ShellRailPlace(val label: StringResource, val icon: ShellIcon) {
    /** The hive on screen, or the district when there is none open. */
    Hive(Res.string.rail_hive, ShellIcon.Hive),
    Inbox(Res.string.rail_inbox, ShellIcon.Inbox),
    Swarm(Res.string.rail_roster, ShellIcon.Swarm),
    Stable(Res.string.rail_stable, ShellIcon.Stable),
    Memory(Res.string.rail_memory, ShellIcon.Memory),
    Store(Res.string.rail_store, ShellIcon.Store),
    ;

    /** Where this place goes. [Hive] goes to the hive; what it does there depends on where you are. */
    val destination: ControlRoomDestination
        get() = when (this) {
            Hive -> ControlRoomDestination.Overview
            Inbox -> ControlRoomDestination.Inbox
            Swarm -> ControlRoomDestination.Company
            Stable -> ControlRoomDestination.Stable
            Memory -> ControlRoomDestination.Memory
            Store -> ControlRoomDestination.AddOns
        }

    companion object {
        /** The rail place lit for [destination]; null for the Keeper's room, which has its own link. */
        fun of(destination: ControlRoomDestination): ShellRailPlace? = when (destination) {
            ControlRoomDestination.Overview, ControlRoomDestination.Workflows -> Hive
            ControlRoomDestination.Inbox -> Inbox
            ControlRoomDestination.Company -> Swarm
            ControlRoomDestination.Stable -> Stable
            ControlRoomDestination.Memory -> Memory
            ControlRoomDestination.AddOns -> Store
            ControlRoomDestination.Settings, ControlRoomDestination.Compute -> null
        }
    }
}

/** What tapping the rail's Hive does, from where the keeper is. */
internal enum class RailHiveStep {
    /** From another place: back to the hive you came from. */
    BackToHive,
    /** On a hive: out to the district, every hive at once. */
    OutToDistrict,
    /** In the district: back into the hive. */
    IntoHive,
}

internal fun railHiveStep(destination: ControlRoomDestination, inDistrict: Boolean): RailHiveStep = when {
    destination != ControlRoomDestination.Overview -> RailHiveStep.BackToHive
    inDistrict -> RailHiveStep.IntoHive
    else -> RailHiveStep.OutToDistrict
}

/** Line icons on a 24-unit grid, stroked like the Figma design's (1.8 units, round caps). */
internal enum class ShellIcon(val paths: List<String>) {
    Hive(listOf("M12 3l7.8 4.5v9L12 21l-7.8-4.5v-9L12 3z", "M12 8l3.5 2v4L12 16l-3.5-2v-4L12 8z")),
    Inbox(listOf("M4 4h16v13H4z", "M4 13h4l2 3h4l2-3h4")),
    Swarm(listOf("M12 9m-3 0a3 3 0 1 0 6 0a3 3 0 1 0 -6 0", "M17 7m-2 0a2 2 0 1 0 4 0a2 2 0 1 0 -4 0", "M3 20c0-4 2-6 6-6s6 2 6 6M15 13c4 0 6 2 6 5")),
    Stable(listOf("M12 3c4 0 7 6 7 11a7 7 0 0 1-14 0c0-5 3-11 7-11z", "M9 14c1 1.5 5 1.5 6 0")),
    Memory(listOf("M7 4h10a3 3 0 0 1 3 3v10a3 3 0 0 1-3 3H7a3 3 0 0 1-3-3V7a3 3 0 0 1 3-3z", "M8 8h8M8 12h8M8 16h5")),
    Store(listOf("M4 9l2-5h12l2 5M5 9v11h14V9", "M9 20v-6h6v6M3 9c0 2 3 3 4.5 1C9 12 12 12 12 9c0 3 3 3 4.5 1C18 12 21 11 21 9")),
    Settings(listOf("M12 12m-3 0a3 3 0 1 0 6 0a3 3 0 1 0 -6 0", "M19 12a7 7 0 0 0-.1-1l2-1.5-2-3.4-2.4 1A8 8 0 0 0 15 6l-.3-2.6h-4L10.4 6A8 8 0 0 0 9 7.1l-2.4-1-2 3.4 2 1.5a7 7 0 0 0 0 2l-2 1.5 2 3.4 2.4-1A8 8 0 0 0 10.4 18l.3 2.6h4L15 18a8 8 0 0 0 1.5-1.1l2.4 1 2-3.4-2-1.5a7 7 0 0 0 .1-1z")),
}

/** Draws [icon] in [color] at [size]; decorative, its control carries the words. */
@Composable
internal fun ShellGlyph(icon: ShellIcon, color: Color, size: Dp = 20.dp, modifier: Modifier = Modifier) {
    val paths = remember(icon) { icon.paths.map { PathParser().parsePathString(it).toPath() } }
    Canvas(modifier.size(size).clearAndSetSemantics {}) {
        val unit = this.size.width / 24f
        scale(unit, unit, pivot = androidx.compose.ui.geometry.Offset.Zero) {
            val stroke = Stroke(width = 1.8f, cap = StrokeCap.Round, join = StrokeJoin.Round)
            paths.forEach { drawPath(it, color, style = stroke) }
        }
    }
}

/**
 * The wide rail (Figma: `.rail`): the Aive's mark, the places, then the keeper and the Keeper's
 * room at its foot. Ink, with the selected place filled in the Ink surface's strong colour (Yellow).
 * [labelled] shows the words (210dp); without them it is a 72dp column of icons, each still named
 * for screen readers.
 */
@Composable
internal fun ShellRail(
    selected: ShellRailPlace?,
    keeperSelected: Boolean,
    unreadInbox: Int,
    labelled: Boolean,
    onPlace: (ShellRailPlace) -> Unit,
    onOpenKeeper: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AzphaltSurface(Azphalt.Ink) {
        Column(
            modifier = modifier
                .width(if (labelled) ShellRailWidth else ShellRailNarrowWidth)
                .fillMaxHeight()
                .background(Azphalt.Ink)
                .padding(start = if (labelled) 16.dp else 10.dp, end = if (labelled) 16.dp else 10.dp, top = 24.dp, bottom = 18.dp),
        ) {
            RailBrand(labelled)
            val railLabel = stringResource(Res.string.rail_label)
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(top = 24.dp)
                    .verticalScroll(rememberScrollState())
                    .selectableGroup()
                    .semantics { contentDescription = railLabel },
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                ShellRailPlace.entries.forEach { place ->
                    RailButton(
                        label = stringResource(place.label),
                        icon = place.icon,
                        selected = place == selected,
                        labelled = labelled,
                        count = if (place == ShellRailPlace.Inbox) unreadInbox else 0,
                        onClick = { onPlace(place) },
                    )
                }
            }
            RailKeeper(labelled)
            RailButton(
                label = stringResource(Res.string.keeper_room),
                icon = ShellIcon.Settings,
                selected = keeperSelected,
                labelled = labelled,
                count = 0,
                small = true,
                onClick = onOpenKeeper,
                modifier = Modifier.tourTarget(TourTargetId.KEEPERS_ROOM_GEAR),
            )
        }
    }
}

@Composable
private fun RailBrand(labelled: Boolean) {
    val on = azphaltOnSurface()
    val name = stringResource(Res.string.nav_the_aive)
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(58.dp)
                .padding(start = if (labelled) 10.dp else 0.dp, end = if (labelled) 10.dp else 0.dp, bottom = 20.dp)
                .semantics(mergeDescendants = true) { contentDescription = name; heading() },
            horizontalArrangement = if (labelled) Arrangement.spacedBy(11.dp) else Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // The hive's mark: the rail is Ink, so it takes the strong colour of an Ink surface.
            ShellGlyph(ShellIcon.Hive, azphaltStrong(), size = 38.dp)
            if (labelled) {
                Column(Modifier.clearAndSetSemantics {}) {
                    Text(stringResource(Res.string.rail_brand_the), style = AzphaltType.eyebrow.copy(fontSize = 9.sp, lineHeight = 10.sp, letterSpacing = 0.28.em), color = on)
                    Text(stringResource(Res.string.rail_brand_aive), style = AzphaltType.section.copy(fontSize = 24.sp, lineHeight = 24.sp, letterSpacing = (-0.04).em), color = on)
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(AzphaltLiftedInk))
    }
}

@Composable
private fun RailButton(
    label: String,
    icon: ShellIcon,
    selected: Boolean,
    labelled: Boolean,
    count: Int,
    onClick: () -> Unit,
    small: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val fill = if (selected) azphaltStrong() else Color.Transparent
    val surface = azphaltSurface()
    val content = if (selected) fill.onOpposite() else surface.mutedOnOpposite(minRatio = 4.5f)
    val badge = inboxBadgeLabel(count)
    val description = if (badge != null) stringResource(Res.string.rail_unread, label, badge) else label
    Row(
        modifier = modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {
                role = Role.Tab
                this.selected = selected
                contentDescription = description
                if (badge != null) liveRegion = LiveRegionMode.Polite
            }
            .heightIn(min = AzphaltMinTouchTarget)
            .clip(RoundedCornerShape(999.dp))
            .background(fill)
            .clickable(onClick = onClick)
            .padding(horizontal = if (labelled) (if (small) 10.dp else 12.dp) else 0.dp),
        horizontalArrangement = if (labelled) Arrangement.spacedBy(12.dp) else Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            ShellGlyph(icon, content, size = if (small) 19.dp else 20.dp)
            // Icons only: the count shrinks to a dot so an unread wait still shows.
            if (!labelled && badge != null) {
                Box(Modifier.align(Alignment.TopEnd).offset(x = 5.dp, y = (-3).dp).size(9.dp).clip(CircleShape).background(if (selected) Azphalt.Ink else Azphalt.Yellow))
            }
        }
        if (labelled) {
            Text(
                label,
                style = AzphaltType.body.copy(fontSize = if (small) 13.sp else 14.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 0.04.em),
                color = content,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (badge != null) {
                // Yellow on Ink; Ink with white on the Yellow of the selected place.
                val cap = if (selected) Azphalt.Ink else Azphalt.Yellow
                Box(
                    Modifier.defaultMinSize(minWidth = 24.dp, minHeight = 24.dp).clip(RoundedCornerShape(999.dp)).background(cap).padding(horizontal = 6.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(badge, style = AzphaltType.endCap, color = cap.onOpposite())
                }
            }
        }
    }
}

/**
 * Who keeps this hive. The app has no keeper name, so it says "You" rather than invent one; the mark
 * is the keeper's own seeded hue, like any living thing in the hive.
 */
@Composable
private fun RailKeeper(labelled: Boolean) {
    val keeper = stringResource(Res.string.rail_keeper)
    val you = stringResource(Res.string.rail_keeper_you)
    Column {
        Box(Modifier.fillMaxWidth().height(1.dp).background(AzphaltLiftedInk))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 14.dp, horizontal = if (labelled) 9.dp else 0.dp)
                .semantics(mergeDescendants = true) { contentDescription = "$keeper: $you" },
            horizontalArrangement = if (labelled) Arrangement.spacedBy(10.dp) else Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val mark = Azphalt.hue("keeper")
            Box(Modifier.size(36.dp).clip(CircleShape).background(mark), contentAlignment = Alignment.Center) {
                Text(stringResource(Res.string.rail_keeper_mark), style = AzphaltType.section.copy(fontSize = 16.sp, lineHeight = 16.sp), color = mark.onOpposite())
            }
            if (labelled) {
                Column(Modifier.clearAndSetSemantics {}) {
                    Text(keeper.uppercase(), style = AzphaltType.eyebrow.copy(fontSize = 9.sp, lineHeight = 11.sp, letterSpacing = 0.18.em), color = azphaltMutedOnSurface())
                    Text(you, style = AzphaltType.body.copy(fontSize = 14.sp, fontWeight = FontWeight.Bold), color = azphaltOnSurface())
                }
            }
        }
    }
}

/**
 * The wide header (Figma: `.aive-header`): THE AIVE / the breadcrumb, the hive's state, and the
 * Inbox. The rail holds the Keeper's room, so the gear is not repeated here. Earlier crumbs are
 * text buttons that go back up; the last is where you are.
 */
@Composable
internal fun WideShellHeader(
    crumbs: List<ShellCrumb>,
    state: String,
    unreadInbox: Int,
    onOpenInbox: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val on = azphaltOnSurface()
    Column(modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 65.dp).padding(horizontal = 28.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            Text(stringResource(Res.string.nav_the_aive), style = AzphaltType.eyebrow.copy(letterSpacing = 0.1.em), color = on)
            Text("/", style = AzphaltType.eyebrow, color = azphaltMutedOnSurface(), modifier = Modifier.clearAndSetSemantics {})
            WideCrumbs(crumbs, Modifier.weight(1f))
            Row(
                modifier = Modifier.azphaltLiveRegion(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // A still dot with a soft ring: the hive's state, without ambient motion.
                Box(Modifier.size(16.dp).clip(CircleShape).background(on.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(on))
                }
                Text(state, style = AzphaltType.eyebrow.copy(letterSpacing = 0.14.em), color = on)
            }
            Spacer(Modifier.width(9.dp))
            InboxBadge(unread = unreadInbox, onClick = onOpenInbox, modifier = Modifier.tourTarget(TourTargetId.MESSAGES_BUTTON))
        }
        Box(Modifier.fillMaxWidth().height(2.dp).background(on.copy(alpha = 0.18f)))
    }
}

@Composable
private fun WideCrumbs(crumbs: List<ShellCrumb>, modifier: Modifier = Modifier) {
    val label = stringResource(Res.string.crumb_label)
    val here = stringResource(Res.string.crumb_here)
    val on = azphaltOnSurface()
    val scroll = rememberScrollState()
    // A long trail scrolls so where you are, its last step, stays in view.
    LaunchedEffect(crumbs.map { it.label }, scroll.maxValue) { scroll.scrollTo(scroll.maxValue) }
    Row(
        modifier = modifier.semantics { contentDescription = label }.horizontalScroll(scroll),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        crumbs.forEachIndexed { index, crumb ->
            if (index > 0) Text("›", style = AzphaltType.body, color = azphaltMutedOnSurface(), modifier = Modifier.clearAndSetSemantics {})
            val last = index == crumbs.lastIndex
            val style = if (last) AzphaltType.body.copy(fontWeight = FontWeight.ExtraBold) else AzphaltType.body.copy(fontWeight = FontWeight.Medium)
            val onClick = crumb.onClick
            val base = Modifier
                .defaultMinSize(minHeight = AzphaltMinTouchTarget)
                .clip(RoundedCornerShape(999.dp))
            val step = if (onClick != null) {
                base.semantics(mergeDescendants = true) { role = Role.Button; contentDescription = crumb.label }.clickable(onClick = onClick)
            } else {
                base.clearAndSetSemantics {
                    contentDescription = crumb.label
                    heading()
                    if (last) stateDescription = here
                }
            }
            Box(step.padding(horizontal = 8.dp), contentAlignment = Alignment.Center) {
                Text(crumb.label, style = style, color = on, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 360.dp))
            }
        }
    }
}

/**
 * The page-title pattern on wide pages (Figma: `SectionTitle`): a small eyebrow, the big title in
 * Archivo Black, one line under it, and an optional action at the right.
 */
@Composable
internal fun AzphaltPageTitle(
    eyebrow: String,
    title: String,
    line: String? = null,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
) {
    val on = azphaltOnSurface()
    Row(
        modifier = modifier.fillMaxWidth().heightIn(min = 91.dp),
        horizontalArrangement = Arrangement.spacedBy(20.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Column(Modifier.weight(1f).semantics(mergeDescendants = true) { heading() }) {
            Text(eyebrow.uppercase(), style = AzphaltType.eyebrow, color = on)
            Spacer(Modifier.height(7.dp))
            Text(title, style = AzphaltType.hero, color = on)
            if (line != null) {
                Spacer(Modifier.height(8.dp))
                Text(line, style = AzphaltType.body.copy(fontWeight = FontWeight.Bold), color = on)
            }
        }
        action?.invoke()
    }
}
