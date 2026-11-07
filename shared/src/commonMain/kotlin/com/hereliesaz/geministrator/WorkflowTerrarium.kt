package com.hereliesaz.geministrator

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import com.hereliesaz.conveyance.h2g2.H2g2WorkflowState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.hereliesaz.conveyance.h2g2.H2g2TerrariumRelationshipKind
import com.hereliesaz.geministrator.domain.RoleDefinition
import com.hereliesaz.geministrator.domain.TaskDefinitionId
import com.hereliesaz.geministrator.domain.TaskRunStatus
import com.hereliesaz.geministrator.domain.WorkflowDefinition
import com.hereliesaz.geministrator.domain.WorkflowDefinitionId
import com.hereliesaz.geministrator.domain.WorkflowRun
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.hereliesaz.geministrator.resources.Res
import com.hereliesaz.geministrator.resources.hive_new_empty
import com.hereliesaz.geministrator.resources.terrarium_could_not_stage_workflow_edit
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource

/**
 * Production workflow view: the DAG is a living node-creature map rather than a diagram.
 *
 * Habitat movement is presentation-only and persisted independently; bodies never edit the
 * workflow. Wiring changes only through [TerrariumConnections], recorded on the [TerrariumTide] and
 * staged as a draft; the currently executing run remains immutable.
 */
@Composable
internal fun GeministratorWorkflowTerrarium(
    definition: WorkflowDefinition,
    liveRun: WorkflowRun,
    roles: Collection<RoleDefinition>,
    selectedTaskId: String?,
    onTaskSelected: (String) -> Unit,
    onStageDefinition: (suspend (WorkflowDefinition) -> WorkflowDefinition)? = null,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    onSurface: (() -> Unit)? = null,
    /** Opens in Build instead of Live; for a hive that has never run, and for screenshots. */
    startInBuild: Boolean = false,
    /**
     * A hive's own head (mockup H6): set, it leads the row and Live and Build sit beside it as one
     * segmented control; null keeps the two pills on their own.
     */
    heading: (@Composable () -> Unit)? = null,
    /** Shown under the field while building, given the staged hive and the way to edit it (H6's bench). */
    beneathField: (@Composable (WorkflowDefinition, (TerrariumDependencyEditResult) -> Unit) -> Unit)? = null,
    /** On wide screens, the hive in one frame: controls bar, tissue, legend and feed bar (HiveWorkspace.kt). */
    frame: HiveFrame? = null,
    /** Opens a new selection on this view instead of the first (screenshots and tests). */
    startFocusLevel: FocusLevel? = null,
    /** A hive at rest can delete itself from its page (HiveDeletion.kt); null where that is not offered. */
    hiveDeletion: HiveDeletionActions? = null,
) {
    val scope = rememberCoroutineScope()
    val layoutStore = remember { SettingsTerrariumLayoutStore.createDefault() }
    val fallbackDraftPersistence = remember { com.hereliesaz.geministrator.persistence.WorkflowPersistenceFactory.durable }
    val layoutDefinitionId = liveRun.workflowDefinitionId
    var positions by remember(layoutDefinitionId) {
        mutableStateOf(layoutStore.load(layoutDefinitionId))
    }
    // The tide holds every wiring edit; its present is the staged definition shown in the field.
    var tide by remember(definition) { mutableStateOf(TerrariumTide(present = definition)) }
    val stagedDefinition = tide.present
    var authoringMessage by remember(definition.id) { mutableStateOf<String?>(null) }

    val stageDefinition: suspend (WorkflowDefinition) -> WorkflowDefinition = onStageDefinition ?: { candidate ->
        val draftId = WorkflowDefinitionId("${definition.id.value}--terrarium-draft")
        val persisted = candidate.copy(
            id = draftId,
            name = "${definition.name} — terrarium draft",
        )
        fallbackDraftPersistence.definitions.put(persisted)
        persisted
    }

    // Rehearse: frames of a dry run replace the live run while they play; nothing is persisted.
    var rehearsal by remember(definition.id) { mutableStateOf<List<WorkflowRun>?>(null) }
    var rehearsalFrame by remember(definition.id) { mutableStateOf(0) }
    LaunchedEffect(rehearsal) {
        val frames = rehearsal ?: return@LaunchedEffect
        for (index in frames.indices) {
            rehearsalFrame = index
            delay(900)
        }
        delay(1_500)
        rehearsal = null
    }
    val run = rehearsal?.getOrNull(rehearsalFrame) ?: liveRun

    val projection = remember(stagedDefinition, run, roles, positions) {
        projectWorkflowTerrarium(
            definition = stagedDefinition,
            run = run,
            roles = roles,
            persistedPositions = positions,
        )
    }
    val tissue = remember(projection, stagedDefinition, run) {
        projectTissue(stagedDefinition, run, projection.subjects.associate { it.node.id to it.position }, roles)
    }
    // Who is born and who is still an egg, from the run's task statuses; Build shows every creature.
    val births = remember(stagedDefinition, run) { projectBirths(stagedDefinition, run) }
    val animatedRelationships = remember(projection.relationships, run) {
        projection.relationships.map { relationship ->
            if (relationship.kind != H2g2TerrariumRelationshipKind.Dependency) {
                relationship
            } else {
                val upstream = run.taskRuns[TaskDefinitionId(relationship.from)]
                val downstream = run.taskRuns[TaskDefinitionId(relationship.to)]
                val isTransfer = upstream?.artifacts?.isNotEmpty() == true &&
                    downstream?.status in setOf(
                        TaskRunStatus.Ready,
                        TaskRunStatus.Planning,
                        TaskRunStatus.Running,
                        TaskRunStatus.Verifying,
                    )
                if (isTransfer) {
                    relationship.copy(
                        kind = H2g2TerrariumRelationshipKind.Transfer,
                        active = true,
                    )
                } else {
                    relationship
                }
            }
        }
    }

    fun applyEdit(edit: TerrariumDependencyEditResult) {
        when (edit) {
            is TerrariumDependencyEditResult.Applied -> scope.launch {
                runCatching { stageDefinition(edit.definition) }
                    .onSuccess { persisted ->
                        tide = tide.record(persisted)
                        authoringMessage = null
                    }
                    .onFailure { failure ->
                        authoringMessage = failure.message ?: getString(Res.string.terrarium_could_not_stage_workflow_edit)
                    }
            }
            TerrariumDependencyEditResult.NoChange -> Unit
            is TerrariumDependencyEditResult.Rejected -> authoringMessage = edit.reason
        }
    }

    // Live watches the run; Build holds the field still and opens the wiring to edits.
    var building by remember(definition.id) { mutableStateOf(startInBuild) }
    // While grouping, taps gather creatures instead of selecting one.
    var grouping by remember(definition.id) { mutableStateOf<Set<String>?>(null) }

    // Framed, the controls bar drives the field's zoom and focus; unframed, the field keeps its own.
    val camera = remember(definition.id) { TerrariumCamera() }
    camera.driven = frame != null

    // The semantic focus stack (TerrariumFocus.kt): what the camera shows follows the selection.
    // A cluster being grouped counts as one focus; otherwise the selected creature.
    val focusSelection: Set<String> = grouping?.takeIf { it.isNotEmpty() } ?: setOfNotNull(selectedTaskId)
    val focusPositions = remember(projection) { projection.subjects.associate { it.node.id to it.position } }
    val focusGraph = remember(stagedDefinition, focusPositions) {
        val ids = stagedDefinition.tasks.map { it.id.value }.filterTo(linkedSetOf()) { it in focusPositions }
        FocusGraph(ids, stagedDefinition.tasks.flatMap { t -> t.dependsOn.map { it.value to t.id.value } })
    }
    val orchestratorIds = remember(stagedDefinition, roles) {
        stagedDefinition.tasks.filter { t -> roles.firstOrNull { it.id == t.roleId }?.name == "Orchestrator" }.map { it.id.value }
    }
    val activeIds = remember(projection, building) {
        if (building) emptySet() else projection.subjects.filter { it.node.state == H2g2WorkflowState.Active }.mapTo(mutableSetOf()) { it.node.id }
    }
    val blockedIds = remember(projection, building) {
        if (building) emptySet() else projection.subjects.filter {
            it.node.state == H2g2WorkflowState.Gate || it.node.state == H2g2WorkflowState.Blocked || it.node.state == H2g2WorkflowState.Failed
        }.mapTo(mutableSetOf()) { it.node.id }
    }
    // Removing the selected creature (or grouped ones) lets go of them: the view falls back to the whole hive.
    var knownTaskIds by remember(definition.id) { mutableStateOf(stagedDefinition.tasks.map { it.id.value }.toSet()) }
    LaunchedEffect(stagedDefinition.tasks) {
        val remaining = stagedDefinition.tasks.map { it.id.value }.toSet()
        if (remaining != knownTaskIds) {
            selectedTaskId?.takeIf { it in knownTaskIds && it !in remaining }?.let(onTaskSelected)
            grouping?.let { gathered ->
                val kept = selectionAfterRemoval(gathered, remaining)
                if (kept != gathered) grouping = kept
            }
            knownTaskIds = remaining
        }
    }
    val focusSteps = remember(focusGraph, focusSelection, orchestratorIds, activeIds, blockedIds) {
        focusLevels(focusGraph, focusSelection, orchestratorIds, activeIds, blockedIds)
    }
    var stackSelection by remember(definition.id) { mutableStateOf<Set<String>?>(null) }
    LaunchedEffect(focusSelection, focusSteps) {
        val sameSelection = stackSelection == focusSelection
        camera.stack = if (sameSelection) {
            camera.stack.refreshed(focusSteps)
        } else {
            val fresh = camera.stack.selected(focusSteps)
            val at = startFocusLevel?.let { level -> focusSteps.indexOfFirst { it.level == level } } ?: -1
            if (at >= 0 && focusSelection.isNotEmpty()) fresh.copy(index = at) else fresh
        }
        if (!sameSelection) camera.free = false
        stackSelection = focusSelection
    }
    val focusStep = camera.stack.current
    var appliedView by remember(definition.id) { mutableStateOf<List<Any?>?>(null) }
    LaunchedEffect(focusStep, focusSelection, camera.applyTick, camera.stack.index, focusPositions) {
        // Once a finger has moved the view, only a deliberate change (a step, a new selection, recentre) puts it back.
        val deliberate = listOf(focusStep.level, focusSelection, camera.applyTick, camera.stack.index)
        if (camera.free && appliedView == deliberate) return@LaunchedEffect
        appliedView = deliberate
        val all = focusPositions.values
        val overview = overviewFit(all)
        val fit = if (focusStep.level == FocusLevel.Overview) {
            overview
        } else {
            fitCamera(focusStep.nodes.mapNotNull(focusPositions::get), floorZoom = overview.zoom)
        }
        camera.zoom = fit.zoom
        camera.focus = fit.focus
        camera.free = false
    }
    // Back steps the view out a level before it does anything else, while the selection has somewhere to go.
    PlatformBackHandler(enabled = focusSelection.isNotEmpty() && camera.stack.canStepOut) { camera.stepOut(null) }
    val undo = {
        val back = tide.undo()
        scope.launch {
            runCatching { stageDefinition(back.present) }
                .onSuccess { persisted -> tide = back.copy(present = persisted) }
                .onFailure { failure -> authoringMessage = failure.message }
        }
        Unit
    }
    val redo = {
        val forward = tide.redo()
        scope.launch {
            runCatching { stageDefinition(forward.present) }
                .onSuccess { persisted -> tide = forward.copy(present = persisted) }
                .onFailure { failure -> authoringMessage = failure.message }
        }
        Unit
    }

    Column(modifier = modifier.fillMaxWidth()) {
        if (frame == null) {
            if (heading == null) {
                TerrariumModeToggle(building = building, onBuildingChange = { building = it })
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.weight(1f, fill = false).padding(end = 8.dp)) { heading() }
                    TerrariumModeToggle(building = building, onBuildingChange = { building = it }, segmented = true)
                }
            }
        } else {
            HiveControlsBar(
                building = building,
                onBuildingChange = { building = it },
                status = frame.status,
                onStatusClick = frame.onStatusClick,
                camera = camera,
                onSurface = onSurface,
                canUndo = tide.canUndo,
                canRedo = tide.canRedo,
                onUndo = undo,
                onRedo = redo,
            )
        }
        val field: @Composable () -> Unit = {
          // Programmatic steps, local or remote, wear the neuron: the swarm reads as a flowchart.
          androidx.compose.runtime.CompositionLocalProvider(
              LocalProgrammaticNodes provides projection.programmaticNodeIds,
              LocalProviderBadges provides projection.providerBadges,
          ) {
            PlatformNodeTerrarium(
                subjects = projection.subjects,
                relationships = animatedRelationships,
                adornments = projection.adornments,
                serviceVisits = projection.serviceVisits,
                editable = true,
                compact = compact,
                tissue = (if (building && rehearsal == null) tissue.copy(egg = null) else tissue)
                    .copy(
                        gathered = grouping.orEmpty(),
                        births = if (building && rehearsal == null) emptyMap() else births,
                    ),
                selectedId = selectedTaskId,
                onNodeSelected = { node ->
                    grouping?.let { gathered ->
                        if (stagedDefinition.tasks.any { it.id.value == node.id }) {
                            grouping = if (node.id in gathered) gathered - node.id else gathered + node.id
                        }
                        return@PlatformNodeTerrarium
                    }
                    if (node.id != TERRARIUM_ORCHESTRATOR_ID && stagedDefinition.tasks.any { it.id.value == node.id }) {
                        onTaskSelected(node.id)
                    }
                },
                onNodeMoved = { subjectId, position ->
                    layoutStore.put(layoutDefinitionId, subjectId, position)
                    positions = positions + (subjectId to position)
                },
                // Bodies only arrange (docs/Swarm-OS.md): wiring changes through the connections below.
                onNodeDroppedOn = null,
                onSurface = onSurface,
                // In Build, a strand drawn from a feeder's tail to another creature feeds it; a swipe
                // across a strand cuts it.
                onTetherDrawn = if (building) {
                    { from, to -> applyEdit(addTerrariumDependency(stagedDefinition, TaskDefinitionId(to), TaskDefinitionId(from))) }
                } else {
                    null
                },
                onTetherSliced = if (building) {
                    { from, to -> applyEdit(removeTerrariumDependency(stagedDefinition, TaskDefinitionId(to), TaskDefinitionId(from))) }
                } else {
                    null
                },
                // Tapping the bare ground lets go of the selection, which returns the view to the whole hive.
                onGroundTapped = {
                    if (grouping == null) selectedTaskId?.let(onTaskSelected)
                },
                modifier = Modifier.fillMaxSize(),
                camera = camera,
            )
          }
            // An empty hive in Build says how it fills (mockup H6).
            if (building && stagedDefinition.tasks.isEmpty()) {
                Box(Modifier.fillMaxSize()) {
                    Text(
                        text = stringResource(Res.string.hive_new_empty),
                        style = AzphaltType.body.copy(fontWeight = FontWeight.Normal, textAlign = TextAlign.Center),
                        // The field is dark tissue whatever the ground: its text is Ink's opposite, softened.
                        color = Azphalt.Ink.onOpposite().copy(alpha = 0.78f),
                        modifier = Modifier.align(Alignment.BottomCenter).padding(start = 36.dp, end = 36.dp, bottom = 28.dp),
                    )
                }
            }
        }

        if (frame == null) {
            Box(Modifier.fillMaxWidth().height(if (compact) 470.dp else 620.dp)) { field() }
        } else {
            HiveTissueFrame(height = frame.tissueHeight, building = building) { field() }
            frame.feedBar()
        }
        if (building) beneathField?.let { bench ->
            Box(Modifier.padding(top = 12.dp)) { bench(stagedDefinition, ::applyEdit) }
        }

        if (building) {
            TerrariumConnections(
                definition = stagedDefinition,
                selectedTaskId = selectedTaskId,
                canUndo = tide.canUndo,
                canRedo = tide.canRedo,
                onRehearse = { rehearsal = rehearsalFrames(stagedDefinition, liveRun) },
                grouping = grouping,
                onGroupingChange = { grouping = it },
                onFold = { members ->
                    when (val fold = foldTerrariumCircuit(stagedDefinition, members.mapTo(mutableSetOf(), ::TaskDefinitionId))) {
                        is TerrariumFoldResult.Rejected -> authoringMessage = fold.reason
                        is TerrariumFoldResult.Folded -> scope.launch {
                            // The circuit is its own workflow: saved first, so the parent's nested creature can find it.
                            runCatching { fallbackDraftPersistence.definitions.put(fold.circuit) }
                                .onSuccess {
                                    grouping = null
                                    applyEdit(TerrariumDependencyEditResult.Applied(fold.parent))
                                }
                                .onFailure { failure -> authoringMessage = failure.message }
                        }
                    }
                },
                onEdit = ::applyEdit,
                roles = roles,
                removeBlocked = terrariumRemoveBlocked(liveRun),
                onUndo = undo,
                onRedo = redo,
                modifier = Modifier.padding(top = 8.dp),
            )
            hiveDeletion?.let { DeleteHiveControl(name = definition.name, creatures = stagedDefinition.tasks.size, actions = it, modifier = Modifier.padding(top = 8.dp)) }
        }

        authoringMessage?.let { message ->
            Text(
                text = message,
                style = AzphaltType.body,
                color = azphaltOnSurface(),
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}
