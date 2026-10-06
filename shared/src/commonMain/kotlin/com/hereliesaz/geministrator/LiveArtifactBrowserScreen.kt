package com.hereliesaz.geministrator

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.hereliesaz.geministrator.domain.ArtifactRef
import com.hereliesaz.geministrator.domain.TaskDefinition
import com.hereliesaz.geministrator.resources.Res
import com.hereliesaz.geministrator.resources.artifacts_artifact_id
import com.hereliesaz.geministrator.resources.artifacts_artifacts
import com.hereliesaz.geministrator.resources.artifacts_loading_runtime
import com.hereliesaz.geministrator.resources.artifacts_no_artifacts_have_been_produced_by
import com.hereliesaz.geministrator.resources.artifacts_no_artifacts_match
import com.hereliesaz.geministrator.resources.artifacts_no_live_run
import com.hereliesaz.geministrator.resources.artifacts_no_project_yet
import com.hereliesaz.geministrator.resources.artifacts_no_workflow_run_yet
import com.hereliesaz.geministrator.resources.artifacts_open
import com.hereliesaz.geministrator.resources.artifacts_persisted_workflow_artifact
import com.hereliesaz.geministrator.resources.artifacts_run_unavailable
import com.hereliesaz.geministrator.resources.artifacts_runtime_disconnected
import com.hereliesaz.geministrator.resources.artifacts_search_artifacts
import org.jetbrains.compose.resources.stringResource
import com.hereliesaz.geministrator.resources.artifacts_count_from_run
import org.jetbrains.compose.resources.pluralStringResource

private data class LiveArtifactRow(
    val task: TaskDefinition,
    val artifact: ArtifactRef,
)

@Composable
internal fun LiveArtifactBrowserScreen(
    runtimeState: ApplicationRuntimeState,
    modifier: Modifier = Modifier,
) {
    val live = (runtimeState as? ApplicationRuntimeState.Live)?.presentation
    val stateScope = live?.run?.id?.value ?: "no-run"
    var query by rememberDurableStringState("artifacts.$stateScope.query")
    var selectedArtifactIdValue by rememberDurableStringState("artifacts.$stateScope.selected")
    val selectedArtifactId = selectedArtifactIdValue.takeIf(String::isNotBlank)

    val rows = remember(live) {
        live?.definition?.tasks.orEmpty().flatMap { task ->
            live?.run?.taskRuns?.get(task.id)?.artifacts.orEmpty().map { artifact ->
                LiveArtifactRow(task, artifact)
            }
        }
    }
    val needle = query.trim().lowercase()
    val filtered = if (needle.isEmpty()) {
        rows
    } else {
        rows.filter { row ->
            listOf(
                row.task.name,
                row.task.id.value,
                row.artifact.label,
                row.artifact.kind.name,
                row.artifact.uri.orEmpty(),
                row.artifact.mediaType.orEmpty(),
            ).any { it.lowercase().contains(needle) }
        }
    }

    Column(
        modifier = modifier
            .fillMaxHeight()
            .verticalScroll(rememberScrollState())
            .padding(26.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(Res.string.artifacts_artifacts), style = AzphaltType.hero, color = Azphalt.currentGround.onPage)

        when {
            live == null -> {
                Text(
                    when (runtimeState) {
                        ApplicationRuntimeState.Loading -> stringResource(Res.string.artifacts_loading_runtime)
                        is ApplicationRuntimeState.NoProject -> stringResource(Res.string.artifacts_no_project_yet)
                        is ApplicationRuntimeState.NoRun -> stringResource(Res.string.artifacts_no_workflow_run_yet)
                        is ApplicationRuntimeState.Disconnected -> stringResource(Res.string.artifacts_runtime_disconnected, runtimeState.message)
                        is ApplicationRuntimeState.ResumeFailed -> stringResource(Res.string.artifacts_run_unavailable, runtimeState.message)
                        is ApplicationRuntimeState.Live -> stringResource(Res.string.artifacts_no_live_run)
                    },
                    style = AzphaltType.body,
                    color = Azphalt.currentGround.onPage,
                )
            }

            rows.isEmpty() -> {
                Text(
                    stringResource(Res.string.artifacts_no_artifacts_have_been_produced_by),
                    style = AzphaltType.body,
                    color = Azphalt.currentGround.onPage,
                )
            }

            else -> {
                Text(
                    pluralStringResource(Res.plurals.artifacts_count_from_run, rows.size, rows.size, live.run.id.value.takeLast(8)),
                    style = AzphaltType.body,
                    color = Azphalt.currentGround.onPage,
                )
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text(stringResource(Res.string.artifacts_search_artifacts)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                if (filtered.isEmpty()) {
                    Text(
                        stringResource(Res.string.artifacts_no_artifacts_match, query.trim()),
                        style = AzphaltType.body,
                        color = Azphalt.currentGround.onPage,
                    )
                } else {
                    filtered.forEach { row ->
                        val selected = selectedArtifactId == row.artifact.id.value
                        AzphaltRecord(
                            seed = "artifact-${row.artifact.id.value}",
                            eyebrow = "${row.task.name} · ${row.artifact.kind.name}",
                            title = row.artifact.label,
                            body = row.artifact.uri
                                ?: row.artifact.mediaType
                                ?: stringResource(Res.string.artifacts_persisted_workflow_artifact),
                            endCap = if (selected) stringResource(Res.string.artifacts_open) else row.artifact.kind.name,
                            selected = selected,
                            onClick = {
                                selectedArtifactIdValue = if (selected) "" else row.artifact.id.value
                            },
                            well = if (selected) {
                                {
                                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Text(
                                            stringResource(Res.string.artifacts_artifact_id, row.artifact.id.value),
                                            style = AzphaltType.eyebrow,
                                            color = Azphalt.currentGround.onPage,
                                        )
                                        row.artifact.uri?.let { uri ->
                                            Text(uri, style = AzphaltType.body, color = Azphalt.currentGround.onPage)
                                        }
                                        row.artifact.textContent?.takeIf(String::isNotBlank)?.let { text ->
                                            Text(text, style = AzphaltType.body, color = Azphalt.currentGround.onPage)
                                        }
                                    }
                                }
                            } else null,
                        )
                    }
                }
            }
        }
    }
}
