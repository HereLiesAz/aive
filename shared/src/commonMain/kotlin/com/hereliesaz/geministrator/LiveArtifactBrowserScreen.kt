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
        Text("ARTIFACTS", style = AzphaltType.hero, color = Azphalt.currentGround.onPage)

        when {
            live == null -> {
                Text(
                    when (runtimeState) {
                        ApplicationRuntimeState.Loading -> "Loading runtime…"
                        ApplicationRuntimeState.NoProject -> "No project yet."
                        is ApplicationRuntimeState.NoRun -> "No workflow run yet."
                        is ApplicationRuntimeState.Disconnected -> "Runtime disconnected: ${runtimeState.message}"
                        is ApplicationRuntimeState.ResumeFailed -> "Run unavailable: ${runtimeState.message}"
                        is ApplicationRuntimeState.Live -> "No live run."
                    },
                    style = AzphaltType.body,
                    color = Azphalt.currentGround.onPage,
                )
            }

            rows.isEmpty() -> {
                Text(
                    "No artifacts have been produced by this run.",
                    style = AzphaltType.body,
                    color = Azphalt.currentGround.onPage,
                )
            }

            else -> {
                Text(
                    "${rows.size} artifact${if (rows.size == 1) "" else "s"} from run ${live.run.id.value.takeLast(8)}.",
                    style = AzphaltType.body,
                    color = Azphalt.currentGround.onPage,
                )
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("Search artifacts") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                if (filtered.isEmpty()) {
                    Text(
                        "No artifacts match \"${query.trim()}\".",
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
                                ?: "Persisted workflow artifact",
                            endCap = if (selected) "Open" else row.artifact.kind.name,
                            selected = selected,
                            onClick = {
                                selectedArtifactIdValue = if (selected) "" else row.artifact.id.value
                            },
                            well = if (selected) {
                                {
                                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Text(
                                            "Artifact ID · ${row.artifact.id.value}",
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
