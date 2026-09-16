package com.hereliesaz.geministrator

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.hereliesaz.geministrator.domain.RoleDefinition
import com.hereliesaz.geministrator.domain.WorkflowDefinition
import com.hereliesaz.geministrator.domain.WorkflowRun

/**
 * Live runtime entry point.
 *
 * The historical function name remains as a compatibility seam for the surrounding UI, but the
 * rendered workflow is now the living H2G2 swarm terrarium. [branchIsolation] is retained for API
 * compatibility while the terrarium interaction model replaces semantic-zoom branch slicing.
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
    GeministratorWorkflowTerrarium(
        definition = definition,
        run = run,
        roles = roles,
        selectedTaskId = selectedTaskId,
        onTaskSelected = onTaskSelected,
        modifier = modifier,
        compact = branchIsolation,
    )
}
