package com.hereliesaz.geministrator

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.hereliesaz.geministrator.domain.WorkflowRun
import com.hereliesaz.geministrator.domain.WorkflowRunStatus
import com.hereliesaz.geministrator.resources.Res
import com.hereliesaz.geministrator.resources.scar_hatched
import com.hereliesaz.geministrator.resources.scar_withered
import org.jetbrains.compose.resources.stringResource

/** Finished runs of this workflow, newest first. */
internal fun runScars(currentRun: WorkflowRun, runs: List<WorkflowRun>): List<WorkflowRun> = runs
    .filter { it.workflowDefinitionId == currentRun.workflowDefinitionId && it.id != currentRun.id }
    .filter { it.status in setOf(WorkflowRunStatus.Completed, WorkflowRunStatus.Failed, WorkflowRunStatus.Cancelled) }
    .sortedByDescending { it.updatedAtEpochMillis }

/** Scars (docs/Swarm-OS.md): the neighbourhood's past runs. Tapping one replays it in place. */
@Composable
internal fun RunScars(
    currentRun: WorkflowRun,
    onLoadRuns: suspend () -> List<WorkflowRun>,
    onReplay: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var scars by remember(currentRun.id) { mutableStateOf<List<WorkflowRun>>(emptyList()) }
    LaunchedEffect(currentRun.id) { scars = runScars(currentRun, onLoadRuns()) }
    if (scars.isEmpty()) return
    Row(modifier = modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        scars.forEach { scar ->
            AzphaltPill(
                label = stringResource(if (scar.status == WorkflowRunStatus.Completed) Res.string.scar_hatched else Res.string.scar_withered),
                seed = "scar-${scar.id.value}",
                endCap = scar.objective.take(24),
                onClick = { onReplay(scar.id.value) },
            )
        }
    }
}
