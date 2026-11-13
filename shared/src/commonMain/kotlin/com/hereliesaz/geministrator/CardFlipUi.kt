package com.hereliesaz.geministrator

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.BottomSheetScaffold
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberBottomSheetScaffoldState
import androidx.compose.material3.rememberStandardBottomSheetState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hereliesaz.geministrator.domain.TaskRunStatus
import com.hereliesaz.geministrator.workflow.FlowNode

/** How long the creature takes to turn over to its card (and back), in milliseconds. */
internal const val CARD_FLIP_MS = 360

/** How long the quick crossfade that stands in for the turn takes when motion is reduced, in milliseconds. */
internal const val CARD_FLIP_REDUCED_MS = 90

/**
 * The hive's one flip, shared by the field that turns the creatures and the sheet or docked panel that shows
 * the card's detail. [state] is [CardFlip]; [editing] says the field is in Edit (the only place creatures flip);
 * [detail] draws the card's detail sentence and its one button for the flipped node (set by the field, which
 * holds the staged hive and the run).
 */
internal class CardFlipHost {
    var state by mutableStateOf(CardFlip())
    var editing by mutableStateOf(false)
    var detail: (@Composable (taskId: String, modifier: Modifier) -> Unit)? = null

    fun send(event: CardFlipEvent) {
        state = reduceCardFlip(state, event)
    }
}

/** Null where there is no shell to host a sheet (a preview, the workflow editor's bowl). */
internal val LocalCardFlip = staticCompositionLocalOf<CardFlipHost?> { null }

/** What the terrarium needs to turn one creature over: who is flipped, every node's card, and where events go. */
internal class NodeFlip(
    val flippedId: String?,
    val cards: Map<String, FlowNode>,
    val send: (CardFlipEvent) -> Unit,
)

/** What a card says to a screen reader: the step, who does it, and how it stands. */
internal fun flowCardDescription(node: FlowNode, state: String?): String {
    val who = flowCardWho(node)
    return listOfNotNull(node.name, who.takeIf { it.isNotEmpty() }, state).joinToString(", ")
}

internal fun flowCardWho(node: FlowNode): String =
    listOfNotNull(node.role, node.kind.takeIf { node.role == null }).joinToString(" · ")

/**
 * The back of a creature: the step's card, as the Flowchart drew it. A box on the state's colour (the same as a
 * creature's chip) with the step's name, its role (or what kind of executor does it) and its state in words; a
 * step a condition skipped is dimmed, not red; the selected card has the strong border. It draws and decides nothing.
 */
@Composable
internal fun FlowCardFace(node: FlowNode, selected: Boolean, modifier: Modifier = Modifier) {
    // A step that was cancelled is usually one a condition skipped ("if it fails" when it did not), so it
    // is shown dimmed rather than as a failure; only a step that failed or was escalated is red.
    val skipped = node.status == TaskRunStatus.Cancelled
    val fill = when {
        skipped -> Azphalt.Ink.copy(alpha = 0.5f)
        else -> when (creatureStateTone(node.status)) {
            CreatureStateTone.Working -> Azphalt.Yellow
            CreatureStateTone.Complete -> ClaspGreen
            CreatureStateTone.Waiting -> Azphalt.Ink
            CreatureStateTone.Gated, CreatureStateTone.Failed -> AzphaltDanger
        }
    }
    val text = if (skipped) Color.White else fill.onOpposite()
    val state = node.status?.label()
    val who = flowCardWho(node)
    Column(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .background(fill)
            .border(if (selected) 3.dp else 1.dp, if (selected) azphaltStrong() else Color.White.copy(alpha = 0.25f), RoundedCornerShape(12.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Text(node.name, style = AzphaltType.body.copy(fontSize = 13.sp), color = text, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (who.isNotEmpty()) {
            Text(who, style = AzphaltType.endCap.copy(fontSize = 10.sp), color = text, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        state?.let { Text(it.uppercase(), style = AzphaltType.endCap.copy(fontSize = 10.sp), color = text, maxLines = 1) }
    }
}

/** How much of the flipped card's sheet shows before it is dragged up: its handle and the card's detail. */
internal val CardFlipPeekHeight: Dp = 224.dp

/**
 * On a phone, the shell's body with Edit's sheet over it. The sheet is not modal: it shows itself at its peek
 * (partially expanded, not full height) when [showSheet], leaves the creatures under it tappable (so the card
 * can be tapped, or swiped, to flip back), and drags up to the creature's whole sheet. It reports how far it
 * stands to [host] ([CardFlipEvent.SheetExpanded] and [CardFlipEvent.SheetPeeked]) so a swipe on the card only
 * flips back while the sheet is at its peek, and expands when [host] says so (a card's button opening a tab).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CardFlipSheetHost(
    host: CardFlipHost,
    enabled: Boolean,
    showSheet: Boolean,
    sheet: @Composable ColumnScope.() -> Unit,
    content: @Composable () -> Unit,
) {
    if (!enabled) {
        content()
        return
    }
    val sheetState = rememberStandardBottomSheetState(initialValue = SheetValue.PartiallyExpanded, skipHiddenState = true)
    val scaffoldState = rememberBottomSheetScaffoldState(sheetState)
    LaunchedEffect(sheetState) {
        snapshotFlow { sheetState.currentValue }.collect { value ->
            host.send(if (value == SheetValue.Expanded) CardFlipEvent.SheetExpanded else CardFlipEvent.SheetPeeked)
        }
    }
    val expanded = host.state.expanded
    LaunchedEffect(showSheet, expanded) {
        if (!showSheet) {
            if (sheetState.currentValue == SheetValue.Expanded) sheetState.partialExpand()
        } else if (expanded && sheetState.currentValue != SheetValue.Expanded) {
            sheetState.expand()
        }
    }
    BottomSheetScaffold(
        sheetContent = { if (showSheet) sheet() },
        scaffoldState = scaffoldState,
        sheetPeekHeight = if (showSheet) CardFlipPeekHeight else 0.dp,
        sheetSwipeEnabled = showSheet,
        sheetContainerColor = Azphalt.Ink,
        sheetContentColor = Azphalt.Ink.onOpposite(),
        sheetDragHandle = { if (showSheet) BottomSheetDefaults.DragHandle(color = Azphalt.Ink.mutedOnOpposite()) },
        containerColor = Color.Transparent,
        contentColor = LocalContentColor.current,
    ) { _ -> content() }
}
