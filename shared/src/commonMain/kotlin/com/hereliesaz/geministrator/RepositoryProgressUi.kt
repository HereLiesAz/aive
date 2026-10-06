package com.hereliesaz.geministrator

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import com.hereliesaz.geministrator.domain.ArtifactKind
import com.hereliesaz.geministrator.domain.RepositoryRef
import com.hereliesaz.geministrator.domain.RepositorySource
import com.hereliesaz.geministrator.domain.TaskExecutor
import com.hereliesaz.geministrator.domain.WorkflowRun
import com.hereliesaz.geministrator.domain.displayName
import com.hereliesaz.geministrator.domain.isTerminal
import com.hereliesaz.geministrator.domain.locationLabel
import com.hereliesaz.geministrator.domain.remoteBrowserUrl
import com.hereliesaz.geministrator.resources.Res
import com.hereliesaz.geministrator.resources.repository_progress
import com.hereliesaz.geministrator.resources.repository_progress_repository
import com.hereliesaz.geministrator.resources.repository_progress_base_commit
import com.hereliesaz.geministrator.resources.repository_progress_branch
import com.hereliesaz.geministrator.resources.repository_progress_changed_file
import com.hereliesaz.geministrator.resources.repository_progress_changed_files
import com.hereliesaz.geministrator.resources.repository_progress_changes
import com.hereliesaz.geministrator.resources.repository_progress_clean
import com.hereliesaz.geministrator.resources.repository_progress_default_branch_not_specified
import com.hereliesaz.geministrator.resources.repository_progress_dirty
import com.hereliesaz.geministrator.resources.repository_progress_head
import com.hereliesaz.geministrator.resources.repository_progress_last_repository_operation
import com.hereliesaz.geministrator.resources.repository_progress_latest_change
import com.hereliesaz.geministrator.resources.repository_progress_linked
import com.hereliesaz.geministrator.resources.repository_progress_merge_request
import com.hereliesaz.geministrator.resources.repository_progress_mr_ready
import com.hereliesaz.geministrator.resources.repository_progress_now
import com.hereliesaz.geministrator.resources.repository_progress_open
import com.hereliesaz.geministrator.resources.repository_progress_open_repository
import com.hereliesaz.geministrator.resources.repository_progress_pr_ready
import com.hereliesaz.geministrator.resources.repository_progress_pull_request
import com.hereliesaz.geministrator.resources.repository_progress_release
import com.hereliesaz.geministrator.resources.repository_progress_released
import com.hereliesaz.geministrator.resources.repository_progress_repository_activity
import com.hereliesaz.geministrator.resources.repository_progress_updated
import com.hereliesaz.geministrator.resources.repository_progress_working
import com.hereliesaz.geministrator.resources.repository_progress_working_tree
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun RepositoryProgressRecord(
    repository: RepositoryRef,
    run: WorkflowRun,
    modifier: Modifier = Modifier,
) {
    val uriHandler = LocalUriHandler.current
    val artifacts = run.taskRuns.values
        .flatMap { it.artifacts }
        .sortedByDescending { it.createdAtEpochMillis }
    val latestChange = artifacts.firstOrNull { it.kind == ArtifactKind.CodeChange }
    val latestReview = artifacts.firstOrNull { it.kind == ArtifactKind.PullRequest }
    val latestRelease = artifacts.firstOrNull { it.kind == ArtifactKind.Release }
    val latestRepositoryOperation = artifacts.firstOrNull { artifact ->
        artifact.metadata["repositorySource"] != null && artifact.metadata["operation"] != null
    }
    val activeStatuses = setOf(
        com.hereliesaz.geministrator.domain.TaskRunStatus.Planning,
        com.hereliesaz.geministrator.domain.TaskRunStatus.Running,
        com.hereliesaz.geministrator.domain.TaskRunStatus.Verifying,
    )
    val activeRepositoryTask = run.taskRuns.values.firstOrNull { taskRun ->
        taskRun.status in activeStatuses &&
            (taskRun.executor is TaskExecutor.RepositoryOperation || taskRun.executor is TaskExecutor.GitHubAction)
    }
    val repositoryFacingRoles = setOf(
        "implementation-engineer",
        "crash-test-dummy",
        "qa-engineer",
        "code-reviewer",
        "release-engineer",
    )
    val activeCodeTask = run.taskRuns.values.firstOrNull { taskRun ->
        taskRun.status in activeStatuses && taskRun.assignedRoleId?.value in repositoryFacingRoles
    }
    val active = activeRepositoryTask ?: activeCodeTask
    val reportedBranch = latestRepositoryOperation?.metadata?.get("branch")
    val branch = reportedBranch ?: repository.defaultBranch ?: stringResource(Res.string.repository_progress_default_branch_not_specified)
    val baseCommit = latestChange?.metadata?.get("baseCommitId")?.take(12)
    val suggestedCommit = latestChange?.metadata?.get("suggestedCommitMessage")
    val headCommit = latestRepositoryOperation?.metadata?.get("headCommit")?.take(12)
    val dirtyFileCount = latestRepositoryOperation?.metadata?.get("dirtyFileCount")?.toIntOrNull()
    val latestRepositoryOperationLabel = latestRepositoryOperation?.metadata?.get("operation")
    val repositoryUrl = latestRepositoryOperation?.metadata?.get("repositoryUrl")
        ?: repository.remoteBrowserUrl()
    val reviewUrl = latestReview?.uri
    val reviewLabel = if (repository.source == RepositorySource.GitLab) stringResource(Res.string.repository_progress_merge_request) else stringResource(Res.string.repository_progress_pull_request)
    val reviewReadyLabel = if (repository.source == RepositorySource.GitLab) stringResource(Res.string.repository_progress_mr_ready) else stringResource(Res.string.repository_progress_pr_ready)

    AzphaltRecord(
        seed = "repository-progress-${repository.source}-${repository.displayName()}",
        eyebrow = stringResource(Res.string.repository_progress_repository, repository.source.displayName()),
        title = repository.displayName(),
        body = buildString {
            append(repository.locationLabel())
            append(stringResource(Res.string.repository_progress_branch))
            append(branch)
            headCommit?.let {
                append(stringResource(Res.string.repository_progress_head))
                append(it)
            }
            dirtyFileCount?.let { count ->
                append(stringResource(Res.string.repository_progress_working_tree))
                if (count == 0) {
                    append("clean")
                } else {
                    append(count)
                    append(if (count == 1) stringResource(Res.string.repository_progress_changed_file) else stringResource(Res.string.repository_progress_changed_files))
                }
            }
            latestRepositoryOperationLabel?.takeIf(String::isNotBlank)?.let {
                append(stringResource(Res.string.repository_progress_last_repository_operation))
                append(it)
            }
            baseCommit?.let {
                append(stringResource(Res.string.repository_progress_base_commit))
                append(it)
            }
            suggestedCommit?.takeIf(String::isNotBlank)?.let {
                append(stringResource(Res.string.repository_progress_latest_change))
                append(it)
            }
            latestReview?.let {
                append(stringResource(Res.string.repository_progress, reviewLabel))
                append(it.label)
            }
            latestRelease?.let {
                append(stringResource(Res.string.repository_progress_release))
                append(it.label)
            }
            active?.let { taskRun ->
                append(stringResource(Res.string.repository_progress_now))
                append(
                    taskRun.progressMessage?.takeIf(String::isNotBlank)
                        ?: taskRun.assignedRoleId?.value?.replace('-', ' ')?.replaceFirstChar(Char::uppercase)
                        ?: taskRun.executor?.let { executor -> executor::class.simpleName }
                        ?: taskRun.status.name,
                )
            }
        },
        endCap = when {
            active != null -> stringResource(Res.string.repository_progress_working)
            latestRelease != null -> stringResource(Res.string.repository_progress_released)
            latestReview != null -> reviewReadyLabel
            dirtyFileCount != null && dirtyFileCount > 0 -> stringResource(Res.string.repository_progress_dirty)
            dirtyFileCount == 0 -> stringResource(Res.string.repository_progress_clean)
            latestRepositoryOperation != null -> stringResource(Res.string.repository_progress_updated)
            latestChange != null -> stringResource(Res.string.repository_progress_changes)
            else -> stringResource(Res.string.repository_progress_linked)
        },
        well = if (repositoryUrl != null || reviewUrl != null) {
            {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        stringResource(Res.string.repository_progress_repository_activity),
                        style = AzphaltType.eyebrow,
                        color = Azphalt.White,
                    )
                    repositoryUrl?.let { url ->
                        AzphaltPill(
                            label = stringResource(Res.string.repository_progress_open_repository),
                            seed = "open-repository-${repository.displayName()}",
                            onClick = { uriHandler.openUri(url) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    reviewUrl?.let { url ->
                        AzphaltPill(
                            label = stringResource(Res.string.repository_progress_open, reviewLabel.lowercase()),
                            seed = "open-review-${latestReview?.id?.value.orEmpty()}",
                            onClick = { uriHandler.openUri(url) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        } else {
            null
        },
        modifier = modifier,
    )
}
