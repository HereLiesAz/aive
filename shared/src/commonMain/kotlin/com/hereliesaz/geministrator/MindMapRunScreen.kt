package com.hereliesaz.geministrator

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.hereliesaz.geministrator.domain.RepositoryRef
import com.hereliesaz.geministrator.domain.TaskRunStatus
import com.hereliesaz.geministrator.domain.WorkflowRunStatus

@Composable
internal fun MindMapRunScreen(
    modifier: Modifier,
    selectedTaskId: String?,
    onTaskSelected: (String) -> Unit,
    onLaunchWorkflow: (String, String, RepositoryRef?) -> Unit,
    onRecoverFromCorruption: () -> Unit = {},
    onRetryRuntime: () -> Unit = {},
    onReconfigureProvider: (String) -> Unit = {},
    onValidateWorkflow: () -> List<String> = { emptyList() },
    compact: Boolean,
    runtimeState: ApplicationRuntimeState,
) {
    var branchIsolation by remember { mutableStateOf(false) }
    val liveWorkflow = (runtimeState as? ApplicationRuntimeState.Live)?.presentation
    val activityEntrance = remember { AzphaltEntrance.childBand() }
    val existingRepository = (runtimeState as? ApplicationRuntimeState.NoRun)?.project?.repository
    var projectName by remember(runtimeState) {
        mutableStateOf((runtimeState as? ApplicationRuntimeState.NoRun)?.project?.name.orEmpty())
    }
    var repositoryOwner by remember(runtimeState) { mutableStateOf(existingRepository?.owner.orEmpty()) }
    var repositoryName by remember(runtimeState) { mutableStateOf(existingRepository?.name.orEmpty()) }
    var defaultBranch by remember(runtimeState) { mutableStateOf(existingRepository?.defaultBranch.orEmpty()) }
    var objective by remember(runtimeState) { mutableStateOf("") }

    Column(
        modifier = modifier
            .fillMaxHeight()
            .verticalScroll(rememberScrollState())
            .padding(if (compact) 16.dp else 26.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        if (liveWorkflow == null) {
            RuntimeStateRecord(runtimeState, compact)
            val corrupted = (runtimeState as? ApplicationRuntimeState.ResumeFailed)?.isCorrupted == true
            if (corrupted) {
                AzphaltPill(
                    "Recover — clear corrupted data",
                    "recover-corruption",
                    onClick = onRecoverFromCorruption,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            val recoverableRuntimeFailure =
                (runtimeState is ApplicationRuntimeState.ResumeFailed && !corrupted) ||
                    runtimeState is ApplicationRuntimeState.Disconnected
            if (recoverableRuntimeFailure) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    AzphaltPill(
                        "Retry runtime",
                        "retry-runtime",
                        onClick = onRetryRuntime,
                        modifier = Modifier.weight(1f),
                    )
                    AzphaltPill(
                        "Reconfigure Jules",
                        "reconfigure-jules",
                        onClick = { onReconfigureProvider("jules") },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            if (runtimeState == ApplicationRuntimeState.NoProject || runtimeState is ApplicationRuntimeState.NoRun) {
                OutlinedTextField(
                    value = projectName,
                    onValueChange = { projectName = it },
                    label = { Text("Project name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = repositoryOwner,
                    onValueChange = { repositoryOwner = it },
                    label = { Text("Repository owner") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = repositoryName,
                    onValueChange = { repositoryName = it },
                    label = { Text("Repository name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = defaultBranch,
                    onValueChange = { defaultBranch = it },
                    label = { Text("Default branch (optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = objective,
                    onValueChange = { objective = it },
                    label = { Text("Objective") },
                    minLines = if (compact) 2 else 3,
                    modifier = Modifier.fillMaxWidth(),
                )
                val owner = repositoryOwner.trim()
                val name = repositoryName.trim()
                val repositoryComplete = (owner.isEmpty() && name.isEmpty()) || (owner.isNotEmpty() && name.isNotEmpty())
                Button(
                    onClick = {
                        val repository = if (owner.isNotEmpty() && name.isNotEmpty()) {
                            RepositoryRef(
                                owner = owner,
                                name = name,
                                defaultBranch = defaultBranch.trim().takeIf(String::isNotEmpty),
                            )
                        } else {
                            null
                        }
                        onLaunchWorkflow(projectName.trim(), objective.trim(), repository)
                    },
                    enabled = projectName.isNotBlank() && objective.isNotBlank() && repositoryComplete,
                ) {
                    Text(if (runtimeState is ApplicationRuntimeState.NoRun) "START RUN" else "CREATE PROJECT + START RUN")
                }
            }
            Spacer(Modifier.height(24.dp))
            return@Column
        }

        val run = liveWorkflow.run
        val completed = run.taskRuns.values.count { it.status == TaskRunStatus.Completed }
        val total = run.taskRuns.size

        Text(
            run.objective.uppercase(),
            style = if (compact) AzphaltType.section else AzphaltType.hero,
            color = Azphalt.currentGround.onPage,
        )
        Text(
            liveWorkflow.definition.name.uppercase(),
            style = AzphaltType.eyebrow,
            color = Azphalt.currentGround.onPage,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AzphaltPill(
                run.status.name,
                "run-status",
                endCap = "$completed/$total",
                onClick = {},
            )
        }

        val awaitingHuman = run.status == WorkflowRunStatus.AwaitingHuman
        AzphaltRecord(
            seed = "owner-attention",
            eyebrow = "Owner attention",
            title = if (awaitingHuman) "Decision required" else "No decision required",
            body = if (awaitingHuman) {
                "A workflow gate is waiting for human input."
            } else {
                "Runtime is advancing without human intervention."
            },
            endCap = if (awaitingHuman) "Required" else "Clear",
        )

        val validationErrors = remember(liveWorkflow.definition) { onValidateWorkflow() }
        if (validationErrors.isNotEmpty()) {
            Text("WORKFLOW ERRORS", style = AzphaltType.eyebrow, color = Azphalt.currentGround.onPage)
            validationErrors.forEachIndexed { index, error ->
                AzphaltRecord(
                    seed = "validation-error-$index",
                    eyebrow = "Invalid",
                    title = "Workflow definition error",
                    body = error,
                    endCap = "Blocked",
                )
            }
        }
        val activeTaskId = remember(run) {
            run.taskRuns.values
                .firstOrNull { it.status == TaskRunStatus.Running || it.status == TaskRunStatus.Planning }
                ?.taskDefinitionId?.value
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("COMPANY EXECUTION", style = AzphaltType.eyebrow, color = Azphalt.currentGround.onPage, modifier = Modifier.weight(1f))
            if (activeTaskId != null) {
                AzphaltPill(
                    label = "Jump to active",
                    seed = "jump-active",
                    onClick = { onTaskSelected(activeTaskId) },
                )
            }
            AzphaltPill(
                label = "Branch",
                seed = "branch-isolation",
                selected = branchIsolation,
                onClick = { branchIsolation = !branchIsolation },
            )
        }
        GeministratorWorkflowMindMap(
            definition = liveWorkflow.definition,
            run = run,
            roles = liveWorkflow.roles,
            selectedTaskId = selectedTaskId,
            onTaskSelected = onTaskSelected,
            branchIsolation = branchIsolation,
            modifier = Modifier.fillMaxWidth(),
        )

        Text("COMPANY ACTIVITY", style = AzphaltType.eyebrow, color = Azphalt.currentGround.onPage)
        val recentTasks = liveWorkflow.definition.tasks
            .mapNotNull { task -> run.taskRuns[task.id]?.let { taskRun -> task to taskRun } }
            .sortedByDescending { (_, taskRun) -> taskRun.status.activityRank() }
            .take(8)
        recentTasks.forEachIndexed { index, (task, taskRun) ->
            val detail = buildString {
                append(taskRun.status.name)
                taskRun.progressMessage?.takeIf(String::isNotBlank)?.let {
                    append(" · ")
                    append(it)
                }
                taskRun.blockingReason?.message?.takeIf(String::isNotBlank)?.let {
                    append(" · ")
                    append(it)
                }
            }
            AzphaltNote(
                seed = "task-activity-${task.id.value}",
                label = task.name,
                value = detail,
                modifier = Modifier.azphaltEntrance(activityEntrance, index, recentTasks.size.coerceAtLeast(1)),
            )
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun RuntimeStateRecord(state: ApplicationRuntimeState, compact: Boolean) {
    val (title, body) = when (state) {
        ApplicationRuntimeState.Loading -> "LOADING RUNTIME" to "Reading persisted workflow state."
        ApplicationRuntimeState.NoProject -> "NO PROJECT" to "Create a project and define its first objective below."
        is ApplicationRuntimeState.NoRun -> "NO ACTIVE RUN" to "${state.project.name} has no persisted workflow run. Define an objective below to start one."
        is ApplicationRuntimeState.Disconnected -> "RUNTIME DISCONNECTED" to state.message
        is ApplicationRuntimeState.ResumeFailed -> "RESUME FAILED" to state.message
        is ApplicationRuntimeState.Live -> return
    }
    Text(
        title,
        style = if (compact) AzphaltType.section else AzphaltType.hero,
        color = Azphalt.currentGround.onPage,
    )
    AzphaltRecord(
        seed = "runtime-state-$title",
        eyebrow = "Runtime",
        title = title.lowercase().replaceFirstChar(Char::uppercase),
        body = body,
        endCap = null,
    )
}

private fun TaskRunStatus.activityRank(): Int = when (this) {
    TaskRunStatus.Running, TaskRunStatus.Planning, TaskRunStatus.Verifying -> 8
    TaskRunStatus.AwaitingApproval, TaskRunStatus.Escalated -> 7
    TaskRunStatus.Retrying -> 6
    TaskRunStatus.Ready -> 5
    TaskRunStatus.Failed -> 4
    TaskRunStatus.Completed -> 3
    TaskRunStatus.Blocked -> 2
    TaskRunStatus.Created -> 1
    TaskRunStatus.Cancelled -> 0
}
