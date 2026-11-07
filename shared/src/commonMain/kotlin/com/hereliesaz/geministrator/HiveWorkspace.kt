package com.hereliesaz.geministrator

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hereliesaz.geministrator.domain.AgentCapability
import com.hereliesaz.geministrator.domain.RoleDefinition
import com.hereliesaz.geministrator.domain.TaskRunStatus
import com.hereliesaz.geministrator.domain.WorkflowRun
import com.hereliesaz.geministrator.domain.WorkflowRunId
import com.hereliesaz.geministrator.domain.WorkflowRunStatus
import com.hereliesaz.geministrator.resources.Res
import com.hereliesaz.geministrator.resources.build_build
import com.hereliesaz.geministrator.resources.build_live
import com.hereliesaz.geministrator.resources.build_redo
import com.hereliesaz.geministrator.resources.build_undo
import com.hereliesaz.geministrator.resources.creature_panel_close
import com.hereliesaz.geministrator.resources.creature_panel_place
import com.hereliesaz.geministrator.resources.hive_active_of
import com.hereliesaz.geministrator.resources.hive_centre
import com.hereliesaz.geministrator.resources.hive_creatures_active
import com.hereliesaz.geministrator.resources.hive_feed_eyebrow
import com.hereliesaz.geministrator.resources.hive_feed_launch
import com.hereliesaz.geministrator.resources.hive_feed_placeholder
import com.hereliesaz.geministrator.resources.hive_feed_queue
import com.hereliesaz.geministrator.resources.hive_feed_queued
import com.hereliesaz.geministrator.resources.hive_feed_title
import com.hereliesaz.geministrator.resources.hive_feed_unqueue
import com.hereliesaz.geministrator.resources.hive_legend_complete
import com.hereliesaz.geministrator.resources.hive_legend_note
import com.hereliesaz.geministrator.resources.hive_legend_waiting
import com.hereliesaz.geministrator.resources.hive_legend_working
import com.hereliesaz.geministrator.resources.hive_run_number
import com.hereliesaz.geministrator.resources.inspector_provider
import com.hereliesaz.geministrator.resources.mind_budget
import com.hereliesaz.geministrator.resources.mind_no_budget
import com.hereliesaz.geministrator.resources.mind_role
import com.hereliesaz.geministrator.resources.mind_tools
import com.hereliesaz.geministrator.resources.terrarium_zoom_in
import com.hereliesaz.geministrator.resources.terrarium_zoom_out
import org.jetbrains.compose.resources.stringResource

// The wide hive page (docs/Keepers-Room.md, "The hive on wide screens"): at ControlRoomBreakpoints.Wide
// and up the hive sits in one frame (its controls bar, the tissue, its legend and the feed bar under it)
// and a creature's panel docks to its right instead of rising as a sheet. Phones keep their layout.

/** The docked creature panel's width on wide screens. */
internal val DockedCreaturePanelWidth: Dp = 310.dp

private val FrameCorner = 19.dp
private val FrameSide = 5.dp

/**
 * Where the field looks: its zoom and the point it is centred on (0..1 of the field). Held by the
 * hive frame so the controls bar can drive it; the field still pinches, pans to a selected creature
 * and surfaces past its widest view, as before.
 */
@Stable
internal class TerrariumCamera {
    var zoom by mutableStateOf(1f)
    var focus by mutableStateOf(Offset(0.5f, 0.5f))

    /** The semantic views of the selection (TerrariumFocus.kt); the camera is put on [FocusStack.current]. */
    var stack by mutableStateOf(FocusStack())

    /** True once a finger has pinched or panned away from the current view; recentring ends it. */
    var free by mutableStateOf(false)

    /** Bumped to have the current view applied again (recentre). */
    var applyTick by mutableStateOf(0)

    /** Whether the controls (a bar, not the field's own stepper) drive this camera. */
    var driven by mutableStateOf(false)

    /** Leaves out the field's own zoom controls and level caption: a field that only watches (the planning field). */
    var controlsHidden by mutableStateOf(false)

    /** In: back toward the creature through the levels already passed; with no selection, a plain zoom in. */
    fun stepIn() {
        if (stack.canStepIn) {
            stack = stack.stepIn()
            free = false
            applyTick++
        } else if (stack.steps.size <= 1) zoomIn()
    }

    /** Out: toward the whole hive; from the Overview (or with no selection at the widest view), [onSurface]. */
    fun stepOut(onSurface: (() -> Unit)?) {
        if (stack.canStepOut) {
            stack = stack.stepOut()
            free = false
            applyTick++
        } else if (stack.steps.size <= 1) zoomOut(onSurface) else onSurface?.invoke()
    }

    fun zoomIn() {
        zoom = (zoom + ZOOM_STEP).coerceAtMost(MAX_ZOOM)
    }

    /** Out a step; from the widest view, [onSurface] (out to the district) when there is one. */
    fun zoomOut(onSurface: (() -> Unit)?) {
        if (zoom <= MIN_ZOOM && onSurface != null) onSurface() else zoom = (zoom - ZOOM_STEP).coerceAtLeast(MIN_ZOOM)
    }

    /** The whole hive again: zoom 1, centred. */
    fun centre() {
        zoom = 1f
        focus = Offset(0.5f, 0.5f)
        free = false
        applyTick++
    }

    companion object {
        const val MIN_ZOOM = 0.70f
        const val MAX_ZOOM = 2.4f
        const val ZOOM_STEP = 0.20f
    }
}

/** How a creature's state reads on its chip and in the legend. */
internal enum class CreatureStateTone { Working, Complete, Waiting, Gated, Failed }

internal fun creatureStateTone(status: TaskRunStatus?): CreatureStateTone = when (status) {
    TaskRunStatus.Planning, TaskRunStatus.Running, TaskRunStatus.Verifying, TaskRunStatus.Retrying -> CreatureStateTone.Working
    TaskRunStatus.Completed -> CreatureStateTone.Complete
    TaskRunStatus.AwaitingApproval, TaskRunStatus.Escalated -> CreatureStateTone.Gated
    TaskRunStatus.Failed, TaskRunStatus.Cancelled -> CreatureStateTone.Failed
    null, TaskRunStatus.Created, TaskRunStatus.Ready, TaskRunStatus.Blocked -> CreatureStateTone.Waiting
}

/** How many of a run's creatures are working right now, and how many it has. */
internal data class HiveActivity(val active: Int, val total: Int)

internal fun hiveActivity(run: WorkflowRun): HiveActivity = HiveActivity(
    active = run.taskRuns.values.count { creatureStateTone(it.status) == CreatureStateTone.Working },
    total = run.taskRuns.size,
)

/**
 * Which run of its hive [run] is, counting from 1 by when each started: the hive is its workflow, as
 * for its combs ([runScars]). Null when [runs] does not hold it (not loaded yet), so nothing is guessed.
 */
internal fun hiveRunNumber(run: WorkflowRun, runs: List<WorkflowRun>): Int? {
    val ofHive = runs.filter { it.workflowDefinitionId == run.workflowDefinitionId }
    if (ofHive.none { it.id == run.id }) return null
    return ofHive.count { it.createdAtEpochMillis < run.createdAtEpochMillis || (it.createdAtEpochMillis == run.createdAtEpochMillis && it.id.value < run.id.value) } + 1
}

/** A plain name for a capability a role needs ("RepositoryWrite" reads "Repository write"). */
internal fun capabilityName(capability: AgentCapability): String =
    capability.name.replace(Regex("(?<=[a-z])(?=[A-Z])"), " ").lowercase().replaceFirstChar { it.uppercase() }

/**
 * The feed bar's objective while the hive's swarm is still running: held, and fed in once that run
 * has finished, as its continuation. Launching over a live run would cancel it, so the bar queues.
 */
internal data class QueuedFeed(val runId: String, val objective: String)

/** The one queued objective, kept on this device so it survives a restart. */
internal object HiveFeedQueue {
    private const val KEY = "hive.feed.queued"
    private const val SEPARATOR = "\n"

    var current: QueuedFeed? by mutableStateOf(read())
        private set

    fun queue(runId: WorkflowRunId, objective: String) {
        val clean = objective.trim()
        if (clean.isEmpty()) return
        current = QueuedFeed(runId.value, clean)
        DurableUiState.store.putString(KEY, runId.value + SEPARATOR + clean)
    }

    fun clear() {
        current = null
        DurableUiState.store.remove(KEY)
    }

    private fun read(): QueuedFeed? = DurableUiState.store.getString(KEY)
        ?.split(SEPARATOR, limit = 2)
        ?.takeIf { it.size == 2 && it[0].isNotBlank() && it[1].isNotBlank() }
        ?.let { QueuedFeed(it[0], it[1]) }
}

/** The queued objective to feed in now: only once [run], the one it waits on, has finished. */
internal fun queuedFeedDue(queued: QueuedFeed?, run: WorkflowRun?): String? =
    queued?.takeIf { run != null && it.runId == run.id.value && run.status.isFinished() }?.objective

/** Whether the feed bar launches now, or queues behind the swarm still running. */
internal fun feedLaunchesNow(status: WorkflowRunStatus): Boolean = status.isFinished()

/**
 * Feeds the queued objective in once its run has finished, wherever the keeper is: composed once,
 * by the shell, beside the hive. It goes through the same launch as the feed bar, as a continuation.
 */
@Composable
internal fun QueuedFeedLauncher(
    live: LiveWorkflowPresentation?,
    onLaunchWorkflow: (String, String, com.hereliesaz.geministrator.domain.RepositoryRef?, List<WorkflowRunId>) -> Unit,
) {
    val due = queuedFeedDue(HiveFeedQueue.current, live?.run)
    androidx.compose.runtime.LaunchedEffect(due, live?.run?.id) {
        val objective = due ?: return@LaunchedEffect
        val workflow = live ?: return@LaunchedEffect
        HiveFeedQueue.clear()
        onLaunchWorkflow(workflow.project.name, objective, workflow.project.repository, listOf(workflow.run.id))
    }
}

/**
 * The hive page's title row: the run's state and project, the hive's name, what it was fed, and on
 * the right which run this is and how many of its creatures are working now.
 */
@Composable
internal fun HivePageTitle(
    eyebrow: String,
    title: String,
    objective: String?,
    runNumber: Int?,
    activity: HiveActivity,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(eyebrow.uppercase(), style = AzphaltType.eyebrow, color = azphaltOnSurface(), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                title,
                style = AzphaltType.hero.copy(fontSize = 40.sp, lineHeight = 44.sp, letterSpacing = (-0.045f).sp * 40),
                color = azphaltOnSurface(),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.semantics { heading() },
            )
            objective?.takeIf(String::isNotBlank)?.let {
                Text(it, style = AzphaltType.body, color = azphaltOnSurface(), maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        Column(horizontalAlignment = Alignment.End, modifier = Modifier.azphaltLiveRegion()) {
            runNumber?.let { Text(stringResource(Res.string.hive_run_number, it), style = AzphaltType.eyebrow, color = azphaltOnSurface()) }
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(Res.string.hive_active_of, activity.active, activity.total), style = AzphaltType.section, color = azphaltOnSurface())
                Text(
                    stringResource(Res.string.hive_creatures_active),
                    style = AzphaltType.endCap,
                    color = azphaltOnSurface(),
                    modifier = Modifier.padding(bottom = 6.dp),
                )
            }
        }
    }
}

/**
 * Across the top of the hive frame, on Ink: Live or Build, one line of what the swarm is doing,
 * then undo and redo (in Build), centre, and zoom.
 */
@Composable
internal fun HiveControlsBar(
    building: Boolean,
    onBuildingChange: (Boolean) -> Unit,
    status: String,
    onStatusClick: (() -> Unit)?,
    camera: TerrariumCamera,
    onSurface: (() -> Unit)?,
    canUndo: Boolean,
    canRedo: Boolean,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AzphaltThemedSurface(Azphalt.Ink) {
        Row(
            modifier = modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(topStart = FrameCorner, topEnd = FrameCorner))
                .background(Azphalt.Ink)
                .heightIn(min = 64.dp)
                .padding(start = 8.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                Modifier
                    .clip(RoundedCornerShape(28.dp))
                    .background(AzphaltLiftedInk)
                    .padding(4.dp)
                    .selectableGroup(),
            ) {
                ModeSegment(stringResource(Res.string.build_live), selected = !building) { onBuildingChange(false) }
                ModeSegment(stringResource(Res.string.build_build), selected = building) { onBuildingChange(true) }
            }
            Row(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = AzphaltMinTouchTarget)
                    .then(if (onStatusClick != null) Modifier.clickable(role = Role.Button, onClick = onStatusClick) else Modifier)
                    .padding(horizontal = 14.dp)
                    .azphaltLiveRegion(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(Modifier.size(7.dp).clip(CircleShape).background(Azphalt.Yellow))
                Text(
                    status,
                    style = AzphaltType.body.copy(fontSize = 13.sp, fontWeight = FontWeight.Medium),
                    color = azphaltMutedOnSurface(),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (building) {
                RoundGlyphButton(HiveGlyph.Undo, stringResource(Res.string.build_undo), enabled = canUndo, onClick = onUndo)
                RoundGlyphButton(HiveGlyph.Redo, stringResource(Res.string.build_redo), enabled = canRedo, onClick = onRedo)
            }
            RoundGlyphButton(HiveGlyph.Target, stringResource(Res.string.hive_centre), onClick = camera::centre)
            RoundGlyphButton(HiveGlyph.ZoomOut, stringResource(Res.string.terrarium_zoom_out), onClick = { camera.stepOut(onSurface) })
            RoundGlyphButton(HiveGlyph.ZoomIn, stringResource(Res.string.terrarium_zoom_in), onClick = camera::stepIn)
        }
    }
}

@Composable
private fun ModeSegment(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .heightIn(min = AzphaltMinTouchTarget)
            .clip(RoundedCornerShape(24.dp))
            .background(if (selected) azphaltStrong() else Color.Transparent)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 18.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label.uppercase(),
            style = AzphaltType.endCap,
            color = if (selected) azphaltStrong().onOpposite() else azphaltMutedOnSurface(),
        )
    }
}

/**
 * The hive frame's tissue: Ink on both sides, open to the controls bar above and the feed bar below,
 * with the legend along its foot.
 */
@Composable
internal fun HiveTissueFrame(height: Dp, building: Boolean, modifier: Modifier = Modifier, field: @Composable () -> Unit) {
    Box(
        modifier
            .fillMaxWidth()
            .height(height)
            .background(Azphalt.Ink)
            .padding(horizontal = FrameSide),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .then(if (building) Modifier.border(3.dp, Azphalt.Yellow) else Modifier),
        ) {
            field()
            HiveLegend(Modifier.align(Alignment.BottomStart).padding(horizontal = 14.dp, vertical = 10.dp))
        }
    }
}

/** What the field's colours mean, and that moving bodies never rewires anything. */
@Composable
internal fun HiveLegend(modifier: Modifier = Modifier) {
    AzphaltThemedSurface(Azphalt.Ink) {
        val muted = azphaltMutedOnSurface()
        Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            LegendKey(Azphalt.Yellow, stringResource(Res.string.hive_legend_working), muted)
            LegendKey(ClaspGreen, stringResource(Res.string.hive_legend_complete), muted)
            LegendKey(muted.copy(alpha = 0.55f), stringResource(Res.string.hive_legend_waiting), muted)
            Spacer(Modifier.weight(1f))
            Text(stringResource(Res.string.hive_legend_note), style = AzphaltType.endCap.copy(fontWeight = FontWeight.Bold), color = muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun LegendKey(dot: Color, label: String, text: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(dot))
        Text(label, style = AzphaltType.endCap, color = text)
    }
}

/**
 * Under the hive, always: an objective for the swarm. A finished swarm launches it at once as its
 * continuation; a running one holds it ([HiveFeedQueue]) and feeds it in when it finishes.
 */
@Composable
internal fun HiveFeedBar(
    run: WorkflowRun,
    onLaunch: (String) -> Unit,
    modifier: Modifier = Modifier,
    message: String? = null,
) {
    var objective by remember(run.id) { mutableStateOf("") }
    val fieldName = stringResource(Res.string.hive_feed_title)
    val now = feedLaunchesNow(run.status)
    val queued = HiveFeedQueue.current?.takeIf { it.runId == run.id.value }
    val submit = {
        val clean = objective.trim()
        if (clean.isNotEmpty()) {
            if (now) onLaunch(clean) else HiveFeedQueue.queue(run.id, clean)
            objective = ""
        }
    }
    AzphaltThemedSurface(Azphalt.Ink) {
        Column(
            modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(bottomStart = FrameCorner, bottomEnd = FrameCorner))
                .background(Azphalt.Ink)
                .padding(start = 18.dp, end = 10.dp, top = 10.dp, bottom = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(stringResource(Res.string.hive_feed_eyebrow), style = AzphaltType.eyebrow, color = azphaltStrong())
                    Text(stringResource(Res.string.hive_feed_title), style = AzphaltType.body.copy(fontSize = 14.sp), color = azphaltOnSurface())
                }
                OutlinedTextField(
                    value = objective,
                    onValueChange = { objective = it },
                    placeholder = { Text(stringResource(Res.string.hive_feed_placeholder), style = AzphaltType.body.copy(fontSize = 14.sp)) },
                    singleLine = true,
                    shape = RoundedCornerShape(28.dp),
                    colors = azphaltTextFieldColors(),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { submit() }),
                    // The field is named for screen readers by what it is for, not only its placeholder.
                    modifier = Modifier.weight(1f).semantics { contentDescription = fieldName },
                )
                FeedButton(
                    label = stringResource(if (now) Res.string.hive_feed_launch else Res.string.hive_feed_queue),
                    onClick = submit,
                )
            }
            queued?.let { waiting ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.azphaltLiveRegion(),
                ) {
                    Text(
                        stringResource(Res.string.hive_feed_queued, waiting.objective),
                        style = AzphaltType.body.copy(fontSize = 13.sp),
                        color = azphaltMutedOnSurface(),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    AzphaltPill(label = stringResource(Res.string.hive_feed_unqueue), seed = "hive-feed-unqueue", onClick = HiveFeedQueue::clear)
                }
            }
            message?.let {
                Text(it, style = AzphaltType.body.copy(fontSize = 13.sp), color = azphaltOnSurface(), modifier = Modifier.azphaltLiveRegion())
            }
        }
    }
}

@Composable
private fun FeedButton(label: String, onClick: () -> Unit) {
    val fill = azphaltStrong()
    Row(
        modifier = Modifier
            .heightIn(min = 52.dp)
            .clip(RoundedCornerShape(26.dp))
            .background(fill)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Glyph(HiveGlyph.Play, fill.onOpposite(), Modifier.size(16.dp), filled = true)
        Text(label.uppercase(), style = AzphaltType.endCap, color = fill.onOpposite())
    }
}

/** The docked panel's head: which creature of how many, its job's name, and a way to close. */
@Composable
internal fun DockedCreatureHead(position: Int?, of: Int, title: String, onClose: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Column(Modifier.weight(1f).padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            position?.let {
                Text(stringResource(Res.string.creature_panel_place, it, of), style = AzphaltType.eyebrow, color = azphaltStrong())
            }
            Text(title, style = AzphaltType.section.copy(fontSize = 24.sp, lineHeight = 26.sp), color = azphaltOnSurface(), modifier = Modifier.semantics { heading() })
        }
        RoundGlyphButton(HiveGlyph.Close, stringResource(Res.string.creature_panel_close), onClick = onClose)
    }
}

/** The creature itself on a lifted card: its state chip, its role and what thinks for it. */
@Composable
internal fun DockedCreatureCard(
    roleLabel: String,
    hueSeed: String,
    status: TaskRunStatus,
    provider: String?,
    rigOverride: String?,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(138.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(AzphaltLiftedInk),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The creature is decoration here: its role and state are read from the text beside it.
        NodeMascotSurface(
            roleLabel = roleLabel,
            hueSeed = hueSeed,
            state = status.toTerrariumState(),
            motionPhase = { 0f },
            rigOverride = rigOverride,
            modifier = Modifier.size(130.dp).clearAndSetSemantics {},
        )
        AzphaltThemedSurface(AzphaltLiftedInk) {
            Column(Modifier.weight(1f).padding(end = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                StateChip(status)
                Text(roleLabel, style = AzphaltType.body, color = azphaltOnSurface(), maxLines = 2, overflow = TextOverflow.Ellipsis)
                provider?.let { Text(it, style = AzphaltType.endCap.copy(letterSpacing = 0.sp), color = azphaltMutedOnSurface()) }
            }
        }
    }
}

@Composable
private fun StateChip(status: TaskRunStatus) {
    val fill = when (creatureStateTone(status)) {
        CreatureStateTone.Working -> Azphalt.Yellow
        CreatureStateTone.Complete -> ClaspGreen
        CreatureStateTone.Waiting -> Azphalt.Ink
        CreatureStateTone.Gated, CreatureStateTone.Failed -> AzphaltDanger
    }
    Text(
        status.label().uppercase(),
        style = AzphaltType.endCap.copy(fontSize = 10.sp),
        color = fill.onOpposite(),
        modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(fill).padding(horizontal = 8.dp, vertical = 4.dp),
    )
}

/** Needs, Work and Mind as tabs underlined in the strong colour, across the panel's width. */
@Composable
internal fun CreatureUnderlineTabs(tab: CreatureTab, onTabChange: (CreatureTab) -> Unit, pendingProposals: Int) {
    val line = azphaltMutedOnSurface().copy(alpha = 0.4f)
    val strong = azphaltStrong()
    Row(
        Modifier
            .fillMaxWidth()
            .selectableGroup()
            .drawBehind { drawLine(line, Offset(0f, size.height - 0.5.dp.toPx()), Offset(size.width, size.height - 0.5.dp.toPx()), 1.dp.toPx()) },
    ) {
        CreatureTab.entries.forEach { entry ->
            val selected = entry == tab
            val label = entry.label().uppercase() + if (entry == CreatureTab.Mind && pendingProposals > 0) " · $pendingProposals" else ""
            Box(
                Modifier
                    .weight(1f)
                    .heightIn(min = AzphaltMinTouchTarget)
                    .selectable(selected = selected, role = Role.Tab, onClick = { onTabChange(entry) })
                    .drawBehind {
                        if (selected) {
                            val w = 3.dp.toPx()
                            drawLine(strong, Offset(0f, size.height - w / 2), Offset(size.width, size.height - w / 2), w)
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text(label, style = AzphaltType.endCap, color = if (selected) azphaltOnSurface() else azphaltMutedOnSurface())
            }
        }
    }
}

/** Its mind as a list of what it really is: role, provider, what it needs to work with, budget. */
@Composable
internal fun MindDefinitionList(identity: String, provider: String?, role: RoleDefinition?) {
    val rows = buildList {
        add(stringResource(Res.string.mind_role) to identity)
        provider?.let { add(stringResource(Res.string.inspector_provider) to it) }
        role?.capabilitiesRequired?.takeIf { it.isNotEmpty() }?.let { needs ->
            add(stringResource(Res.string.mind_tools) to needs.sortedBy { it.ordinal }.joinToString(", ", transform = ::capabilityName))
        }
        role?.let { add(stringResource(Res.string.mind_budget) to (it.tokenBudget?.toString() ?: stringResource(Res.string.mind_no_budget))) }
    }
    val rule = azphaltMutedOnSurface().copy(alpha = 0.3f)
    Column(Modifier.fillMaxWidth()) {
        rows.forEach { (term, value) ->
            Column(
                Modifier
                    .fillMaxWidth()
                    .drawBehind { drawLine(rule, Offset(0f, size.height), Offset(size.width, size.height), 1.dp.toPx()) }
                    .padding(vertical = 10.dp)
                    .semantics(mergeDescendants = true) {},
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(term, style = AzphaltType.eyebrow, color = azphaltStrong())
                Text(value, style = AzphaltType.body, color = azphaltOnSurface())
            }
        }
    }
}

/** A round, outlined control on Ink: a 48dp target around a 34dp ring. */
@Composable
internal fun RoundGlyphButton(glyph: HiveGlyph, label: String, onClick: () -> Unit, enabled: Boolean = true) {
    val ink = if (enabled) azphaltOnSurface() else azphaltMutedOnSurface().copy(alpha = 0.5f)
    Box(
        Modifier
            .size(AzphaltMinTouchTarget)
            .clip(CircleShape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.size(34.dp).border(1.dp, azphaltMutedOnSurface().copy(alpha = 0.5f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Glyph(glyph, ink, Modifier.size(17.dp))
        }
    }
}

/** Line glyphs on a 24-unit grid, drawn in the surface's own colour. */
internal enum class HiveGlyph(val path: String) {
    Undo("M9 7L4 12l5 5M5 12h9a5 5 0 015 5"),
    Redo("M15 7l5 5-5 5M19 12h-9a5 5 0 00-5 5"),
    ZoomIn("M16 10a6 6 0 1 1-12 0a6 6 0 1 1 12 0M14.5 14.5L21 21M10 7v6M7 10h6"),
    ZoomOut("M16 10a6 6 0 1 1-12 0a6 6 0 1 1 12 0M14.5 14.5L21 21M7 10h6"),
    Target("M20 12a8 8 0 1 1-16 0a8 8 0 1 1 16 0M15 12a3 3 0 1 1-6 0a3 3 0 1 1 6 0"),
    Close("M6 6l12 12M18 6L6 18"),
    Play("M8 5l11 7-11 7V5z"),
}

@Composable
private fun Glyph(glyph: HiveGlyph, color: Color, modifier: Modifier, filled: Boolean = false) {
    val path = remember(glyph) { PathParser().parsePathString(glyph.path).toPath() }
    Canvas(modifier.clearAndSetSemantics {}) {
        val unit = size.minDimension / 24f
        scale(unit, unit, pivot = Offset.Zero) {
            if (filled) drawPath(path, color)
            drawPath(path, color, style = Stroke(width = 1.8f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}

/**
 * The hive frame's parts that come from outside the field: how tall the tissue stands, the status
 * line (and what tapping it opens), and the feed bar under it.
 */
internal class HiveFrame(
    val tissueHeight: Dp,
    val status: String,
    val onStatusClick: (() -> Unit)?,
    val feedBar: @Composable () -> Unit,
)
