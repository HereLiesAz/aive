package com.hereliesaz.geministrator.tour

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned

/**
 * The closed kinds of observable event a lesson step may wait for (docs: the tutorial design, 6.4).
 * [wire] is the spelling in a lesson payload. Only [Tapped] and [Opened] are observed by the overlay layer itself;
 * the others are observed by the follow creature from stores and the run's journal (the planner and engine work).
 */
enum class ExpectKind(val wire: String) {
    Tapped("tapped"),
    Opened("opened"),
    KeeperSection("keeperSection"),
    StoryView("storyView"),
    Approved("approved"),
    SourceConnected("sourceConnected"),
    SpendCapSet("spendCapSet"),
    SourcePinned("sourcePinned"),
    Paused("paused"),
    ViewedLong("viewedLong");

    companion object {
        fun fromWire(text: String): ExpectKind? = entries.firstOrNull { it.wire == text }
    }
}

/**
 * Every real control the tutorial overlay can point at: a closed set with stable semantic ids (never a position, never
 * a label text). A control is registered by tagging it with [tourTarget]. This enum is the registry: lesson validators
 * check a payload's target id against [registeredIds], and a source scan test asserts every id here is tagged on a
 * control in commonMain. It lists what CAN be pointed at; it is not an order of anything to teach.
 */
enum class TourTargetId(
    /** Key of the string resource holding the control's visible name (also in the UI word index). */
    val labelKey: String,
    /** Plain "where it lives", given to the planner as reference. */
    val place: String,
    /** Targets that must have been introduced before this one can be taught. */
    val needs: Set<TourTargetId> = emptySet(),
    /** True when the control is on screen on every screen of the shell. */
    val alwaysVisible: Boolean = false,
    /** The kinds of event that can be observed for this control. */
    val observable: Set<ExpectKind> = setOf(ExpectKind.Tapped),
) {
    MESSAGES_BUTTON(
        labelKey = "rail_inbox",
        place = "in the header on a phone, in the left rail on a wide window",
        alwaysVisible = true,
        observable = setOf(ExpectKind.Tapped),
    ),
    KEEPERS_ROOM_GEAR(
        labelKey = "keeper_room",
        place = "in the header on a phone, at the foot of the left rail on a wide window",
        alwaysVisible = true,
        observable = setOf(ExpectKind.Tapped),
    ),
    GOAL_FIELD(
        labelKey = "first_hive_objective",
        place = "on the first screen, where the keeper writes the hive (the goal)",
    ),
    OPTIONS_PILL(
        labelKey = "first_hive_options",
        place = "on the first screen, under the goal",
    ),
    FEED_IT_BUTTON(
        labelKey = "first_hive_feed",
        place = "on the first screen, under the goal and its options",
    ),
    RAIL_HIVE(
        labelKey = "rail_hive",
        place = "left rail of a wide window",
    ),
    RAIL_ROSTER(
        labelKey = "rail_roster",
        place = "left rail of a wide window",
    ),
    RAIL_STABLE(
        labelKey = "rail_stable",
        place = "left rail of a wide window",
    ),
    RAIL_MEMORY(
        labelKey = "rail_memory",
        place = "left rail of a wide window",
    ),
    RAIL_STORE(
        labelKey = "rail_store",
        place = "left rail of a wide window",
    ),
    VIEW_LIVE(
        labelKey = "build_live",
        place = "the view switch above a hive",
    ),
    VIEW_EDIT(
        labelKey = "build_build",
        place = "the view switch above a hive",
    ),
    HIVE_PAUSE(
        labelKey = "hive_pause_run",
        place = "above a hive while it runs",
    ),
    HIVE_STOP(
        labelKey = "hive_stop_run",
        place = "above a hive while it runs",
    ),
    CREATURE_TAB_NEEDS(
        labelKey = "creature_tab_needs",
        place = "on a creature's sheet, after a creature is opened",
    ),
    CREATURE_TAB_WORK(
        labelKey = "creature_tab_work",
        place = "on a creature's sheet, after a creature is opened",
    ),
    CREATURE_TAB_MIND(
        labelKey = "creature_tab_mind",
        place = "on a creature's sheet, after a creature is opened",
    ),
    KEEPER_SOURCES(
        labelKey = "keeper_group_sources",
        place = "a part of the Keeper's room (a list on the left of a wide window, a list of parts on a phone)",
        needs = setOf(TourTargetId.KEEPERS_ROOM_GEAR),
    ),
    KEEPER_ALLOWANCE(
        labelKey = "keeper_group_allowance",
        place = "a part of the Keeper's room (a list on the left of a wide window, a list of parts on a phone)",
        needs = setOf(TourTargetId.KEEPERS_ROOM_GEAR),
    ),
    KEEPER_HOSTS(
        labelKey = "keeper_group_hosts",
        place = "a part of the Keeper's room (a list on the left of a wide window, a list of parts on a phone)",
        needs = setOf(TourTargetId.KEEPERS_ROOM_GEAR),
    ),
    KEEPER_ROUTINES(
        labelKey = "keeper_group_routines",
        place = "a part of the Keeper's room (a list on the left of a wide window, a list of parts on a phone)",
        needs = setOf(TourTargetId.KEEPERS_ROOM_GEAR),
    ),
    KEEPER_LOOK(
        labelKey = "keeper_group_look",
        place = "a part of the Keeper's room (a list on the left of a wide window, a list of parts on a phone)",
        needs = setOf(TourTargetId.KEEPERS_ROOM_GEAR),
    );

    companion object {
        /** The ids a lesson may name. */
        val registeredIds: Set<String> = entries.map { it.name }.toSet()

        fun fromId(id: String): TourTargetId? = entries.firstOrNull { it.name == id }
    }
}

/** One registry row as the validators and the planner packet see it: plain data, no Compose. */
data class TargetRow(
    val id: String,
    val label: String,
    val needs: Set<String>,
    val alwaysVisible: Boolean,
    val observable: Set<ExpectKind>,
)

/** The registry as rows; [labelOf] resolves a string resource key to its text (null: the row is dropped). */
fun tourTargetRows(labelOf: (String) -> String?): List<TargetRow> = TourTargetId.entries.mapNotNull { t ->
    val label = labelOf(t.labelKey) ?: return@mapNotNull null
    TargetRow(t.name, label, t.needs.map { it.name }.toSet(), t.alwaysVisible, t.observable)
}

/** An observed fact about the real screen. Booleans only: never a field's value. */
sealed interface TourEvent {
    data class Tapped(val id: TourTargetId) : TourEvent
    data class Opened(val id: TourTargetId) : TourEvent
}

/**
 * Where tagged controls report their window bounds and taps. Bounds are always kept (cheap); taps and "opened" are
 * reported only while [tracking] is on (a tour is active). It never reads a control's content and never acts for the
 * keeper.
 */
object TourTargetRegistry {
    private val bounds = mutableStateMapOf<TourTargetId, Rect>()
    private val trackingState = mutableStateOf(false)

    /** The overlay's listener for tap and open reports. */
    internal var observer: ((TourEvent) -> Unit)? = null

    var tracking: Boolean
        get() = trackingState.value
        internal set(value) {
            trackingState.value = value
        }

    /** Where the control is now, or null while it is not on screen. */
    fun boundsOf(id: TourTargetId): Rect? = bounds[id]

    /** The first of [ids] that is on screen now (a phone and a wide layout host one control in different places). */
    fun firstPresent(ids: List<TourTargetId>): TourTargetId? = ids.firstOrNull { bounds[it] != null }

    internal fun register(id: TourTargetId, rect: Rect) {
        if (bounds[id] != rect) bounds[id] = rect
    }

    internal fun unregister(id: TourTargetId) {
        bounds.remove(id)
    }

    internal fun reportTapped(id: TourTargetId) {
        if (tracking) observer?.invoke(TourEvent.Tapped(id))
    }

    internal fun reportOpened(id: TourTargetId) {
        if (tracking) observer?.invoke(TourEvent.Opened(id))
    }
}

/**
 * Tags a real control with its stable tutorial id. Invisible: it draws nothing, changes no layout, consumes no pointer
 * event and no focus, and adds no semantics. It only reports the control's bounds, that it entered the tree, and that
 * a press-and-release happened on it (the last two only while a tour is active).
 */
fun Modifier.tourTarget(id: TourTargetId): Modifier = composed {
    DisposableEffect(id) {
        TourTargetRegistry.reportOpened(id)
        onDispose { TourTargetRegistry.unregister(id) }
    }
    Modifier
        .onGloballyPositioned { coordinates ->
            // Always recorded (one equality check and, only when it moved, one map write), so a tour that starts
            // while the control is already on screen can find it without a new layout pass.
            TourTargetRegistry.register(id, coordinates.boundsInWindow())
        }
        .pointerInput(id) {
            awaitEachGesture {
                // The initial pass sees the press before the control does, and nothing is consumed here.
                awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                val up = waitForUpOrCancellation(pass = PointerEventPass.Initial)
                if (up != null) TourTargetRegistry.reportTapped(id)
            }
        }
}
