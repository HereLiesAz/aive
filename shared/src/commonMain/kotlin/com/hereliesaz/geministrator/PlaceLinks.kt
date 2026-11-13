package com.hereliesaz.geministrator

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextDecoration

/**
 * An address the Connect remote agent form should open with, handed over by a link elsewhere (a hive card that
 * uses an orphan). Held as state so the form reacts whether or not it was already on screen, and consumed once so
 * reopening Connections later does not fill it again.
 */
internal object ConnectionsRequests {
    var a2aAddress: String? by mutableStateOf(null)

    /** Takes the pending address, leaving none. */
    fun take(): String? = a2aAddress.also { a2aAddress = null }
}

/**
 * A role the Roster should open on its form, handed over by a link elsewhere (a creature's Mind, its budget line).
 * The Roster is the one place a role is changed; held as state and taken once, like [ConnectionsRequests].
 */
internal object RosterRequests {
    var roleId: String? by mutableStateOf(null)

    /** Takes the pending role id, leaving none. */
    fun take(): String? = roleId.also { roleId = null }
}

/** Set by a link to the swarm editor (the Edit view); the terrarium opens Edit with it and takes it. */
internal object SwarmEditRequests {
    var pending: Boolean by mutableStateOf(false)

    /** True once, when a request was pending. */
    fun take(): Boolean = pending.also { pending = false }
}

/** An address worth handing to the form: trimmed, not blank, not absurd. */
internal fun connectionsAddressRequest(raw: String?): String? =
    raw?.trim()?.takeIf { it.isNotEmpty() && it.length <= 2048 }

/**
 * The one route to Connections (docs/Keepers-Room.md): the Keeper's room, opened on its Connections part and not on
 * whichever part was left open last. Every link to it (the creature panel, a hive's Source line, the hive card, a
 * first-launch hint) calls this. [a2aAddress], when given, opens the Connect remote agent form with that address.
 */
internal fun openConnectionsPlace(
    keeperSectionName: MutableState<String>,
    keeperOpen: MutableState<Boolean>,
    a2aAddress: String?,
    onDestinationSelected: (ControlRoomDestination) -> Unit,
) {
    keeperSectionName.value = KeeperSection.Connections.name
    keeperOpen.value = true
    ConnectionsRequests.a2aAddress = connectionsAddressRequest(a2aAddress)
    onDestinationSelected(ControlRoomDestination.Settings)
}

/** The Keeper's room opened on one part (Schedules, Limits, Memory's neighbours...), not on whichever was left open. */
internal fun openKeeperPartPlace(
    section: KeeperSection,
    keeperSectionName: MutableState<String>,
    keeperOpen: MutableState<Boolean>,
    onDestinationSelected: (ControlRoomDestination) -> Unit,
) {
    keeperSectionName.value = section.name
    keeperOpen.value = true
    onDestinationSelected(ControlRoomDestination.Settings)
}

/**
 * The Hive place opened on one hive at rest (a schedule's hive, a role's hive): off the district, no resting hive
 * but this one, back on the Hive destination.
 */
internal fun openHivePlace(
    definitionId: String,
    district: MutableState<Boolean>,
    restingHive: MutableState<String>,
    onDestinationSelected: (ControlRoomDestination) -> Unit,
) {
    district.value = false
    restingHive.value = definitionId
    onDestinationSelected(ControlRoomDestination.Overview)
}

/**
 * Links that any screen can follow without threading a callback through every parent (the nav map's "everything is a
 * link"). The ControlRoom provides them; a preview or test that provides none follows none, and its lines stay plain.
 */
internal class PlaceLinks(
    /** Opens Connections, optionally on the Connect remote agent form with an address filled in. */
    val openConnections: ((a2aAddress: String?) -> Unit)? = null,
    /** Opens one source's connect or reconfigure screen (the existing onReconfigureProvider). */
    val openSource: ((providerId: String) -> Unit)? = null,
    /** Opens the Roster on this role's form: the one place instructions, tools, budget and source change. */
    val openRole: ((roleId: String) -> Unit)? = null,
    /** Opens the Keeper's room on Delegations (trusted steps). */
    val openDelegations: (() -> Unit)? = null,
    /** Opens the swarm editor (Edit view) of the hive on screen, the selected creature kept. */
    val openSwarmEditor: (() -> Unit)? = null,
    /** Opens one hive on the Hive place, at rest unless it has a live run. */
    val openHive: ((definitionId: String) -> Unit)? = null,
    /** Opens the Keeper's room on one part. */
    val openKeeperPart: ((KeeperSection) -> Unit)? = null,
    /** Opens a whole place: the Roster, the Stable, the Store, Messages, the Swarm library. */
    val openPlace: ((ControlRoomDestination) -> Unit)? = null,
)

internal val LocalPlaceLinks = staticCompositionLocalOf { PlaceLinks() }

/**
 * Text that is a link: underlined, a button to a screen reader, and a full-size touch target. It names where the
 * keeper can change the thing it shows (the nav map's "everything is a link"), so [onClick] opens that place.
 * [onClick] null leaves it plain text, so a screen with nowhere to go never draws a dead link.
 */
@Composable
internal fun LinkText(
    text: String,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    style: TextStyle = AzphaltType.body,
    color: Color = azphaltOnSurface(),
) {
    if (onClick == null) {
        Text(text, style = style, color = color, modifier = modifier)
    } else {
        Text(
            text,
            style = style,
            color = color,
            textDecoration = TextDecoration.Underline,
            modifier = modifier
                .defaultMinSize(minHeight = AzphaltMinTouchTarget)
                .clickable(role = Role.Button, onClick = onClick),
        )
    }
}
