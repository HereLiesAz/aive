package com.hereliesaz.geministrator

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import com.hereliesaz.geministrator.domain.ArtifactKind
import com.hereliesaz.geministrator.domain.RepositoryRef
import com.hereliesaz.geministrator.domain.TaskExecutor
import com.hereliesaz.geministrator.domain.TaskRunStatus
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
    val latestPullRequest = artifacts.firstOrNull { it.kind == ArtifactKind.PullRequest }
    val latestRelease = artifacts.firstOrNull { it.kind == ArtifactKind.Release }
    val activeRepositoryTask = run.taskRuns.values.firstOrNull { taskRun ->
        !taskRun.status.isTerminal() &&
            (taskRun.executor is TaskExecutor.RepositoryOperation || taskRun.executor is TaskExecutor.GitHubAction)
    }
    val activeCodeTask = run.taskRuns.values.firstOrNull { taskRun ->
        !taskRun.status.isTerminal() && taskRun.progressMessage?.contains("git", ignoreCase = true) == true
    }
    val active = activeRepositoryTask ?: activeCodeTask
    val branch = repository.defaultBranch ?: "Default branch not specified"
    val baseCommit = latestChange?.metadata?.get("baseCommitId")?.take(12)
    val suggestedCommit = latestChange?.metadata?.get("suggestedCommitMessage")
    val repositoryUrl = repository.remoteBrowserUrl()
    val pullRequestUrl = latestPullRequest?.uri

    AzphaltRecord(
        seed = "repository-progress-${repository.source}-${repository.displayName()}",
        eyebrow = "${repository.source.displayName()} repository",
        title = repository.displayName(),
        body = buildString {
            append(repository.locationLabel())
            append("\nBranch: ")
            append(branch)
            baseCommit?.let {
                append("\nBase commit: ")
                append(it)
            }
            suggestedCommit?.takeIf(String::isNotBlank)?.let {
                append("\nLatest change: ")
                append(it)
            }
            latestPullRequest?.let {
                append("\nPull request: ")
                append(it.label)
            }
            latestRelease?.let {
                append("\nRelease: ")
                append(it.label)
            }
            active?.progressMessage?.takeIf(String::isNotBlank)?.let {
                append("\nNow: ")
                append(it)
            }
        },
        endCap = when {
            active != null -> "Working"
            latestRelease != null -> "Released"
            latestPullRequest != null -> "PR ready"
            latestChange != null -> "Changes"
            else -> "Linked"
        },
        well = if (repositoryUrl != null || pullRequestUrl != null) {
            {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "REPOSITORY ACTIVITY",
                        style = AzphaltType.eyebrow,
                        color = Azphalt.currentGround.onPage,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        repositoryUrl?.let { url ->
                            AzphaltPill(
                                label = "Open repository",
                                seed = "open-repository-${repository.displayName()}",
                                onClick = { uriHandler.openUri(url) },
                            )
                        }
                        pullRequestUrl?.let { url ->
                            AzphaltPill(
                                label = "Open pull request",
                                seed = "open-pr-${latestPullRequest?.id?.value.orEmpty()}",
                                onClick = { uriHandler.openUri(url) },
                            )
                        }
                    }
                }
            }
        } else {
            null
        },
        modifier = modifier,
    )
}
