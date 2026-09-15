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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.hereliesaz.geministrator.domain.TaskExecutor
import com.hereliesaz.geministrator.domain.WorkflowDefinition
import com.hereliesaz.geministrator.domain.displayName

@Composable
internal fun LiveWorkflowLibraryScreen(
    runtimeState: ApplicationRuntimeState,
    onLoadDefinitions: suspend () -> List<WorkflowDefinition>,
    modifier: Modifier = Modifier,
) {
    var definitions by remember { mutableStateOf<List<WorkflowDefinition>?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    var selectedDefinitionId by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(runtimeState) {
        runCatching { onLoadDefinitions() }
            .onSuccess {
                definitions = it
                loadError = null
            }
            .onFailure {
                definitions = emptyList()
                loadError = it.message ?: "Workflow definitions could not be loaded."
            }
    }

    val activeId = (runtimeState as? ApplicationRuntimeState.Live)
        ?.presentation
        ?.definition
        ?.id
        ?.value
    val needle = query.trim().lowercase()
    val visible = definitions.orEmpty().filter { definition ->
        needle.isEmpty() || listOf(
            definition.name,
            definition.id.value,
            definition.description.orEmpty(),
        ).any { it.lowercase().contains(needle) }
    }

    Column(
        modifier = modifier
            .fillMaxHeight()
            .verticalScroll(rememberScrollState())
            .padding(26.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("WORKFLOWS", style = AzphaltType.hero, color = Azphalt.currentGround.onPage)

        loadError?.let {
            Text("Definition store error: $it", style = AzphaltType.body, color = Azphalt.currentGround.onPage)
        }

        when (val loaded = definitions) {
            null -> Text("Loading definitions…", style = AzphaltType.body, color = Azphalt.currentGround.onPage)
            else -> {
                if (loaded.isEmpty()) {
                    Text(
                        "No persisted workflow definitions yet. Launching a project objective will materialize one.",
                        style = AzphaltType.body,
                        color = Azphalt.currentGround.onPage,
                    )
                } else {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        label = { Text("Search workflow definitions") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (visible.isEmpty()) {
                        Text(
                            "No definitions match \"${query.trim()}\".",
                            style = AzphaltType.body,
                            color = Azphalt.currentGround.onPage,
                        )
                    } else {
                        visible.forEach { definition ->
                            val selected = selectedDefinitionId == definition.id.value
                            val isActive = definition.id.value == activeId
                            AzphaltRecord(
                                seed = "workflow-definition-${definition.id.value}",
                                eyebrow = if (isActive) "Active definition" else "Persisted definition",
                                title = definition.name,
                                body = definition.description
                                    ?: "${definition.tasks.size} task${if (definition.tasks.size == 1) "" else "s"} · ${definition.integrationPolicy.name}",
                                endCap = if (isActive) "Active" else "${definition.tasks.size} tasks",
                                selected = selected,
                                onClick = {
                                    selectedDefinitionId = if (selected) null else definition.id.value
                                },
                                well = if (selected) {
                                    {
                                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                            Text(
                                                "Integration · ${definition.integrationPolicy.name} · Concurrency ${definition.concurrencyPolicy.maxConcurrentTasks}",
                                                style = AzphaltType.body,
                                                color = Azphalt.currentGround.onPage,
                                            )
                                            definition.tasks.forEach { task ->
                                                val executor = task.executor ?: task.roleId?.let(TaskExecutor::RoleAgent)
                                                AzphaltNote(
                                                    seed = "workflow-task-${definition.id.value}-${task.id.value}",
                                                    label = task.name,
                                                    value = buildString {
                                                        append(executor?.displayName() ?: "Unassigned")
                                                        if (task.dependsOn.isNotEmpty()) {
                                                            append(" · after ")
                                                            append(task.dependsOn.joinToString(", ") { it.value })
                                                        }
                                                    },
                                                )
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
}
