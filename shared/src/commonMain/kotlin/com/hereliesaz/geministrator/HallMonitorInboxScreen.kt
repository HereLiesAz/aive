package com.hereliesaz.geministrator

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.hereliesaz.geministrator.domain.ArtifactKind
import com.hereliesaz.geministrator.domain.TaskRunStatus

private const val HALL_MONITOR_TRIAL_DESCRIPTION_PREFIX = "Isolated Hall Monitor counterfactual"

internal fun isHallMonitorTrial(presentation: LiveWorkflowPresentation): Boolean =
    presentation.definition.description?.startsWith(HALL_MONITOR_TRIAL_DESCRIPTION_PREFIX) == true

@Composable
internal fun HallMonitorPauseInboxScreen(
    presentation: LiveWorkflowPresentation,
    onTestSolution: (String, Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val pause = requireNotNull(presentation.run.globalPause)
    val reportArtifact = presentation.run.taskRuns.values
        .asSequence()
        .flatMap { it.artifacts.asSequence() }
        .firstOrNull { it.id == pause.reportArtifactId || it.kind == ArtifactKind.HallMonitorReport }
    val reportResult = runCatching { decodeHallMonitorReportPayload(reportArtifact?.textContent.orEmpty()) }
    val report = reportResult.getOrNull()

    Column(
        modifier = modifier
            .fillMaxHeight()
            .verticalScroll(rememberScrollState())
            .padding(26.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("INBOX", style = AzphaltType.hero, color = Azphalt.currentGround.onPage)
        AzphaltRecord(
            seed = "hall-monitor-global-pause",
            eyebrow = "GLOBAL PAUSE",
            title = report?.title ?: "Hall Monitor review",
            body = buildString {
                append(pause.reason)
                append("\n\nThe original run remains frozen while you review or test recommendations. Testing does not approve or apply anything.")
            },
            endCap = "PAUSED",
            well = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (report == null) {
                        Text(
                            reportResult.exceptionOrNull()?.message
                                ?: "The Hall Monitor report cannot be parsed into testable solutions.",
                            style = AzphaltType.body,
                            color = Azphalt.currentGround.onPage,
                        )
                    } else {
                        report.findings.forEach { finding ->
                            Text(
                                "${finding.scope.name.uppercase()} · ${finding.subject}",
                                style = AzphaltType.eyebrow,
                                color = Azphalt.currentGround.onPage,
                            )
                            Text(
                                finding.observation,
                                style = AzphaltType.body,
                                color = Azphalt.currentGround.onPage,
                            )
                            finding.solutions.forEachIndexed { solutionIndex, solution ->
                                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(
                                        solution.title,
                                        style = AzphaltType.lead,
                                        color = Azphalt.currentGround.onPage,
                                    )
                                    Text(
                                        "${solution.action.name.uppercase()} · ${solution.target}",
                                        style = AzphaltType.eyebrow,
                                        color = Azphalt.currentGround.onPage,
                                    )
                                    Text(
                                        solution.rationale,
                                        style = AzphaltType.body,
                                        color = Azphalt.currentGround.onPage,
                                    )
                                    AzphaltNote(
                                        seed = "hall-monitor-tests-${finding.id}-$solutionIndex",
                                        label = "VALIDATION",
                                        value = solution.validationTests.joinToString(" · "),
                                    )
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        AzphaltPill(
                                            label = "TEST SOLUTION",
                                            seed = "hall-monitor-test-${finding.id}-$solutionIndex",
                                            endCap = "Isolated",
                                            onClick = { onTestSolution(finding.id, solutionIndex) },
                                        )
                                    }
                                }
                            }
                        }
                    }

                    if (pause.solutionTrials.isNotEmpty()) {
                        Text("TRIAL HISTORY", style = AzphaltType.eyebrow, color = Azphalt.currentGround.onPage)
                        pause.solutionTrials.forEach { trial ->
                            AzphaltNote(
                                seed = "hall-monitor-trial-${trial.id}",
                                label = trial.solutionTitle,
                                value = "${trial.action.name} · ${trial.workflowRunId.value}",
                            )
                        }
                    }
                }
            },
        )
    }
}

@Composable
internal fun HallMonitorTrialInboxScreen(
    presentation: LiveWorkflowPresentation,
    onApproveTask: (String) -> Unit,
    onRejectPlan: (String) -> Unit,
    onResolveEscalation: (String, Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val taskRuns = presentation.run.taskRuns.values
    val completed = taskRuns.count { it.status == TaskRunStatus.Completed }
    val artifacts = taskRuns.flatMap { it.artifacts }

    Column(
        modifier = modifier
            .fillMaxHeight()
            .verticalScroll(rememberScrollState())
            .padding(26.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("TEST SOLUTION", style = AzphaltType.hero, color = Azphalt.currentGround.onPage)
        AzphaltRecord(
            seed = presentation.run.id.value,
            eyebrow = "ISOLATED COUNTERFACTUAL",
            title = presentation.definition.name,
            body = buildString {
                append(presentation.run.objective)
                append("\n\nThe paused source run is unchanged. This trial uses Manual integration and exists only to produce evidence.")
            },
            endCap = presentation.run.status.name,
            well = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    AzphaltNote(
                        seed = "trial-progress-${presentation.run.id.value}",
                        label = "PROGRESS",
                        value = "$completed / ${taskRuns.size} tasks complete · ${artifacts.size} artifacts",
                    )
                    taskRuns.forEach { taskRun ->
                        val task = presentation.definition.tasks.firstOrNull { it.id == taskRun.taskDefinitionId }
                        AzphaltRecord(
                            seed = "trial-task-${taskRun.id.value}",
                            eyebrow = taskRun.status.name.uppercase(),
                            title = task?.name ?: taskRun.taskDefinitionId.value,
                            body = taskRun.progressMessage ?: task?.objective.orEmpty(),
                            endCap = taskRun.progress?.let { "${(it * 100).toInt()}%" },
                            well = if (taskRun.artifacts.isEmpty() &&
                                taskRun.status !in setOf(TaskRunStatus.AwaitingApproval, TaskRunStatus.Escalated)
                            ) {
                                null
                            } else {
                                {
                                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        taskRun.artifacts.forEach { artifact ->
                                            AzphaltNote(
                                                seed = "trial-artifact-${artifact.id.value}",
                                                label = "${artifact.kind.name} · ${artifact.label}",
                                                value = artifact.textContent?.take(240)
                                                    ?: artifact.uri
                                                    ?: "stored",
                                            )
                                        }
                                        if (taskRun.status == TaskRunStatus.AwaitingApproval) {
                                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                AzphaltPill("Approve", "trial-approve-${taskRun.id.value}", onClick = {
                                                    onApproveTask(taskRun.taskDefinitionId.value)
                                                })
                                                if (taskRun.assignedProviderId != null) {
                                                    AzphaltPill("Reject", "trial-reject-${taskRun.id.value}", onClick = {
                                                        onRejectPlan(taskRun.taskDefinitionId.value)
                                                    })
                                                }
                                            }
                                        }
                                        if (taskRun.status == TaskRunStatus.Escalated) {
                                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                AzphaltPill("Retry", "trial-retry-${taskRun.id.value}", onClick = {
                                                    onResolveEscalation(taskRun.taskDefinitionId.value, true)
                                                })
                                                AzphaltPill("Stop", "trial-stop-${taskRun.id.value}", onClick = {
                                                    onResolveEscalation(taskRun.taskDefinitionId.value, false)
                                                })
                                            }
                                        }
                                    }
                                }
                            },
                        )
                    }
                }
            },
        )
    }
}
