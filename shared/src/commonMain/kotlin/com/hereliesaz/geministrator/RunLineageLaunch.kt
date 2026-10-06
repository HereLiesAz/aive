package com.hereliesaz.geministrator

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.hereliesaz.geministrator.domain.WorkflowRun
import com.hereliesaz.geministrator.domain.WorkflowRunId
import com.hereliesaz.geministrator.domain.WorkflowRunStatus
import com.hereliesaz.geministrator.memory.MemoryTimeRange
import com.hereliesaz.geministrator.resources.Res
import com.hereliesaz.geministrator.resources.lineage_a_new_run_that_reads_this
import com.hereliesaz.geministrator.resources.lineage_cancel_continue
import com.hereliesaz.geministrator.resources.lineage_continue_this_run
import com.hereliesaz.geministrator.resources.lineage_lineage
import com.hereliesaz.geministrator.resources.lineage_loading_runs
import com.hereliesaz.geministrator.resources.lineage_next_objective
import com.hereliesaz.geministrator.resources.lineage_no_earlier_runs_yet
import com.hereliesaz.geministrator.resources.lineage_start_continuation
import com.hereliesaz.geministrator.resources.lineage_starts_a_new_line_of_work
import com.hereliesaz.geministrator.resources.lineage_the_new_run_reads_every_chosen
import com.hereliesaz.geministrator.resources.lineage_the_new_run_reads_the_chosen
import org.jetbrains.compose.resources.stringResource

/**
 * How a launch relates to earlier runs. Maps onto `continuesWorkflowRunIds` of the launch
 * functions: [New] passes none (a root run), [Continue] exactly one, [Combine] two or more.
 */
enum class LaunchLineageMode(val label: String) {
    New("New"),
    Continue("Continue a run"),
    Combine("Combine runs"),
}

/** One earlier run offered to the launch lineage picker. */
data class LaunchableRun(
    val id: WorkflowRunId,
    val workflowName: String,
    val projectName: String,
    val objective: String,
    val status: WorkflowRunStatus,
    val createdAtEpochMillis: Long,
) {
    val dateLabel: String get() = MemoryTimeRange.isoDate(createdAtEpochMillis)
}

/** Pure launch-lineage selection state; the Compose picker only renders and forwards events. */
data class LaunchLineageState(
    val mode: LaunchLineageMode = LaunchLineageMode.New,
    val selected: List<WorkflowRunId> = emptyList(),
) {
    /** Switch mode. Continue keeps at most the first selected run; New clears the selection. */
    fun withMode(next: LaunchLineageMode): LaunchLineageState = when (next) {
        LaunchLineageMode.New -> LaunchLineageState(LaunchLineageMode.New)
        LaunchLineageMode.Continue -> LaunchLineageState(next, selected.take(1))
        LaunchLineageMode.Combine -> copy(mode = next)
    }

    /** Tap a run: Continue replaces the single choice, Combine toggles membership, New ignores. */
    fun toggle(runId: WorkflowRunId): LaunchLineageState = when (mode) {
        LaunchLineageMode.New -> this
        LaunchLineageMode.Continue -> copy(selected = if (selected == listOf(runId)) emptyList() else listOf(runId))
        LaunchLineageMode.Combine -> copy(selected = if (runId in selected) selected - runId else selected + runId)
    }

    /** Drop selections that are no longer offered (deleted or filtered runs). */
    fun retainOnly(available: Collection<WorkflowRunId>): LaunchLineageState {
        val keep = available.toSet()
        return copy(selected = selected.filter { it in keep })
    }

    /** Why the current selection cannot launch, or null when it can. */
    val validationError: String?
        get() = when (mode) {
            LaunchLineageMode.New -> null
            LaunchLineageMode.Continue -> if (selected.size == 1) null else "Pick the run to continue."
            LaunchLineageMode.Combine -> if (selected.size >= 2) null else "Pick at least two runs to combine."
        }

    /** The value to pass as `continuesWorkflowRunIds`, in selection order. */
    val continuesWorkflowRunIds: List<WorkflowRunId>
        get() {
            check(validationError == null) { validationError.orEmpty() }
            return if (mode == LaunchLineageMode.New) emptyList() else selected.distinct()
        }

    companion object {
        /** State for "Continue" launched from a run's own view. */
        fun continuing(runId: WorkflowRunId) = LaunchLineageState(LaunchLineageMode.Continue, listOf(runId))
    }
}

/** True when a run has finished and can be offered for continuation from its detail view. */
fun WorkflowRunStatus.isFinished(): Boolean =
    this == WorkflowRunStatus.Completed || this == WorkflowRunStatus.Failed || this == WorkflowRunStatus.Cancelled

/** Every persisted run, newest first, with its workflow and project names for the picker. */
suspend fun ApplicationRuntime.loadLaunchableRuns(): List<LaunchableRun> {
    val definitionNames = HashMap<String, String>()
    return persistence.projects.all().flatMap { project ->
        persistence.runs.byProject(project.id).map { run: WorkflowRun ->
            val workflowName = definitionNames.getOrPut(run.workflowDefinitionId.value) {
                persistence.definitions.get(run.workflowDefinitionId)?.name ?: run.workflowDefinitionId.value
            }
            LaunchableRun(
                id = run.id,
                workflowName = workflowName,
                projectName = project.name,
                objective = run.objective,
                status = run.status,
                createdAtEpochMillis = run.createdAtEpochMillis,
            )
        }
    }.sortedByDescending(LaunchableRun::createdAtEpochMillis)
}

@Composable
internal fun LaunchLineagePicker(
    state: LaunchLineageState,
    runs: List<LaunchableRun>?,
    onStateChange: (LaunchLineageState) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(Res.string.lineage_lineage), style = AzphaltType.eyebrow, color = Azphalt.currentGround.onPage)
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LaunchLineageMode.entries.forEach { mode ->
                AzphaltPill(
                    label = mode.label,
                    seed = "launch-lineage-${mode.name}",
                    selected = state.mode == mode,
                    onClick = { onStateChange(state.withMode(mode)) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
        if (state.mode == LaunchLineageMode.New) {
            Text(
                stringResource(Res.string.lineage_starts_a_new_line_of_work),
                style = AzphaltType.body,
                color = Azphalt.currentGround.onPage,
            )
            return@Column
        }
        Text(
            if (state.mode == LaunchLineageMode.Continue) {
                stringResource(Res.string.lineage_the_new_run_reads_the_chosen)
            } else {
                stringResource(Res.string.lineage_the_new_run_reads_every_chosen)
            },
            style = AzphaltType.body,
            color = Azphalt.currentGround.onPage,
        )
        when {
            runs == null -> Text(stringResource(Res.string.lineage_loading_runs), style = AzphaltType.body, color = Azphalt.currentGround.onPage)
            runs.isEmpty() -> Text(stringResource(Res.string.lineage_no_earlier_runs_yet), style = AzphaltType.body, color = Azphalt.currentGround.onPage)
            else -> runs.forEach { run ->
                val order = state.selected.indexOf(run.id)
                AzphaltRecord(
                    seed = "lineage-${run.id.value}",
                    eyebrow = "${run.projectName} · ${run.dateLabel}",
                    title = run.workflowName,
                    body = run.objective.take(80).let { if (run.objective.length > 80) "$it…" else it },
                    endCap = if (order >= 0 && state.mode == LaunchLineageMode.Combine) "#${order + 1}" else run.status.name,
                    selected = order >= 0,
                    onClick = { onStateChange(state.toggle(run.id)) },
                )
            }
        }
    }
}

/** "Continue" on a finished run's view: asks for the next objective and launches a continuation. */
@Composable
internal fun ContinueRunPanel(
    runId: WorkflowRunId,
    onContinue: (objective: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var open by remember(runId) { mutableStateOf(false) }
    var objective by remember(runId) { mutableStateOf("") }
    var error by remember(runId) { mutableStateOf<String?>(null) }
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        AzphaltPill(
            label = if (open) stringResource(Res.string.lineage_cancel_continue) else stringResource(Res.string.lineage_continue_this_run),
            seed = "continue-run",
            selected = open,
            onClick = { open = !open; error = null },
            modifier = Modifier.fillMaxWidth(),
        )
        if (!open) return@Column
        Text(
            stringResource(Res.string.lineage_a_new_run_that_reads_this),
            style = AzphaltType.body,
            color = Azphalt.currentGround.onPage,
        )
        OutlinedTextField(
            value = objective,
            onValueChange = { objective = it; error = null },
            label = { Text(stringResource(Res.string.lineage_next_objective)) },
            minLines = 2,
            modifier = Modifier.fillMaxWidth(),
        )
        AzphaltPill(
            label = stringResource(Res.string.lineage_start_continuation),
            seed = "continue-run-start",
            selected = objective.isNotBlank(),
            onClick = {
                if (objective.isBlank()) {
                    error = "Enter an objective to continue this run."
                } else {
                    onContinue(objective.trim())
                    open = false
                    objective = ""
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
        error?.let { Text(it, style = AzphaltType.body, color = MaterialTheme.colorScheme.error) }
    }
}
