package com.hereliesaz.geministrator

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.hereliesaz.geministrator.domain.WorkflowDefinition
import com.hereliesaz.geministrator.domain.WorkflowDefinitionId
import com.hereliesaz.geministrator.events.WorkflowEvent
import com.hereliesaz.geministrator.resources.Res
import com.hereliesaz.geministrator.resources.hive_launch
import com.hereliesaz.geministrator.resources.hive_launch_failed
import com.hereliesaz.geministrator.resources.hive_new_name
import com.hereliesaz.geministrator.resources.hive_objective
import com.hereliesaz.geministrator.resources.hive_repository
import com.hereliesaz.geministrator.resources.hive_timeline
import com.hereliesaz.geministrator.resources.hive_timeline_empty
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource

/**
 * The launch bar (mockup H2): what replaces the Workflows tab's run form. An objective and Launch;
 * the hive's own workflow is what runs.
 */
@Composable
internal fun HiveLaunchBar(definition: WorkflowDefinition, modifier: Modifier = Modifier) {
    val host = LocalWorkflowLibraryHost.current ?: return
    HiveLaunchBar(definition.id.value, onLaunch = { objective, repository -> host.run(definition, objective, repository) }, modifier)
}

/** The launch bar's body: [onLaunch] gets the objective and repository, each null when blank. */
@Composable
internal fun HiveLaunchBar(
    key: String,
    onLaunch: suspend (String?, com.hereliesaz.geministrator.domain.RepositoryRef?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    var objective by remember(key) { mutableStateOf("") }
    var repository by remember(key) { mutableStateOf("") }
    var message by remember(key) { mutableStateOf<String?>(null) }
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = objective,
            onValueChange = { objective = it },
            label = { Text(stringResource(Res.string.hive_objective)) },
            singleLine = true,
            colors = azphaltTextFieldColors(),
            modifier = Modifier.fillMaxWidth(),
        )
        // Where the swarm works; blank keeps the current project's repository.
        OutlinedTextField(
            value = repository,
            onValueChange = { repository = it },
            label = { Text(stringResource(Res.string.hive_repository)) },
            singleLine = true,
            colors = azphaltTextFieldColors(),
            modifier = Modifier.fillMaxWidth(),
        )
        AzphaltPill(
            label = stringResource(Res.string.hive_launch),
            seed = "hive-launch",
            onClick = {
                scope.launch {
                    message = runCatching {
                        onLaunch(objective.trim().ifBlank { null }, hiveRepository(repository))
                    }
                        .fold({ null }, { it.message ?: getString(Res.string.hive_launch_failed) })
                    if (message == null) objective = ""
                }
            },
        )
        message?.let { Text(it, style = AzphaltType.body, color = azphaltOnSurface()) }
    }
}

/** A pasted repository: GitLab when it says so, GitHub otherwise; blank is none. */
internal fun hiveRepository(locator: String): com.hereliesaz.geministrator.domain.RepositoryRef? {
    val clean = locator.trim().ifBlank { return null }
    val source = if ("gitlab.com" in clean.lowercase()) {
        com.hereliesaz.geministrator.domain.RepositorySource.GitLab
    } else {
        com.hereliesaz.geministrator.domain.RepositorySource.GitHub
    }
    return com.hereliesaz.geministrator.domain.parseRepositoryRef(source, clean)
}

/** A comb's timeline (mockup H3): the run's events in order, what the Runs tab used to list. */
@Composable
internal fun RunTimeline(runKey: String, onLoadTimeline: suspend () -> List<WorkflowEvent>, modifier: Modifier = Modifier) {
    var events by remember(runKey) { mutableStateOf<List<WorkflowEvent>?>(null) }
    LaunchedEffect(runKey) { events = onLoadTimeline() }
    Column(modifier = modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(Res.string.hive_timeline), style = AzphaltType.eyebrow, color = azphaltOnSurface())
        val list = events ?: return@Column
        if (list.isEmpty()) Text(stringResource(Res.string.hive_timeline_empty), style = AzphaltType.body, color = azphaltOnSurface())
        list.sortedBy { it.occurredAtEpochMillis }.forEach { event ->
            AzphaltRecord(
                seed = "event-${event.occurredAtEpochMillis}-${event::class.simpleName}",
                eyebrow = workflowEventTaskId(event)?.value?.takeLast(12) ?: "workflow",
                title = workflowEventLabel(event),
                body = workflowEventDetail(event),
                endCap = null,
            )
        }
    }
}

/**
 * A hive that has never run (mockups H2, H6): its workflow at rest, editable in Build, with the
 * launch bar under it. Edits save to the workflow itself.
 */
@Composable
internal fun RestingHive(
    definitionId: String,
    onLoadDefinitions: suspend () -> List<WorkflowDefinition>,
    selectedTaskId: String?,
    onTaskSelected: (String) -> Unit,
    onSurface: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val host = LocalWorkflowLibraryHost.current
    var definition by remember(definitionId) { mutableStateOf<WorkflowDefinition?>(null) }
    LaunchedEffect(definitionId) { definition = onLoadDefinitions().firstOrNull { it.id.value == definitionId } }
    val current = definition ?: return
    val roles = remember(host) { mutableStateOf<Collection<com.hereliesaz.geministrator.domain.RoleDefinition>>(emptyList()) }
    LaunchedEffect(host) { roles.value = host?.installedRoles().orEmpty() }
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(current.name.uppercase(), style = AzphaltType.section, color = azphaltOnSurface())
        GeministratorWorkflowTerrarium(
            definition = current,
            liveRun = remember(current.id) { restingWorkflowRun(current) },
            roles = roles.value,
            selectedTaskId = selectedTaskId,
            onTaskSelected = onTaskSelected,
            onStageDefinition = host?.let { h -> { candidate -> h.saveAuthored(candidate).also { definition = it } } },
            onSurface = onSurface,
        )
        HiveLaunchBar(current)
    }
}

/** New hive (mockup H6): an empty workflow, saved, ready to open in Build. */
internal suspend fun createEmptyHive(host: WorkflowLibraryHost, existing: Collection<WorkflowDefinition>): WorkflowDefinition {
    val taken = existing.mapTo(mutableSetOf()) { it.id.value }
    val id = generateSequence(1) { it + 1 }.map { "hive-$it" }.first { it !in taken }
    return host.saveAuthored(WorkflowDefinition(id = WorkflowDefinitionId(id), name = getString(Res.string.hive_new_name), tasks = emptyList()))
}
