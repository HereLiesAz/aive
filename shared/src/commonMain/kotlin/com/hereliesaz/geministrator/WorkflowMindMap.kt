package com.hereliesaz.geministrator

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.hereliesaz.conveyance.h2g2.H2g2SemanticWorkflowMap
import com.hereliesaz.geministrator.domain.RoleDefinition
import com.hereliesaz.geministrator.domain.TaskDefinitionId
import com.hereliesaz.geministrator.domain.WorkflowDefinition
import com.hereliesaz.geministrator.domain.WorkflowRun

/**
 * Live runtime entry point. The rendered map is a projection of workflow/run state rather than a
 * parallel UI model, so provider assignment, blocking, retries and progress can move the same nodes.
 *
 * When [branchIsolation] is true and a [selectedTaskId] is set, only the selected node's
 * ancestors and descendants are rendered — useful for large DAGs where context around a single
 * task matters more than the full picture.
 *
 * The renderer itself owns semantic map navigation: overview summaries -> band neighborhood ->
 * node neighborhood. Tap, pinch, and Back move between those fixed levels instead of exposing a
 * free camera.
 */
@Composable
internal fun GeministratorWorkflowMindMap(
    definition: WorkflowDefinition,
    run: WorkflowRun,
    roles: Collection<RoleDefinition>,
    selectedTaskId: String?,
    onTaskSelected: (String) -> Unit,
    branchIsolation: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val fullProjection = remember(definition, run, roles) {
        projectWorkflowMindMap(definition, run, roles)
    }
    val focusId = if (branchIsolation) selectedTaskId?.let(::TaskDefinitionId) else null
    val projection = remember(fullProjection, focusId) {
        fullProjection.focusOn(focusId, definition)
    }
    H2g2SemanticWorkflowMap(
        bands = projection.bands,
        edges = projection.edges,
        selectedId = selectedTaskId,
        onNodeSelected = { onTaskSelected(it.id) },
        modifier = modifier,
    )
}
