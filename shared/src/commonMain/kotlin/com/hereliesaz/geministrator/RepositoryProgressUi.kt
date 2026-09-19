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
    val branch = reportedBranch ?: repository.defaultBranch ?: "Default branch not specified"
    val baseCommit = latestChange?.metadata?.get("baseCommitId")?.take(12)
    val suggestedCommit = latestChange?.metadata?.get("suggestedCommitMessage")
    val headCommit = latestRepositoryOperation?.metadata?.get("headCommit")?.take(12)
    val dirtyFileCount = latestRepositoryOperation?.metadata?.get("dirtyFileCount")?.toIntOrNull()
    val latestRepositoryOperationLabel = latestRepositoryOperation?.metadata?.get("operation")
    val repositoryUrl = latestRepositoryOperation?.metadata?.get("repositoryUrl")
        ?: repository.remoteBrowserUrl()
    val reviewUrl = latestReview?.uri
    val reviewLabel = if (repository.source == RepositorySource.GitLab) "Merge request" else "Pull request"
    val reviewReadyLabel = if (repository.source == RepositorySource.GitLab) "MR ready" else "PR ready"

    AzphaltRecord(
        seed = "repository-progress-${repository.source}-${repository.displayName()}",
        eyebrow = "${repository.source.displayName()} repository",
        title = repository.displayName(),
        body = buildString {
            append(repository.locationLabel())
            append("\nBranch: ")
            append(branch)
            headCommit?.let {
                append("\nHEAD: ")
                append(it)
            }
            dirtyFileCount?.let { count ->
                append("\nWorking tree: ")
                if (count == 0) {
                    append("clean")
                } else {
                    append(count)
                    append(if (count == 1) " changed file" else " changed files")
                }
            }
            latestRepositoryOperationLabel?.takeIf(String::isNotBlank)?.let {
                append("\nLast repository operation: ")
                append(it)
            }
            baseCommit?.let {
                append("\nBase commit: ")
                append(it)
            }
            suggestedCommit?.takeIf(String::isNotBlank)?.let {
                append("\nLatest change: ")
                append(it)
            }
            latestReview?.let {
                append("\n$reviewLabel: ")
                append(it.label)
            }
            latestRelease?.let {
                append("\nRelease: ")
                append(it.label)
            }
            active?.let { taskRun ->
                append("\nNow: ")
                append(
                    taskRun.progressMessage?.takeIf(String::isNotBlank)
                        ?: taskRun.assignedRoleId?.value?.replace('-', ' ')?.replaceFirstChar(Char::uppercase)
                        ?: taskRun.executor?.let { executor -> executor::class.simpleName }
                        ?: taskRun.status.name,
                )
            }
        },
        endCap = when {
            active != null -> "Working"
            latestRelease != null -> "Released"
            latestReview != null -> reviewReadyLabel
            dirtyFileCount != null && dirtyFileCount > 0 -> "Dirty"
            dirtyFileCount == 0 -> "Clean"
            latestRepositoryOperation != null -> "Updated"
            latestChange != null -> "Changes"
            else -> "Linked"
        },
        well = if (repositoryUrl != null || reviewUrl != null) {
            {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "REPOSITORY ACTIVITY",
                        style = AzphaltType.eyebrow,
                        color = Azphalt.White,
                    )
                    repositoryUrl?.let { url ->
                        AzphaltPill(
                            label = "Open repository",
                            seed = "open-repository-${repository.displayName()}",
                            onClick = { uriHandler.openUri(url) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    reviewUrl?.let { url ->
                        AzphaltPill(
                            label = "Open ${reviewLabel.lowercase()}",
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
