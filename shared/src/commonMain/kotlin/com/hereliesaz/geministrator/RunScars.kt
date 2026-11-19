package com.hereliesaz.geministrator

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
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
import com.hereliesaz.geministrator.resources.run_all_empty
import com.hereliesaz.geministrator.resources.run_all_filter_all
import com.hereliesaz.geministrator.resources.run_all_filter_done
import com.hereliesaz.geministrator.resources.run_all_filter_failed
import com.hereliesaz.geministrator.resources.run_all_filter_paused
import com.hereliesaz.geministrator.resources.run_all_filter_running
import com.hereliesaz.geministrator.resources.run_all_filter_stopped
import com.hereliesaz.geministrator.resources.run_all_open_now
import com.hereliesaz.geministrator.resources.run_all_pill
import com.hereliesaz.geministrator.resources.scar_hatched
import com.hereliesaz.geministrator.resources.scar_stopped
import com.hereliesaz.geministrator.resources.scar_withered
import org.jetbrains.compose.resources.stringResource

/** Finished runs of this workflow, newest first. */
internal fun runScars(currentRun: WorkflowRun, runs: List<WorkflowRun>): List<WorkflowRun> = runs
    .filter { it.workflowDefinitionId == currentRun.workflowDefinitionId && it.id != currentRun.id }
    .filter { it.status in setOf(WorkflowRunStatus.Completed, WorkflowRunStatus.Failed, WorkflowRunStatus.Cancelled) }
    .sortedByDescending { it.updatedAtEpochMillis }

/** The status groups the "All runs" list can be narrowed to. */
internal enum class RunStatusFilter { All, Running, Paused, Done, Failed, Stopped }

/** The group a run falls in: unfinished runs are Running, or Paused while the keeper's pause is on; the rest by how they ended. */
internal fun runStatusGroup(run: WorkflowRun): RunStatusFilter = when (run.status) {
    WorkflowRunStatus.Completed -> RunStatusFilter.Done
    WorkflowRunStatus.Failed -> RunStatusFilter.Failed
    WorkflowRunStatus.Cancelled -> RunStatusFilter.Stopped
    else -> if (run.keeperPause != null) RunStatusFilter.Paused else RunStatusFilter.Running
}

/** Every run of every project, newest first, narrowed by [filter]: running and paused ones included (unlike [runScars]). */
internal fun allRunsFor(runs: List<WorkflowRun>, filter: RunStatusFilter): List<WorkflowRun> = runs
    .filter { filter == RunStatusFilter.All || runStatusGroup(it) == filter }
    .sortedByDescending { it.updatedAtEpochMillis }

/**
 * Scars (docs/Swarm-OS.md): the neighbourhood's past runs. Tapping one replays it in place. The last pill, All runs, opens the
 * list of every run of every project (running and paused too) with its status, full objective and id, a status filter, and the
 * run on show marked; tapping a run opens it the same way a scar does.
 */
@Composable
internal fun RunScars(
    currentRun: WorkflowRun,
    onLoadRuns: suspend () -> List<WorkflowRun>,
    onReplay: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var every by remember(currentRun.id) { mutableStateOf<List<WorkflowRun>>(emptyList()) }
    var allOpen by remember(currentRun.id) { mutableStateOf(false) }
    var filter by remember(currentRun.id) { mutableStateOf(RunStatusFilter.All) }
    LaunchedEffect(currentRun.id) { every = onLoadRuns() }
    val scars = runScars(currentRun, every)
    if (every.isEmpty()) return
    Column(modifier = modifier) {
        Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            scars.forEach { scar ->
                AzphaltPill(
                    label = stringResource(
                        when (scar.status) {
                            WorkflowRunStatus.Completed -> Res.string.scar_hatched
                            WorkflowRunStatus.Cancelled -> Res.string.scar_stopped
                            else -> Res.string.scar_withered
                        },
                    ),
                    seed = "scar-${scar.id.value}",
                    endCap = scar.objective.take(24),
                    onClick = { onReplay(scar.id.value) },
                )
            }
            AzphaltPill(
                label = stringResource(Res.string.run_all_pill),
                seed = "scar-all-runs",
                selected = allOpen,
                onClick = { allOpen = !allOpen },
            )
        }
        AzphaltChildBand(visible = allOpen) {
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()).padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                RunStatusFilter.entries.forEach { option ->
                    AzphaltPill(
                        label = stringResource(
                            when (option) {
                                RunStatusFilter.All -> Res.string.run_all_filter_all
                                RunStatusFilter.Running -> Res.string.run_all_filter_running
                                RunStatusFilter.Paused -> Res.string.run_all_filter_paused
                                RunStatusFilter.Done -> Res.string.run_all_filter_done
                                RunStatusFilter.Failed -> Res.string.run_all_filter_failed
                                RunStatusFilter.Stopped -> Res.string.run_all_filter_stopped
                            },
                        ),
                        seed = "run-all-filter-${option.name}",
                        selected = filter == option,
                        onClick = { filter = option },
                    )
                }
            }
            val shown = allRunsFor(every, filter)
            Column(
                modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp).verticalScroll(rememberScrollState()).padding(top = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (shown.isEmpty()) {
                    Text(stringResource(Res.string.run_all_empty), style = AzphaltType.body, color = azphaltOnSurface())
                }
                shown.forEach { run ->
                    val statusLabel = stringResource(
                        when (runStatusGroup(run)) {
                            RunStatusFilter.Done -> Res.string.run_all_filter_done
                            RunStatusFilter.Failed -> Res.string.run_all_filter_failed
                            RunStatusFilter.Stopped -> Res.string.run_all_filter_stopped
                            RunStatusFilter.Paused -> Res.string.run_all_filter_paused
                            else -> Res.string.run_all_filter_running
                        },
                    )
                    val active = run.id == currentRun.id
                    Column(modifier = Modifier.fillMaxWidth().clickable { onReplay(run.id.value) }) {
                        Text(
                            text = statusLabel + " · " + run.id.value + if (active) " · " + stringResource(Res.string.run_all_open_now) else "",
                            style = AzphaltType.section,
                            color = azphaltOnSurface(),
                        )
                        // The whole objective, wrapped: never cut to the pill's few characters.
                        Text(text = run.objective, style = AzphaltType.body, color = azphaltOnSurface())
                    }
                }
            }
        }
    }
}
