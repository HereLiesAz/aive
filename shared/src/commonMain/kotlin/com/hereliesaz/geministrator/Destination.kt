package com.hereliesaz.geministrator

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** A field of a role's form that a link can name, so the Roster opens with the cursor on it (docs/Navigation-Map.md). */
internal enum class RoleField { Instructions, TokenBudget, Source }

/**
 * Where a link goes (docs/Navigation-Map.md). Every "change it here" link names one of these and calls the one
 * navigate; the shell then lands on [place] and applies the rest (the open Keeper's room part, the role and its
 * field, the creature and its tab). It adds no screen: each case is a place that already exists.
 */
internal sealed interface Destination {
    /** A whole place: the Roster, the Stable, the Store, Messages, the Swarm library, the Keeper's room. */
    data class Place(val destination: ControlRoomDestination) : Destination

    /** The Keeper's room opened on one part. */
    data class KeeperPart(val section: KeeperSection) : Destination

    /** Connections; [a2aAddress], when given, opens the Connect remote agent form with it filled in. */
    data class Connections(val a2aAddress: String? = null) : Destination

    /** The Roster on one role's form, with [field] focused when the link names one. */
    data class Role(val roleId: String, val field: RoleField? = null) : Destination

    /** One creature of the hive on screen, opened on [tab] when the link names one. */
    data class Task(val taskId: String, val tab: CreatureTab? = null) : Destination

    /** One hive, at rest unless it has a live run. */
    data class Hive(val definitionId: String) : Destination

    /** The swarm editor (the Edit view) of the hive on screen. */
    data object SwarmEditor : Destination
}

/** The top-level place a destination lands on: what the shell selects and what Back returns to. */
internal val Destination.place: ControlRoomDestination
    get() = when (this) {
        is Destination.Place -> this.destination
        is Destination.KeeperPart -> ControlRoomDestination.Settings
        is Destination.Connections -> ControlRoomDestination.Settings
        is Destination.Role -> ControlRoomDestination.Company
        is Destination.Task -> ControlRoomDestination.Overview
        is Destination.Hive -> ControlRoomDestination.Overview
        Destination.SwarmEditor -> ControlRoomDestination.Overview
    }

/**
 * A destination the app shell was asked to go to that the control room applies (the Keeper's room part, the role,
 * the creature): held as state and taken once, like [RosterRequests]. Set by [navigate] callers above the control
 * room, such as the MCP consent dialog.
 */
internal object PendingDestination {
    var request: Destination? by mutableStateOf(null)

    /** Takes the pending destination, leaving none. */
    fun take(): Destination? = request.also { request = null }
}
