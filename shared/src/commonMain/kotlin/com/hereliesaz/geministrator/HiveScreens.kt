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
import com.hereliesaz.geministrator.domain.TaskDefinitionId
import com.hereliesaz.geministrator.domain.WorkflowDefinition
import com.hereliesaz.geministrator.domain.WorkflowDefinitionId
import com.hereliesaz.geministrator.events.WorkflowEvent
import com.hereliesaz.geministrator.resources.Res
import com.hereliesaz.geministrator.resources.hive_gone
import com.hereliesaz.geministrator.resources.hive_launch
import com.hereliesaz.geministrator.resources.hive_launch_failed
import com.hereliesaz.geministrator.resources.hive_new_name
import com.hereliesaz.geministrator.resources.hive_objective
import com.hereliesaz.geministrator.resources.hive_repository
import com.hereliesaz.geministrator.resources.hive_timeline
import com.hereliesaz.geministrator.resources.hive_timeline_empty
import com.hereliesaz.geministrator.resources.timeline_view_events
import com.hereliesaz.geministrator.resources.timeline_view_waterfall
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
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
            tone = PillTone.Primary,
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

/**
 * A comb's timeline (mockup H3): the run as a waterfall of its creatures' attempts
 * ([runWaterfall], [RunWaterfallChart]), or its events in order, what the Runs tab used to list.
 * The choice is remembered. While the run lives, unfinished bars grow to now, once a second;
 * with reduced motion they move only when new events arrive.
 */
@OptIn(ExperimentalTime::class)
@Composable
internal fun RunTimeline(
    runKey: String,
    onLoadTimeline: suspend () -> List<WorkflowEvent>,
    modifier: Modifier = Modifier,
    taskName: (TaskDefinitionId) -> String = { it.value },
    selectedTaskId: String? = null,
    onTaskSelected: (String) -> Unit = {},
) {
    var events by remember(runKey) { mutableStateOf<List<WorkflowEvent>?>(null) }
    LaunchedEffect(runKey) { events = onLoadTimeline() }
    var view by rememberDurableStringState("overview.timeline.view", TIMELINE_WATERFALL)
    Column(modifier = modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(Res.string.hive_timeline), style = AzphaltType.eyebrow, color = azphaltOnSurface())
        val list = events ?: return@Column
        if (list.isEmpty()) {
            Text(stringResource(Res.string.hive_timeline_empty), style = AzphaltType.body, color = azphaltOnSurface())
            return@Column
        }
        AzphaltTabs(
            tabs = listOf(
                AzphaltTabItem(TIMELINE_WATERFALL, stringResource(Res.string.timeline_view_waterfall)),
                AzphaltTabItem(TIMELINE_EVENTS, stringResource(Res.string.timeline_view_events)),
            ),
            selectedKey = view,
            onSelected = { view = it },
            seedPrefix = "timeline-view",
        )
        if (view != TIMELINE_EVENTS) {
            var now by remember(runKey) { mutableStateOf(Clock.System.now().toEpochMilliseconds()) }
            val waterfall = remember(list, now) { runWaterfall(list, now) }
            val reduceMotion = AzphaltMotion.reduceMotion
            LaunchedEffect(runKey, waterfall.live, reduceMotion) {
                while (waterfall.live && !reduceMotion) {
                    delay(1_000)
                    now = Clock.System.now().toEpochMilliseconds()
                }
            }
            RunWaterfallChart(waterfall, taskName, selectedTaskId, onTaskSelected)
            return@Column
        }
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

private const val TIMELINE_WATERFALL = "waterfall"
private const val TIMELINE_EVENTS = "events"

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
    /** The hive was deleted from its page: leave it for the hives list. */
    onDeleted: () -> Unit = onSurface,
) {
    val host = LocalWorkflowLibraryHost.current
    // The hive as opened: the field's tide starts from it, so saving an edit never resets undo.
    var opened by remember(definitionId) { mutableStateOf<WorkflowDefinition?>(null) }
    // The hive as last saved, edits and name included: what the launch bar runs.
    var latest by remember(definitionId) { mutableStateOf<WorkflowDefinition?>(null) }
    var missing by remember(definitionId) { mutableStateOf(false) }
    LaunchedEffect(definitionId) {
        opened = runCatching { onLoadDefinitions() }.getOrDefault(emptyList()).firstOrNull { it.id.value == definitionId }
        latest = opened
        missing = opened == null
    }
    val start = opened ?: run {
        // A hive deleted since it was last open: say so; the breadcrumb leads back out.
        if (missing) Text(stringResource(Res.string.hive_gone), style = AzphaltType.body, color = azphaltOnSurface(), modifier = modifier)
        return
    }
    val current = latest ?: start
    // Opened empty, it is a new hive (mockup H6): in Build, named and filled from under the field.
    val fresh = remember(start.id) { opensAsNewHive(start) }
    val roles = remember(host) { mutableStateOf<Collection<com.hereliesaz.geministrator.domain.RoleDefinition>>(emptyList()) }
    LaunchedEffect(host) { roles.value = host?.installedRoles().orEmpty() }
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // The breadcrumb names the hive; a new one names itself in its own head as well.
        GeministratorWorkflowTerrarium(
            definition = start,
            liveRun = remember(start.id) { restingWorkflowRun(start) },
            roles = roles.value,
            selectedTaskId = selectedTaskId,
            onTaskSelected = onTaskSelected,
            // Every edit keeps the name the keeper gave, so undo never takes the name back.
            onStageDefinition = host?.let { h ->
                { candidate -> h.saveAuthored(candidate.copy(name = (latest ?: start).name)).also { latest = it } }
            },
            onSurface = onSurface,
            hiveDeletion = host?.let { h ->
                HiveDeletionActions(
                    check = { h.hiveDeleteVerdict(start.id) },
                    delete = { h.deleteHive(start.id).also { if (it is HiveDeleteVerdict.Allowed) onDeleted() } },
                )
            },
            startInBuild = fresh,
            heading = if (fresh) {
                { NewHiveCrumb(name = current.name, onSurface = onSurface) }
            } else {
                null
            },
            beneathField = if (fresh) {
                { staged, edit ->
                    NewHiveBench(
                        definition = staged,
                        roles = roles.value,
                        onEdit = edit,
                        onRename = { name ->
                            val h = host ?: error(getString(Res.string.hive_gone))
                            latest = h.saveAuthored(renamedHive(latest ?: staged, name))
                        },
                        drafter = host?.swarmDrafter,
                    )
                }
            } else {
                // A hive with creatures can be redrafted from a prompt; it asks before replacing them.
                { staged, edit -> SwarmDraftBox(staged, host?.swarmDrafter, edit, redraft = true) }
            },
        )
        // An empty hive has nothing to launch yet; the launch bar comes with its first creature.
        if (!fresh || current.tasks.isNotEmpty()) HiveLaunchBar(current)
        // A hive with creatures can be written down as a hive card (HiveCardPanels.kt).
        if (current.tasks.isNotEmpty()) {
            var sharing by remember(current.id) { mutableStateOf(false) }
            AzphaltPill(
                label = hiveCardShareLabel(),
                seed = "hive-card-share",
                selected = sharing,
                onClick = { sharing = !sharing },
            )
            if (sharing) HiveCardSharePanel(current)
        }
    }
}

/** New hive (mockup H6): an empty workflow, saved, ready to open in Build. */
internal suspend fun createEmptyHive(host: WorkflowLibraryHost, existing: Collection<WorkflowDefinition>): WorkflowDefinition {
    val taken = existing.mapTo(mutableSetOf()) { it.id.value }
    val id = generateSequence(1) { it + 1 }.map { "hive-$it" }.first { it !in taken }
    return host.saveAuthored(WorkflowDefinition(id = WorkflowDefinitionId(id), name = getString(Res.string.hive_new_name), tasks = emptyList()))
}
