package com.hereliesaz.geministrator

/** Where the bottom sheet of the flipped card stands: not shown, at its peek, or dragged up to the full creature sheet. */
internal enum class SheetDetent { None, Peek, Expanded }

/**
 * Which node of the hive shows its card, if any. Only one node is flipped at a time, so this is the
 * hive's whole flip state: [flippedId] null is every creature facing front; otherwise that node shows
 * the card (the back of the creature), and [expanded] says the keeper dragged its sheet up from the peek.
 */
internal data class CardFlip(val flippedId: String? = null, val expanded: Boolean = false) {
    /** The sheet follows the card: absent while every creature faces front, at its peek until it is dragged up. */
    val detent: SheetDetent
        get() = when {
            flippedId == null -> SheetDetent.None
            expanded -> SheetDetent.Expanded
            else -> SheetDetent.Peek
        }

    fun isFlipped(id: String): Boolean = flippedId == id
}

/** What can happen to the flip. A pure input to [reduceCardFlip]; no gesture or composable is named here. */
internal sealed interface CardFlipEvent {
    /** A tap on the node's visible face: the creature turns to its card, or the card turns back. */
    data class Tap(val id: String) : CardFlipEvent

    /** A tap on a node other than the flipped one: it flips, and the first flips back. */
    data class OtherNodeTapped(val id: String) : CardFlipEvent

    /** A swipe up, down, left or right across the card of [id]. */
    data class SwipeAny(val id: String) : CardFlipEvent

    /** The keeper dragged the sheet up past its peek. */
    data object SheetExpanded : CardFlipEvent

    /** The sheet came back down to its peek. */
    data object SheetPeeked : CardFlipEvent

    /** Back or Escape. */
    data object Back : CardFlipEvent
}

/**
 * The flip as a pure reducer. Front, no node flipped: a tap flips that node and shows the sheet at its peek.
 * Card: a tap on it, or a swipe across it while the sheet is still at its peek, flips it back and dismisses the
 * sheet; once the sheet is expanded a swipe is the sheet's own (scroll or collapse) and changes nothing; Back
 * always flips back; a tap on another node moves the flip there.
 */
internal fun reduceCardFlip(state: CardFlip, event: CardFlipEvent): CardFlip = when (event) {
    is CardFlipEvent.Tap -> when (state.flippedId) {
        event.id -> CardFlip()
        null -> CardFlip(flippedId = event.id)
        else -> reduceCardFlip(state, CardFlipEvent.OtherNodeTapped(event.id))
    }
    is CardFlipEvent.OtherNodeTapped -> if (state.flippedId == event.id) state else CardFlip(flippedId = event.id)
    is CardFlipEvent.SwipeAny -> if (state.flippedId == event.id && !state.expanded) CardFlip() else state
    CardFlipEvent.SheetExpanded -> if (state.flippedId != null) state.copy(expanded = true) else state
    CardFlipEvent.SheetPeeked -> if (state.flippedId != null) state.copy(expanded = false) else state
    CardFlipEvent.Back -> CardFlip()
}

/**
 * Whether the creature's bottom sheet may open on a phone. Live opens it as before; Edit opens it only while a
 * card is flipped, at its peek, because the card's own detail is the sheet's first content there.
 */
internal fun creatureSheetAllowed(editing: Boolean, flipped: Boolean): Boolean = !editing || flipped

/** The Flowchart view was merged into Edit: a stored Flowchart view lands on Edit, anything else is unchanged. */
internal fun migratedToEdit(storedFlowchartView: Boolean, startInEdit: Boolean): Boolean = startInEdit || storedFlowchartView
