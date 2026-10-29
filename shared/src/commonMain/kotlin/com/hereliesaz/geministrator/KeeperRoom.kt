package com.hereliesaz.geministrator

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.hereliesaz.geministrator.resources.Res
import com.hereliesaz.geministrator.resources.keeper_about
import com.hereliesaz.geministrator.resources.keeper_about_lead
import com.hereliesaz.geministrator.resources.keeper_appearance
import com.hereliesaz.geministrator.resources.keeper_appearance_lead
import com.hereliesaz.geministrator.resources.keeper_compute
import com.hereliesaz.geministrator.resources.keeper_compute_lead
import com.hereliesaz.geministrator.resources.keeper_connections
import com.hereliesaz.geministrator.resources.keeper_connections_lead
import com.hereliesaz.geministrator.resources.keeper_device
import com.hereliesaz.geministrator.resources.keeper_device_lead
import com.hereliesaz.geministrator.resources.keeper_feeds_the_hive
import com.hereliesaz.geministrator.resources.keeper_memory
import com.hereliesaz.geministrator.resources.keeper_memory_lead
import com.hereliesaz.geministrator.resources.keeper_on_count
import com.hereliesaz.geministrator.resources.keeper_projects
import com.hereliesaz.geministrator.resources.keeper_projects_lead
import com.hereliesaz.geministrator.resources.keeper_room
import com.hereliesaz.geministrator.resources.keeper_the_keeper
import com.hereliesaz.geministrator.resources.common_off
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/**
 * The parts of the Keeper's room (docs/Keepers-Room.md): everything that sets up the hive, in one
 * place. [feedsTheHive] parts are what the swarm runs on; the rest are the keeper's own.
 */
internal enum class KeeperSection(
    val title: StringResource,
    val lead: StringResource,
    val feedsTheHive: Boolean,
) {
    Connections(Res.string.keeper_connections, Res.string.keeper_connections_lead, true),
    Memory(Res.string.keeper_memory, Res.string.keeper_memory_lead, true),
    Compute(Res.string.keeper_compute, Res.string.keeper_compute_lead, true),
    Projects(Res.string.keeper_projects, Res.string.keeper_projects_lead, false),
    Device(Res.string.keeper_device, Res.string.keeper_device_lead, false),
    Appearance(Res.string.keeper_appearance, Res.string.keeper_appearance_lead, false),
    About(Res.string.keeper_about, Res.string.keeper_about_lead, false),
    ;

    companion object {
        /** The part an old destination now opens on. */
        fun of(destination: ControlRoomDestination): KeeperSection? = when (destination) {
            ControlRoomDestination.Repositories -> Connections
            ControlRoomDestination.Compute -> Compute
            ControlRoomDestination.Memory -> Memory
            else -> null
        }
    }
}

/** Page padding inside the Keeper's room: 16dp on a phone, 24dp wider. */
internal fun keeperContentPadding(compact: Boolean): PaddingValues =
    if (compact) PaddingValues(16.dp) else PaddingValues(start = 12.dp, top = 8.dp, end = 24.dp, bottom = 24.dp)

/**
 * The Keeper's room: a short list of parts and the open one. On a phone the list is its own
 * page and a part opens over it; wider, they sit side by side. [content] draws a part, scrolling
 * itself.
 */
@Composable
internal fun KeeperRoom(
    sections: List<KeeperSection>,
    opening: KeeperSection?,
    compact: Boolean,
    statusOf: @Composable (KeeperSection) -> String?,
    content: @Composable (KeeperSection, Modifier) -> Unit,
    modifier: Modifier = Modifier,
) {
    var selectedName by rememberDurableStringState("keeper.section", KeeperSection.Connections.name)
    var open by rememberDurableBooleanState("keeper.open")
    LaunchedEffect(opening) {
        if (opening != null) {
            selectedName = opening.name
            open = true
        }
    }
    val selected = sections.firstOrNull { it.name == selectedName } ?: sections.first()

    if (compact) {
        PlatformBackHandler(enabled = open) { open = false }
        if (open) {
            Column(modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AzphaltPill(
                        label = "‹ " + stringResource(Res.string.keeper_room),
                        seed = "keeper-back",
                        onClick = { open = false },
                    )
                }
                KeeperSectionHeading(selected, compact = true)
                content(selected, Modifier.weight(1f).fillMaxWidth())
            }
        } else {
            Column(
                modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(stringResource(Res.string.keeper_room), style = AzphaltType.section, color = azphaltOnSurface())
                KeeperSectionList(sections, selected = null, statusOf = statusOf) {
                    selectedName = it.name
                    open = true
                }
            }
        }
    } else {
        Row(modifier.fillMaxSize()) {
            Column(
                Modifier.width(240.dp).fillMaxHeight().verticalScroll(rememberScrollState()).padding(start = 8.dp, top = 22.dp, end = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                KeeperSectionList(sections, selected = selected, statusOf = statusOf) { selectedName = it.name }
            }
            Column(Modifier.weight(1f).fillMaxHeight()) {
                KeeperSectionHeading(selected, compact = false)
                content(selected, Modifier.weight(1f).fillMaxWidth())
            }
        }
    }
}

@Composable
private fun KeeperSectionHeading(section: KeeperSection, compact: Boolean) {
    Column(
        Modifier.fillMaxWidth().padding(start = if (compact) 16.dp else 12.dp, end = 16.dp, top = if (compact) 8.dp else 22.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(stringResource(section.title), style = AzphaltType.section, color = azphaltOnSurface())
        Text(stringResource(section.lead), style = AzphaltType.body, color = azphaltOnSurface())
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun KeeperSectionList(
    sections: List<KeeperSection>,
    selected: KeeperSection?,
    statusOf: @Composable (KeeperSection) -> String?,
    onSelect: (KeeperSection) -> Unit,
) {
    Column(Modifier.fillMaxWidth().selectableGroup(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        listOf(true, false).forEach { feeds ->
            val group = sections.filter { it.feedsTheHive == feeds }
            if (group.isEmpty()) return@forEach
            Text(
                stringResource(if (feeds) Res.string.keeper_feeds_the_hive else Res.string.keeper_the_keeper).uppercase(),
                style = AzphaltType.eyebrow,
                color = azphaltMutedOnSurface(),
                modifier = Modifier.padding(start = 12.dp, top = 14.dp, bottom = 4.dp),
            )
            group.forEach { section ->
                val isSelected = section == selected
                val fill = if (isSelected) azphaltStrong() else androidx.compose.ui.graphics.Color.Transparent
                val text = if (isSelected) fill.onOpposite() else azphaltOnSurface()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = AzphaltMinTouchTarget)
                        .clip(RoundedCornerShape(999.dp))
                        .background(fill)
                        .semantics {
                            role = Role.Tab
                            this.selected = isSelected
                        }
                        .clickable { onSelect(section) }
                        .padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(stringResource(section.title), style = AzphaltType.lead, color = text, modifier = Modifier.weight(1f))
                    statusOf(section)?.let { Text(it.uppercase(), style = AzphaltType.endCap, color = text) }
                }
            }
        }
    }
}

/** How many things a part has switched on, for the section list ("3 on", "off"). */
@Composable
internal fun keeperCount(count: Int): String =
    if (count > 0) stringResource(Res.string.keeper_on_count, count) else stringResource(Res.string.common_off)

/**
 * One connected (or connectable) thing: its name, a line about it, its status, and what can be
 * done to it. One row each, so a list of them reads at a glance.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ConnectionRow(
    name: String,
    detail: String?,
    status: String?,
    connected: Boolean,
    compact: Boolean,
    modifier: Modifier = Modifier,
    actions: @Composable () -> Unit,
) {
    val card = if (connected) {
        if (azphaltSurface() == Azphalt.Ink) AzphaltLiftedInk else Azphalt.Ink
    } else {
        androidx.compose.ui.graphics.Color.Transparent
    }
    val shape = RoundedCornerShape(14.dp)
    Box(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(card)
            .then(if (connected) Modifier else Modifier.border(2.dp, azphaltMutedOnSurface(), shape))
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        AzphaltThemedSurface(card) {
            BoxWithConstraints {
                val wide = !compact && maxWidth > 620.dp
                if (wide) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        Column(Modifier.width(170.dp)) { ConnectionName(name) }
                        Column(Modifier.weight(1f)) { detail?.let { ConnectionDetail(it) } }
                        status?.let { ConnectionStatus(it) }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { actions() }
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.weight(1f)) { ConnectionName(name) }
                            status?.let { ConnectionStatus(it) }
                        }
                        detail?.let { ConnectionDetail(it) }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { actions() }
                    }
                }
            }
        }
    }
}

@Composable
private fun ConnectionName(name: String) = Text(name, style = AzphaltType.lead, color = azphaltOnSurface())

@Composable
private fun ConnectionDetail(detail: String) = Text(detail, style = AzphaltType.body, color = azphaltOnSurface())

@Composable
private fun ConnectionStatus(status: String) {
    val chip = azphaltStrong()
    Box(Modifier.clip(RoundedCornerShape(999.dp)).background(chip).padding(horizontal = 9.dp, vertical = 4.dp)) {
        Text(status.uppercase(), style = AzphaltType.endCap, color = chip.onOpposite())
    }
}

/** "+ Add a provider": opens the list of what isn't connected yet. */
@Composable
internal fun AddConnectionRow(label: String, hint: String, open: Boolean, onToggle: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    @OptIn(ExperimentalLayoutApi::class)
    FlowRow(
        Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = AzphaltMinTouchTarget)
            .clip(shape)
            .border(2.dp, azphaltMutedOnSurface(), shape)
            .semantics(mergeDescendants = true) {
                role = Role.Button
                selected = open
            }
            .clickable(onClick = onToggle)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text((if (open) "− " else "+ ") + label, style = AzphaltType.lead, color = azphaltOnSurface())
        Text(hint, style = AzphaltType.body, color = azphaltMutedOnSurface())
    }
}
