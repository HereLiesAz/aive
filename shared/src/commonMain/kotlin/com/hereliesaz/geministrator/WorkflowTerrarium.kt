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
import com.hereliesaz.geministrator.resources.terrarium_could_not_stage_workflow_edit
import org.jetbrains.compose.resources.getString

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
    var building by remember(definition.id) { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxWidth()) {
        TerrariumModeToggle(building = building, onBuildingChange = { building = it })
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(if (compact) 470.dp else 620.dp),
        ) {
            PlatformNodeTerrarium(
                subjects = projection.subjects,
                relationships = animatedRelationships,
                adornments = projection.adornments,
                serviceVisits = projection.serviceVisits,
                editable = true,
                compact = compact,
                tissue = if (building && rehearsal == null) tissue.copy(egg = null) else tissue,
                selectedId = selectedTaskId,
                onNodeSelected = { node ->
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
                modifier = Modifier.fillMaxSize(),
            )
        }

        if (building) {
            TerrariumConnections(
                definition = stagedDefinition,
                selectedTaskId = selectedTaskId,
                canUndo = tide.canUndo,
                canRedo = tide.canRedo,
                onRehearse = { rehearsal = rehearsalFrames(stagedDefinition, liveRun) },
                onEdit = ::applyEdit,
                onUndo = {
                    val back = tide.undo()
                    scope.launch {
                        runCatching { stageDefinition(back.present) }
                            .onSuccess { persisted -> tide = back.copy(present = persisted) }
                            .onFailure { failure -> authoringMessage = failure.message }
                    }
                },
                onRedo = {
                    val forward = tide.redo()
                    scope.launch {
                        runCatching { stageDefinition(forward.present) }
                            .onSuccess { persisted -> tide = forward.copy(present = persisted) }
                            .onFailure { failure -> authoringMessage = failure.message }
                    }
                },
                modifier = Modifier.padding(top = 8.dp),
            )
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
