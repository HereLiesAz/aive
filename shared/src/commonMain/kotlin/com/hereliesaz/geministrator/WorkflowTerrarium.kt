package com.hereliesaz.geministrator

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import com.hereliesaz.conveyance.h2g2.H2g2WorkflowState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.hereliesaz.geministrator.tour.TourTargetId
import com.hereliesaz.geministrator.tour.tourTarget
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
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import com.hereliesaz.geministrator.resources.hive_menu_options
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.withTimeoutOrNull
import com.hereliesaz.geministrator.resources.hive_new_empty
import com.hereliesaz.geministrator.resources.terrarium_could_not_stage_workflow_edit
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource

/**
 * How the shell lets a selected creature go when bare ground is tapped. It differs from
 * `onTaskSelected`, which treats a tap on the creature whose panel was closed as "open it again".
 */
internal val LocalReleaseCreature = staticCompositionLocalOf<((String) -> Unit)?> { null }

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
    /**
     * Only watches: no Live/Build toggle, and the keeper's Swarm view does not apply. The planning field
     * (PlanningScene.kt) is drawn this way, so a keeper whose hives show every creature still sees eggs.
     */
    readOnly: Boolean = false,
) {
    // Bare ground lets the selection go; where the shell offers a plain release, use it, else a re-tap.
    val releaseCreature = LocalReleaseCreature.current ?: onTaskSelected
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
    // Who is born and who is still an egg, from the run's task statuses: the hive grows as the work does.
    // Edit shows every creature. Outside Edit one round button on the controls bar shows every creature at once
    // (the whole swarm) instead of letting the hive grow; it is a state of the one hive, not a second screen.
    val births = remember(stagedDefinition, run) { projectBirths(stagedDefinition, run) }
    // Who shares a creature: ordered tasks of one role. Build and the Swarm view keep one creature per task.
    val roaming = remember(stagedDefinition, run) { projectRoaming(stagedDefinition, run) }
    val swarmView = rememberDurableBooleanState("hive.view.swarm")
    var hiveMenu by remember { mutableStateOf(false) }
    var hiveDeleting by remember { mutableStateOf(false) }
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
    // A link elsewhere (a creature's job) asks for the editor: open it, the creature stays selected, and (once Edit
    // is on) it is turned over to its card, since the job card belongs to the flipped creature.
    var turnSelectedOver by remember(definition.id) { mutableStateOf(false) }
    LaunchedEffect(SwarmEditRequests.pending) {
        if (SwarmEditRequests.take() && !readOnly) {
            building = true
            turnSelectedOver = true
        }
    }
    // While grouping, taps gather creatures instead of selecting one.
    var grouping by remember(definition.id) { mutableStateOf<Set<String>?>(null) }

    // Edit's card flip (CardFlip.kt): a tapped creature turns over to its step's card, and its detail shows in
    // the shell's sheet (or docked panel). Without a shell (a preview, the editor's bowl) the field keeps its own
    // flip and shows the detail under itself.
    val shellFlip = LocalCardFlip.current
    val ownFlip = remember { CardFlipHost() }
    val flipHost = shellFlip ?: ownFlip
    val flipping = building && !readOnly && rehearsal == null
    SideEffect {
        flipHost.editing = flipping
        flipHost.detail = { taskId, detailModifier ->
            FlowchartCardDetailPanel(
                taskId = taskId,
                definition = stagedDefinition,
                run = run,
                roles = roles,
                onShowStep = { other -> flipHost.send(CardFlipEvent.OtherNodeTapped(other)) },
                modifier = detailModifier,
            )
        }
    }
    DisposableEffect(flipHost) {
        onDispose {
            flipHost.editing = false
            flipHost.detail = null
            flipHost.state = CardFlip()
        }
    }
    val flippedId = flipHost.state.flippedId
    LaunchedEffect(turnSelectedOver, flipping, selectedTaskId) {
        if (turnSelectedOver && flipping) {
            selectedTaskId?.let { id -> if (flipHost.state.flippedId != id) flipHost.send(CardFlipEvent.Tap(id)) }
            turnSelectedOver = false
        }
    }
    // Leaving Edit, or the selection moving off the flipped creature (bare ground, a link to another), turns it back.
    LaunchedEffect(selectedTaskId, flipping) {
        val flipped = flipHost.state.flippedId
        if (flipped != null && (!flipping || selectedTaskId != flipped)) flipHost.send(CardFlipEvent.Back)
    }
    // With no shell to select for it, a flip selects its creature itself.
    if (shellFlip == null) LaunchedEffect(flippedId) {
        if (flippedId != null && selectedTaskId != flippedId) onTaskSelected(flippedId)
    }
    val flowCards = remember(stagedDefinition, run, roles) {
        com.hereliesaz.geministrator.workflow.WorkflowFlowchart.of(
            stagedDefinition,
            run,
            roleName = { id -> roles.firstOrNull { it.id == id }?.name ?: id.value },
        ).nodes.associateBy { it.id.value }
    }

    // Framed, the controls bar drives the field's zoom and focus; unframed, the field keeps its own.
    val camera = remember(definition.id) { TerrariumCamera() }
    camera.driven = frame != null || readOnly || (heading == null && LocalHiveActions.current.isNotEmpty())
    camera.controlsHidden = readOnly

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
            // Release, not select: a shell's select treats a re-tap of a set-aside creature as "reopen".
            selectedTaskId?.takeIf { it in knownTaskIds && it !in remaining }?.let(releaseCreature)
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

    val runSegment = runSegmentLabel(rehearsal != null, liveRun.status.takeIf { isRealRun(liveRun.id.value) })
    androidx.compose.runtime.CompositionLocalProvider(LocalRunSegment provides runSegment) {
    Column(modifier = modifier.fillMaxWidth()) {
        if (readOnly) {
            // It only watches: no controls above the field.
        } else if (frame == null) {
            if (heading == null && LocalHiveActions.current.isNotEmpty()) {
                // A live hive on a phone: the same controls bar as on a wide screen, so it is one organism.
                HiveControlsBar(
                    building = building,
                    onBuildingChange = { building = it },
                    status = null,
                    onStatusClick = null,
                    camera = camera,
                    onSurface = onSurface,
                    canUndo = tide.canUndo,
                    canRedo = tide.canRedo,
                    onUndo = undo,
                    onRedo = redo,
                    swarm = swarmView.value,
                    onSwarmChange = if (building) null else { all -> swarmView.value = all },
                )
            } else if (heading == null) {
                TerrariumModeToggle(
                    building = building,
                    onBuildingChange = { building = it },
                )
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.weight(1f, fill = false).padding(end = 8.dp)) { heading() }
                    TerrariumModeToggle(
                        building = building,
                        onBuildingChange = { building = it },
                        segmented = true,
                    )
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
                swarm = swarmView.value,
                onSwarmChange = if (building) null else { all -> swarmView.value = all },
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
                        births = if ((building && rehearsal == null) || (swarmView.value && !readOnly)) emptyMap() else births,
                        roaming = if ((building && rehearsal == null) || (swarmView.value && !readOnly)) RoamPlan() else roaming,
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
                        // In Edit a tap turns the creature to its card (the shell selects it and opens the sheet).
                        if (flipping) flipHost.send(CardFlipEvent.Tap(node.id)) else onTaskSelected(node.id)
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
                    if (grouping == null) selectedTaskId?.let(releaseCreature)
                },
                modifier = Modifier.fillMaxSize(),
                camera = camera,
                flip = if (flipping) NodeFlip(flippedId, flowCards, flipHost::send) else null,
            )
          }
            // In Build the tapped creature opens its job on the tissue itself: a card grown from the foot of
            // the field with the objective, approval, tries, time and the rest, so the settings are inside
            // the hive and not a form under it. Everything the form had is here; undo and redo apply to it.
            // It belongs to the creature that has turned over to its step's card (Edit's card flip), and rises
            // under that card, so the creature opens in place as card and settings together.
            if (building && rehearsal == null && grouping == null) {
                stagedDefinition.tasks.firstOrNull { it.id.value == selectedTaskId && it.id.value == flippedId }?.let { openTask ->
                    // Keyed by the creature, so the next one opens at its own top and not at the last one's scroll.
                    androidx.compose.runtime.key(openTask.id) { Box(Modifier.fillMaxSize()) {
                        AzphaltThemedSurface(Azphalt.Ink) {
                            Column(
                                Modifier
                                    .align(Alignment.BottomCenter)
                                    // In the wide frame the legend is drawn along the field's foot: the card stands above it.
                                    .padding(bottom = if (frame != null) 56.dp else 0.dp)
                                    .fillMaxWidth()
                                    .fillMaxHeight(0.4f)
                                    .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                                    .background(Azphalt.Ink.copy(alpha = 0.96f))
                                    // Taps and drags on the card are the card's, never the tissue's beneath it.
                                    .pointerInput(Unit) { detectTapGestures { } }
                                    .verticalScroll(rememberScrollState())
                                    .padding(horizontal = 16.dp, vertical = 14.dp),
                            ) {
                                HiveJobEditor(definition = stagedDefinition, task = openTask, onEdit = ::applyEdit)
                                TerrariumCreatureActions(
                                    definition = stagedDefinition,
                                    selected = openTask,
                                    onEdit = ::applyEdit,
                                    removeBlocked = terrariumRemoveBlocked(liveRun),
                                    modifier = Modifier.padding(top = 12.dp),
                                )
                            }
                        }
                    } }
                }
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

        val baseField: @Composable () -> Unit = field
        // A long press anywhere on the swarm opens its menu (Delete hive, which asks first). It only
        // watches: a drag, a pinch or a creature's own gesture cancels it, so none of them is disturbed.
        val shownField: @Composable () -> Unit = if (hiveDeletion == null || readOnly) {
            baseField
        } else {
            {
                val hiveOptionsLabel = stringResource(Res.string.hive_menu_options)
                Box(
                    Modifier.fillMaxSize()
                        // The same menu for a screen reader or keyboard, which cannot long-press the field.
                        .semantics { onLongClick(hiveOptionsLabel) { hiveMenu = true; true } }
                        .pointerInput(Unit) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            val ended = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                                while (true) {
                                    val event = awaitPointerEvent()
                                    val change = event.changes.firstOrNull { it.id == down.id }
                                    if (event.changes.size > 1 || change == null || !change.pressed || change.isConsumed ||
                                        (change.position - down.position).getDistance() > viewConfiguration.touchSlop
                                    ) {
                                        return@withTimeoutOrNull true
                                    }
                                }
                                @Suppress("UNREACHABLE_CODE")
                                false
                            }
                            if (ended == null) hiveMenu = true
                        }
                    },
                ) { baseField() }
            }
        }
        if (hiveMenu && hiveDeletion != null) {
            HiveActionsMenu(
                name = definition.name,
                onOpen = null,
                onDelete = { hiveDeleting = true },
                onDismiss = { hiveMenu = false },
            )
        }
        if (hiveDeleting && hiveDeletion != null) {
            HiveDeleteRequest(
                name = definition.name,
                creatures = stagedDefinition.tasks.size,
                actions = hiveDeletion,
                onFinished = { hiveDeleting = false },
            )
        }
        if (frame == null) {
            Box(Modifier.fillMaxWidth().height(if (compact) 470.dp else 620.dp).tourTarget(TourTargetId.HIVE_CREATURES)) { shownField() }
            // No shell to hold a sheet: the flipped card's detail sits under the field.
            if (shellFlip == null && flipping) flippedId?.let { id ->
                flipHost.detail?.invoke(id, Modifier.padding(top = 8.dp))
            }
        } else {
            Box(Modifier.tourTarget(TourTargetId.HIVE_CREATURES)) {
                HiveTissueFrame(height = frame.tissueHeight, building = building) { shownField() }
            }
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
}
