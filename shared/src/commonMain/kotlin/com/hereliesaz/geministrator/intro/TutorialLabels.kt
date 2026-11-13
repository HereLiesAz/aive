package com.hereliesaz.geministrator.intro

import com.hereliesaz.geministrator.resources.Res
import com.hereliesaz.geministrator.resources.build_build
import com.hereliesaz.geministrator.resources.build_live
import com.hereliesaz.geministrator.resources.creature_tab_mind
import com.hereliesaz.geministrator.resources.creature_tab_needs
import com.hereliesaz.geministrator.resources.creature_tab_work
import com.hereliesaz.geministrator.resources.first_hive_feed
import com.hereliesaz.geministrator.resources.first_hive_objective
import com.hereliesaz.geministrator.resources.first_hive_options
import com.hereliesaz.geministrator.resources.hive_pause_run
import com.hereliesaz.geministrator.resources.hive_stop_run
import com.hereliesaz.geministrator.resources.keeper_group_allowance
import com.hereliesaz.geministrator.resources.keeper_group_hosts
import com.hereliesaz.geministrator.resources.keeper_group_look
import com.hereliesaz.geministrator.resources.keeper_group_routines
import com.hereliesaz.geministrator.resources.keeper_group_sources
import com.hereliesaz.geministrator.resources.keeper_room
import com.hereliesaz.geministrator.resources.rail_hive
import com.hereliesaz.geministrator.resources.rail_inbox
import com.hereliesaz.geministrator.resources.rail_memory
import com.hereliesaz.geministrator.resources.rail_roster
import com.hereliesaz.geministrator.resources.rail_stable
import com.hereliesaz.geministrator.resources.rail_store
import org.jetbrains.compose.resources.getString

/**
 * The visible name of every control the tutorial layer can point at, by string resource key, read from the app's own
 * resources in the keeper's language. Resource keys are compile-time, so this is the one place that lists them; a test
 * checks it has a row for every registered target.
 */
internal suspend fun tutorialLabels(): Map<String, String> = mapOf(
        "rail_inbox" to getString(Res.string.rail_inbox),
        "keeper_room" to getString(Res.string.keeper_room),
        "first_hive_objective" to getString(Res.string.first_hive_objective),
        "first_hive_options" to getString(Res.string.first_hive_options),
        "first_hive_feed" to getString(Res.string.first_hive_feed),
        "rail_hive" to getString(Res.string.rail_hive),
        "rail_roster" to getString(Res.string.rail_roster),
        "rail_stable" to getString(Res.string.rail_stable),
        "rail_memory" to getString(Res.string.rail_memory),
        "rail_store" to getString(Res.string.rail_store),
        "build_live" to getString(Res.string.build_live),
        "build_build" to getString(Res.string.build_build),
        "hive_pause_run" to getString(Res.string.hive_pause_run),
        "hive_stop_run" to getString(Res.string.hive_stop_run),
        "creature_tab_needs" to getString(Res.string.creature_tab_needs),
        "creature_tab_work" to getString(Res.string.creature_tab_work),
        "creature_tab_mind" to getString(Res.string.creature_tab_mind),
        "keeper_group_sources" to getString(Res.string.keeper_group_sources),
        "keeper_group_allowance" to getString(Res.string.keeper_group_allowance),
        "keeper_group_hosts" to getString(Res.string.keeper_group_hosts),
        "keeper_group_routines" to getString(Res.string.keeper_group_routines),
        "keeper_group_look" to getString(Res.string.keeper_group_look),
)
