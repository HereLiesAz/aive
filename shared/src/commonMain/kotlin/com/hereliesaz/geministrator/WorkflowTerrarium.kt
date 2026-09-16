package com.hereliesaz.geministrator

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.hereliesaz.conveyance.h2g2.H2g2SwarmTerrarium
import com.hereliesaz.conveyance.h2g2.H2g2TerrariumRelationshipKind
import com.hereliesaz.geministrator.domain.RoleDefinition
import com.hereliesaz.geministrator.domain.TaskDefinitionId
import com.hereliesaz.geministrator.domain.TaskRunStatus
import com.hereliesaz.geministrator.domain.WorkflowDefinition
import com.hereliesaz.geministrator.domain.WorkflowDefinitionId
import com.hereliesaz.geministrator.domain.WorkflowRun
import com.hereliesaz.geministrator.persistence.SettingsWorkflowPersistence
import kotlinx.coroutines.launch

/**
 * Production workflow view: the DAG is a living terrarium rather than a diagram.
 *
 * Habitat movement is presentation-only and persisted independently. Dropping one real task-creature
 * onto another means "dragged depends on target". The resulting definition is staged as a draft;
 * the currently executing run remains immutable.
 */
@Composable
internal fun GeministratorWorkflowTerrarium(
    definition: WorkflowDefinition,
    run: WorkflowRun,
    roles: Collection<RoleDefinition>,
    selectedTaskId: String?,
    onTaskSelected: (String) -> Unit,
    onStageDefinition: (suspend (WorkflowDefinition) -> WorkflowDefinition)? = null,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    val scope = rememberCoroutineScope()
    val layoutStore = remember { SettingsTerrariumLayoutStore.createDefault() }
    val fallbackDraftPersistence = remember { SettingsWorkflowPersistence.createDefault() }
    val layoutDefinitionId = run.workflowDefinitionId
    var positions by remember(layoutDefinitionId) {
        mutableStateOf(layoutStore.load(layoutDefinitionId))
    }
    var stagedDefinition by remember(definition) { mutableStateOf(definition) }
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

    val projection = remember(stagedDefinition, run, roles, positions) {
        projectWorkflowTerrarium(
            definition = stagedDefinition,
            run = run,
            roles = roles,
            persistedPositions = positions,
        )
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

    Column(modifier = modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(if (compact) 470.dp else 620.dp),
        ) {
            H2g2SwarmTerrarium(
                subjects = projection.subjects,
                relationships = animatedRelationships,
                adornments = projection.adornments,
                serviceVisits = projection.serviceVisits,
                editable = true,
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
                onNodeDroppedOn = { downstreamRaw, upstreamRaw ->
                    if (downstreamRaw == TERRARIUM_ORCHESTRATOR_ID || upstreamRaw == TERRARIUM_ORCHESTRATOR_ID) {
                        authoringMessage = "The orchestrator is the queen/root visual, not a draggable workflow dependency."
                        return@H2g2SwarmTerrarium
                    }
                    val downstreamId = TaskDefinitionId(downstreamRaw)
                    val upstreamId = TaskDefinitionId(upstreamRaw)
                    when (val edit = addTerrariumDependency(stagedDefinition, downstreamId, upstreamId)) {
                        is TerrariumDependencyEditResult.Applied -> {
                            scope.launch {
                                runCatching { stageDefinition(edit.definition) }
                                    .onSuccess { persisted ->
                                        stagedDefinition = persisted
                                        authoringMessage = "Draft wiring staged: $downstreamRaw now depends on $upstreamRaw."
                                    }
                                    .onFailure { failure ->
                                        authoringMessage = failure.message ?: "Could not stage workflow edit."
                                    }
                            }
                        }
                        TerrariumDependencyEditResult.NoChange -> {
                            authoringMessage = "$downstreamRaw already depends on $upstreamRaw."
                        }
                        is TerrariumDependencyEditResult.Rejected -> {
                            authoringMessage = edit.reason
                        }
                    }
                },
                orchestratorContent = { _, _, childModifier ->
                    TerrariumOrchestratorCharacter(
                        modifier = childModifier.padding(8.dp),
                    )
                },
                modifier = Modifier.fillMaxSize(),
            )
        }

        authoringMessage?.let { message ->
            Text(
                text = message,
                style = AzphaltType.body,
                color = Azphalt.currentGround.onPage,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}
