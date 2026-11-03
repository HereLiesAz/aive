package com.hereliesaz.geministrator

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.hereliesaz.geministrator.memory.ConsolidateNowState
import com.hereliesaz.geministrator.memory.MemoryActivity
import com.hereliesaz.geministrator.memory.MemoryElapsedUnit
import com.hereliesaz.geministrator.memory.MemoryLayerController
import com.hereliesaz.geministrator.memory.consolidateNowState
import com.hereliesaz.geministrator.memory.memoryElapsed
import com.hereliesaz.geministrator.resources.Res
import com.hereliesaz.geministrator.resources.memory_consolidate_now
import com.hereliesaz.geministrator.resources.memory_consolidate_now_working
import com.hereliesaz.geministrator.resources.memory_simple_bank
import com.hereliesaz.geministrator.resources.memory_simple_consolidate_figure
import com.hereliesaz.geministrator.resources.memory_simple_keeper_body
import com.hereliesaz.geministrator.resources.memory_simple_keeper_eyebrow
import com.hereliesaz.geministrator.resources.memory_simple_keeper_title
import com.hereliesaz.geministrator.resources.memory_simple_last_label
import com.hereliesaz.geministrator.resources.memory_simple_latest_eyebrow
import com.hereliesaz.geministrator.resources.memory_simple_latest_many
import com.hereliesaz.geministrator.resources.memory_simple_latest_none_body
import com.hereliesaz.geministrator.resources.memory_simple_latest_none_title
import com.hereliesaz.geministrator.resources.memory_simple_latest_one
import com.hereliesaz.geministrator.resources.memory_simple_observe_figure
import com.hereliesaz.geministrator.resources.memory_simple_off
import com.hereliesaz.geministrator.resources.memory_simple_paused
import com.hereliesaz.geministrator.resources.memory_simple_proposals_label
import com.hereliesaz.geministrator.resources.memory_simple_proposals_value
import com.hereliesaz.geministrator.resources.memory_simple_recall_figure
import com.hereliesaz.geministrator.resources.memory_simple_section_figure
import com.hereliesaz.geministrator.resources.memory_simple_stage_consolidate
import com.hereliesaz.geministrator.resources.memory_simple_stage_observe
import com.hereliesaz.geministrator.resources.memory_simple_stage_recall
import com.hereliesaz.geministrator.resources.memory_simple_stage_section
import com.hereliesaz.geministrator.resources.memory_simple_waiting_label
import com.hereliesaz.geministrator.resources.memory_simple_waiting_value
import com.hereliesaz.geministrator.resources.memory_simple_working
import com.hereliesaz.geministrator.resources.memory_status_last_days
import com.hereliesaz.geministrator.resources.memory_status_last_hours
import com.hereliesaz.geministrator.resources.memory_status_last_just_now
import com.hereliesaz.geministrator.resources.memory_status_last_minutes
import com.hereliesaz.geministrator.resources.memory_status_last_none
import com.hereliesaz.geministrator.resources.memory_view_label
import com.hereliesaz.geministrator.resources.memory_view_simple
import com.hereliesaz.geministrator.resources.memory_view_technical
import com.hereliesaz.geministrator.resources.tab_position
import kotlinx.coroutines.delay
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import org.jetbrains.compose.resources.stringResource

/** Below this page width the field stacks its stages two by two, its figures wrap and its notes stack. */
private val MemoryFieldNarrow: Dp = 560.dp

/** Above this field width the four stages float along one strand, as on desktop. */
private val MemoryFieldStrand: Dp = 700.dp

/**
 * The SIMPLE / TECHNICAL switch at the top of the Memory swarm: an Ink capsule whose selected half
 * is Yellow. A tab row to screen readers, with 48dp halves.
 */
@Composable
internal fun MemoryViewToggle(view: MemoryView, onView: (MemoryView) -> Unit, modifier: Modifier = Modifier) {
    val label = stringResource(Res.string.memory_view_label)
    Row(
        modifier = modifier
            .semantics { contentDescription = label }
            .clip(RoundedCornerShape(999.dp))
            .background(Azphalt.Ink)
            .padding(4.dp)
            .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        AzphaltSurface(Azphalt.Ink) {
            MemoryView.entries.forEachIndexed { index, option ->
                val selected = option == view
                val fill = if (selected) Azphalt.Yellow else Azphalt.Ink
                val position = stringResource(Res.string.tab_position, index + 1, MemoryView.entries.size)
                Box(
                    modifier = Modifier
                        .defaultMinSize(minWidth = AzphaltMinTouchTarget, minHeight = AzphaltMinTouchTarget)
                        .clip(RoundedCornerShape(999.dp))
                        .background(fill)
                        .selectable(selected = selected, role = Role.Tab, onClick = { onView(option) })
                        .semantics { stateDescription = position }
                        .padding(horizontal = 18.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    val text = when (option) {
                        MemoryView.Simple -> stringResource(Res.string.memory_view_simple)
                        MemoryView.Technical -> stringResource(Res.string.memory_view_technical)
                    }
                    Text(text.uppercase(), style = AzphaltType.capsule, color = fill.onOpposite())
                }
            }
        }
    }
}

/**
 * The Simple view: the memory field (the four stages along a strand, when consolidation last did
 * work, what waits, Consolidate now) and two notes. Every figure is real; see [memorySimpleField].
 */
@Composable
internal fun MemorySimpleView(controller: MemoryLayerController) {
    val snapshot by controller.snapshot.collectAsState()
    val activity by controller.activity.collectAsState()
    val settings by controller.settings.collectAsState()
    val knownBanks by controller.knownBanks.collectAsState()
    val selectedBank by controller.selectedBank.collectAsState()
    val proposals by RoleInstructionProposalsHost.ledger.state.collectAsState()
    val field = remember(snapshot, activity) { memorySimpleField(snapshot, activity) }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val narrow = maxWidth < MemoryFieldNarrow
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(24.dp))
                    .background(Azphalt.Ink)
                    .padding(if (narrow) 16.dp else 24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                AzphaltSurface(Azphalt.Ink) {
                    // Which bank the figures are for, only when there is more than one to mean.
                    if (knownBanks.size > 1) {
                        val bank = selectedBank ?: knownBanks.first()
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(Res.string.memory_simple_bank).uppercase(), style = AzphaltType.eyebrow, color = Azphalt.Yellow)
                            Text(bank, style = AzphaltType.body, color = Azphalt.Ink.onOpposite())
                        }
                    }
                    MemoryStrand(field)
                    MemoryFieldFigures(
                        narrow = narrow,
                        last = lastConsolidatedTitle(activity),
                        waiting = field.waiting,
                        proposals = proposals.pendingCount,
                        state = consolidateNowState(settings, activity),
                        onConsolidate = { controller.consolidateNow() },
                    )
                }
            }
            val latest = field.latestMemory
            val notes: List<@Composable (Modifier) -> Unit> = listOf(
                { m ->
                    MemoryNoteCard(
                        eyebrow = stringResource(Res.string.memory_simple_latest_eyebrow),
                        title = latest?.text ?: stringResource(Res.string.memory_simple_latest_none_title),
                        body = when {
                            latest == null -> stringResource(Res.string.memory_simple_latest_none_body)
                            latest.sourceEpisodeIds.size == 1 -> stringResource(Res.string.memory_simple_latest_one)
                            latest.sourceEpisodeIds.size > 1 -> stringResource(Res.string.memory_simple_latest_many, latest.sourceEpisodeIds.size)
                            else -> null
                        },
                        modifier = m,
                    )
                },
                { m ->
                    MemoryNoteCard(
                        eyebrow = stringResource(Res.string.memory_simple_keeper_eyebrow),
                        title = stringResource(Res.string.memory_simple_keeper_title),
                        body = stringResource(Res.string.memory_simple_keeper_body),
                        modifier = m,
                    )
                },
            )
            if (narrow) {
                notes.forEach { it(Modifier.fillMaxWidth()) }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    notes.forEach { it(Modifier.weight(1f)) }
                }
            }
        }
    }
}

/** The four stages along a dashed Yellow strand, each card on it; two by two when the field is narrow. */
@Composable
private fun MemoryStrand(field: MemorySimpleField) {
    val stages = MemorySimpleStage.entries
    val centers = remember { mutableStateMapOf<Int, Offset>() }
    var origin by remember { mutableStateOf(Offset.Zero) }
    fun track(index: Int) = Modifier.onGloballyPositioned { c: LayoutCoordinates ->
        centers[index] = c.boundsInRoot().center
    }
    val figures = stages.map { stage ->
        when (stage) {
            MemorySimpleStage.Observe -> stringResource(Res.string.memory_simple_observe_figure, field.sessionsBanked)
            MemorySimpleStage.Section -> stringResource(Res.string.memory_simple_section_figure, field.sections)
            MemorySimpleStage.Consolidate -> stringResource(Res.string.memory_simple_consolidate_figure, field.memories, field.links)
            MemorySimpleStage.Recall -> stringResource(Res.string.memory_simple_recall_figure, field.recalls)
        }
    }
    val names = listOf(
        stringResource(Res.string.memory_simple_stage_observe),
        stringResource(Res.string.memory_simple_stage_section),
        stringResource(Res.string.memory_simple_stage_consolidate),
        stringResource(Res.string.memory_simple_stage_recall),
    )
    val working = stringResource(Res.string.memory_simple_working)
    BoxWithConstraints(
        Modifier.fillMaxWidth().onGloballyPositioned { origin = it.positionInRoot() },
    ) {
        val strand = maxWidth >= MemoryFieldStrand
        // The strand under the cards, through their centres in order.
        Canvas(Modifier.matchParentSize()) {
            val points = stages.indices.mapNotNull { centers[it]?.minus(origin) }
            if (points.size < 2) return@Canvas
            val path = Path().apply {
                moveTo(points.first().x, points.first().y)
                points.zipWithNext().forEach { (a, b) ->
                    val mid = (a.x + b.x) / 2f
                    cubicTo(mid, a.y, mid, b.y, b.x, b.y)
                }
            }
            val dash = 9.dp.toPx()
            drawPath(
                path,
                color = Azphalt.Yellow.copy(alpha = 0.45f),
                style = Stroke(width = 4.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(dash, dash))),
            )
            points.zipWithNext().forEachIndexed { i, (a, b) ->
                val at = Offset((a.x + b.x) / 2f, (a.y + b.y) / 2f)
                val r = (if (i == 1) 9 else 7).dp.toPx()
                drawCircle(Azphalt.Yellow.copy(alpha = 0.25f), radius = r * 2f, center = at)
                drawCircle(Azphalt.Yellow, radius = r, center = at)
            }
        }
        val card: @Composable (Int, Modifier) -> Unit = { i, m ->
            MemoryStageCard(
                number = "0${i + 1}",
                name = names[i],
                figure = figures[i],
                working = if (field.working == stages[i]) working else null,
                modifier = m.then(track(i)),
            )
        }
        if (strand) {
            // Figma's stations along the strand: left edge, a third in, past half, right edge.
            val width = minOf(200.dp, maxWidth * 0.24f)
            val height = 340.dp
            val xs = listOf(maxWidth * 0.03f, maxWidth * 0.31f, maxWidth * 0.59f, maxWidth * 0.98f - width)
            val ys = listOf(0.30f, 0.12f, 0.40f, 0.18f)
            Box(Modifier.fillMaxWidth().height(height)) {
                stages.indices.forEach { i ->
                    card(i, Modifier.width(width).offset(x = xs[i], y = height * ys[i]))
                }
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                listOf(0 to 1, 2 to 3).forEach { (a, b) ->
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        card(a, Modifier.weight(1f))
                        Box(Modifier.weight(1f).padding(top = 32.dp)) { card(b, Modifier.fillMaxWidth()) }
                    }
                }
            }
        }
    }
}

@Composable
private fun MemoryStageCard(number: String, name: String, figure: String, working: String?, modifier: Modifier) {
    val description = listOfNotNull(name, figure, working).joinToString(", ")
    Column(
        modifier = modifier
            .semantics(mergeDescendants = true) { contentDescription = description }
            .clip(RoundedCornerShape(16.dp))
            .background(AzphaltLiftedInk)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        AzphaltSurface(AzphaltLiftedInk) {
            val content = AzphaltLiftedInk.onOpposite()
            Text(
                if (working == null) number else "$number · ${working.uppercase()}",
                style = AzphaltType.eyebrow,
                color = Azphalt.Yellow,
                modifier = if (working != null) Modifier.azphaltLiveRegion() else Modifier,
            )
            Text(name, style = AzphaltType.lead.copy(fontWeight = FontWeight.Black), color = content)
            Text(figure, style = AzphaltType.body, color = content)
        }
    }
}

/** The line under the strand: last consolidation, waiting, proposals, then Consolidate now. */
@Composable
private fun MemoryFieldFigures(
    narrow: Boolean,
    last: String,
    waiting: Int,
    proposals: Int,
    state: ConsolidateNowState,
    onConsolidate: () -> Unit,
) {
    val figures: List<Pair<String, String>> = listOf(
        stringResource(Res.string.memory_simple_last_label) to last,
        stringResource(Res.string.memory_simple_waiting_label) to stringResource(Res.string.memory_simple_waiting_value, waiting),
        stringResource(Res.string.memory_simple_proposals_label) to stringResource(Res.string.memory_simple_proposals_value, proposals),
    )
    val action: @Composable (Modifier) -> Unit = { m ->
        Box(m, contentAlignment = if (narrow) Alignment.Center else Alignment.CenterEnd) {
            when (state) {
                ConsolidateNowState.Ready -> AzphaltPill(
                    stringResource(Res.string.memory_consolidate_now),
                    "memory-consolidate-now",
                    onClick = onConsolidate,
                    tone = PillTone.Primary,
                    modifier = if (narrow) Modifier.fillMaxWidth() else Modifier,
                )
                ConsolidateNowState.Working -> FieldNote(stringResource(Res.string.memory_consolidate_now_working))
                ConsolidateNowState.Paused -> FieldNote(stringResource(Res.string.memory_simple_paused))
                ConsolidateNowState.Off -> FieldNote(stringResource(Res.string.memory_simple_off))
            }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(AzphaltLiftedInk))
        if (narrow) {
            figures.chunked(2).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    row.forEach { (label, value) -> FieldFigure(label, value, Modifier.weight(1f)) }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
            action(Modifier.fillMaxWidth())
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(18.dp), verticalAlignment = Alignment.CenterVertically) {
                figures.forEach { (label, value) -> FieldFigure(label, value, Modifier.weight(1f)) }
                action(Modifier.weight(1.1f))
            }
        }
    }
}

@Composable
private fun FieldFigure(label: String, value: String, modifier: Modifier) {
    Column(modifier.semantics(mergeDescendants = true) {}.azphaltLiveRegion(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label.uppercase(), style = AzphaltType.eyebrow, color = Azphalt.Yellow)
        Text(value, style = AzphaltType.body, color = Azphalt.Ink.onOpposite())
    }
}

@Composable
private fun FieldNote(text: String) {
    Text(text, style = AzphaltType.body, color = Azphalt.Ink.onOpposite(), modifier = Modifier.azphaltLiveRegion())
}

/** One of the two notes under the field: outlined on the page ground. */
@Composable
private fun MemoryNoteCard(eyebrow: String, title: String, body: String?, modifier: Modifier) {
    val content = azphaltOnSurface()
    Column(
        modifier = modifier
            .semantics(mergeDescendants = true) {}
            .clip(RoundedCornerShape(20.dp))
            .border(2.dp, content, RoundedCornerShape(20.dp))
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(eyebrow.uppercase(), style = AzphaltType.eyebrow, color = content)
        Text(title, style = AzphaltType.lead.copy(fontWeight = FontWeight.Black), color = content, maxLines = 3)
        if (body != null) Text(body, style = AzphaltType.body, color = content)
    }
}

/**
 * "Last consolidated 5 min ago", or that nothing was consolidated since this start. The time is
 * kept in memory only ([MemoryActivity.lastConsolidatedAtEpochMillis]); it re-reads the clock every
 * 30 seconds while shown.
 */
@OptIn(ExperimentalTime::class)
@Composable
internal fun lastConsolidatedTitle(activity: MemoryActivity): String {
    var now by remember { mutableStateOf(Clock.System.now().toEpochMilliseconds()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            now = Clock.System.now().toEpochMilliseconds()
        }
    }
    val last = activity.lastConsolidatedAtEpochMillis?.let { memoryElapsed(it, maxOf(now, it)) }
    return if (last == null) stringResource(Res.string.memory_status_last_none) else when (last.unit) {
        MemoryElapsedUnit.JustNow -> stringResource(Res.string.memory_status_last_just_now)
        MemoryElapsedUnit.Minutes -> stringResource(Res.string.memory_status_last_minutes, last.amount)
        MemoryElapsedUnit.Hours -> stringResource(Res.string.memory_status_last_hours, last.amount)
        MemoryElapsedUnit.Days -> stringResource(Res.string.memory_status_last_days, last.amount)
    }
}
