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
import com.hereliesaz.geministrator.resources.Res
import com.hereliesaz.geministrator.resources.common_approve
import com.hereliesaz.geministrator.resources.common_inbox
import com.hereliesaz.geministrator.resources.common_reject
import com.hereliesaz.geministrator.resources.common_retry
import com.hereliesaz.geministrator.resources.hall_monitor_tasks_complete_artifacts
import com.hereliesaz.geministrator.resources.hall_monitor_global_pause
import com.hereliesaz.geministrator.resources.hall_monitor_hall_monitor_review
import com.hereliesaz.geministrator.resources.hall_monitor_isolated
import com.hereliesaz.geministrator.resources.hall_monitor_isolated_counterfactual
import com.hereliesaz.geministrator.resources.hall_monitor_paused
import com.hereliesaz.geministrator.resources.hall_monitor_progress
import com.hereliesaz.geministrator.resources.hall_monitor_stop
import com.hereliesaz.geministrator.resources.hall_monitor_test_solution
import com.hereliesaz.geministrator.resources.hall_monitor_the_hall_monitor_report_cannot_be
import com.hereliesaz.geministrator.resources.hall_monitor_the_original_run_remains_frozen_while
import com.hereliesaz.geministrator.resources.hall_monitor_the_paused_source_run_is_unchanged
import com.hereliesaz.geministrator.resources.hall_monitor_trial_history
import com.hereliesaz.geministrator.resources.hall_monitor_validation
import org.jetbrains.compose.resources.stringResource

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
        Text(stringResource(Res.string.common_inbox), style = AzphaltType.hero, color = Azphalt.currentGround.onPage)
        AzphaltRecord(
            seed = "hall-monitor-global-pause",
            eyebrow = stringResource(Res.string.hall_monitor_global_pause),
            title = report?.title ?: stringResource(Res.string.hall_monitor_hall_monitor_review),
            body = buildString {
                append(pause.reason)
                append(stringResource(Res.string.hall_monitor_the_original_run_remains_frozen_while))
            },
            endCap = stringResource(Res.string.hall_monitor_paused),
            well = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (report == null) {
                        Text(
                            reportResult.exceptionOrNull()?.message
                                ?: stringResource(Res.string.hall_monitor_the_hall_monitor_report_cannot_be),
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
                                        label = stringResource(Res.string.hall_monitor_validation),
                                        value = solution.validationTests.joinToString(" · "),
                                    )
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        AzphaltPill(
                                            label = stringResource(Res.string.hall_monitor_test_solution),
                                            seed = "hall-monitor-test-${finding.id}-$solutionIndex",
                                            endCap = stringResource(Res.string.hall_monitor_isolated),
                                            onClick = { onTestSolution(finding.id, solutionIndex) },
                                        )
                                    }
                                }
                            }
                        }
                    }

                    if (pause.solutionTrials.isNotEmpty()) {
                        Text(stringResource(Res.string.hall_monitor_trial_history), style = AzphaltType.eyebrow, color = Azphalt.currentGround.onPage)
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
        Text(stringResource(Res.string.hall_monitor_test_solution), style = AzphaltType.hero, color = Azphalt.currentGround.onPage)
        AzphaltRecord(
            seed = presentation.run.id.value,
            eyebrow = stringResource(Res.string.hall_monitor_isolated_counterfactual),
            title = presentation.definition.name,
            body = buildString {
                append(presentation.run.objective)
                append(stringResource(Res.string.hall_monitor_the_paused_source_run_is_unchanged))
            },
            endCap = presentation.run.status.name,
            well = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    AzphaltNote(
                        seed = "trial-progress-${presentation.run.id.value}",
                        label = stringResource(Res.string.hall_monitor_progress),
                        value = stringResource(Res.string.hall_monitor_tasks_complete_artifacts, completed, taskRuns.size, artifacts.size),
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
                                                AzphaltPill(stringResource(Res.string.common_approve), "trial-approve-${taskRun.id.value}", onClick = {
                                                    onApproveTask(taskRun.taskDefinitionId.value)
                                                })
                                                if (taskRun.assignedProviderId != null) {
                                                    AzphaltPill(stringResource(Res.string.common_reject), "trial-reject-${taskRun.id.value}", onClick = {
                                                        onRejectPlan(taskRun.taskDefinitionId.value)
                                                    })
                                                }
                                            }
                                        }
                                        if (taskRun.status == TaskRunStatus.Escalated) {
                                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                AzphaltPill(stringResource(Res.string.common_retry), "trial-retry-${taskRun.id.value}", onClick = {
                                                    onResolveEscalation(taskRun.taskDefinitionId.value, true)
                                                })
                                                AzphaltPill(stringResource(Res.string.hall_monitor_stop), "trial-stop-${taskRun.id.value}", onClick = {
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
