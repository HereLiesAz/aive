package com.hereliesaz.geministrator

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * One place on the way back: the destination and, when a creature's panel was open there, that creature and its
 * tab (read from the durable `creature.panel.tab` key), so Back from Connections, Compute or Limits lands on the
 * creature and tab the keeper left, not on the bare hive.
 */
internal data class NavEntry(
    val destination: ControlRoomDestination,
    val taskId: String = "",
    val tab: String = "",
)

/**
 * The way back after going to [target] from [from]. The hive is home: reaching it clears the way back. Returning to
 * a place already on the way back pops to it, so Back never ping-pongs and the history never grows past the places
 * actually open. Anything else pushes where the keeper was.
 */
internal fun historyAfterNavigate(history: List<NavEntry>, from: NavEntry, target: ControlRoomDestination): List<NavEntry> {
    val earlier = history.indexOfFirst { it.destination == target }
    return when {
        target == ControlRoomDestination.Overview -> emptyList()
        earlier >= 0 -> history.take(earlier)
        else -> history + from
    }
}

/** A creature (and its tab) to reopen after Back, handed to the shell and consumed by it once the creature is selected. */
internal object CreatureTabRestore {
    var taskId: String by mutableStateOf("")
    var tab: String by mutableStateOf("")
}

/** The tab to open [selectedTaskId] on: the restored one when it was restored for that creature, else [default]. */
internal fun restoredTabOrDefault(restoredTask: String, restoredTab: String, selectedTaskId: String, default: String): String =
    if (restoredTask == selectedTaskId && restoredTab.isNotEmpty()) restoredTab else default
