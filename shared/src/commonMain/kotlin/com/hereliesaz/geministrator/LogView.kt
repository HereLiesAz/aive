package com.hereliesaz.geministrator

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hereliesaz.geministrator.domain.TaskDefinitionId
import com.hereliesaz.geministrator.events.WorkflowEvent
import com.hereliesaz.geministrator.log.LogLine
import com.hereliesaz.geministrator.log.LogSource
import com.hereliesaz.geministrator.log.agentActivityLines
import com.hereliesaz.geministrator.log.allActivityLines
import com.hereliesaz.geministrator.log.linesForRole
import com.hereliesaz.geministrator.log.rolesIn
import com.hereliesaz.geministrator.log.formatLogTime
import com.hereliesaz.geministrator.log.runEventLines
import com.hereliesaz.geministrator.resources.Res
import com.hereliesaz.geministrator.resources.log_empty_activity
import com.hereliesaz.geministrator.resources.log_empty_agents
import com.hereliesaz.geministrator.resources.log_empty_app
import com.hereliesaz.geministrator.resources.log_empty_memory
import com.hereliesaz.geministrator.resources.log_empty_run
import com.hereliesaz.geministrator.resources.log_note_activity
import com.hereliesaz.geministrator.resources.log_note_agents
import com.hereliesaz.geministrator.resources.log_roles_all
import com.hereliesaz.geministrator.resources.log_note_app
import com.hereliesaz.geministrator.resources.log_note_memory
import com.hereliesaz.geministrator.resources.log_note_run
import com.hereliesaz.geministrator.resources.log_source_activity
import com.hereliesaz.geministrator.resources.log_source_agents
import com.hereliesaz.geministrator.resources.log_source_app
import com.hereliesaz.geministrator.resources.log_source_memory
import com.hereliesaz.geministrator.resources.log_source_run
import com.hereliesaz.geministrator.workflow.SessionActivityKey
import com.hereliesaz.geministrator.workflow.SessionActivityLine
import kotlinx.coroutines.flow.StateFlow
import org.jetbrains.compose.resources.stringResource

/**
 * The logs the Log tab reads besides the run's own events: what the agents did, the app's own
 * diagnostic log, and the memory layer's. [memory] is null where the app has no memory layer.
 */
internal class RunLogSources(
    val agents: StateFlow<Map<SessionActivityKey, List<SessionActivityLine>>>,
    /** Whose session a feed is, by name; null for a session that belongs to another run. */
    val agentScope: (SessionActivityKey) -> String?,
    val app: StateFlow<List<LogLine>>,
    val memory: StateFlow<List<LogLine>>?,
    /** The role doing a task, for the role filter; null when none is. */
    val roleOfTask: (TaskDefinitionId) -> String? = { null },
    /** The role whose session a feed is; null when none is. */
    val roleOfSession: (SessionActivityKey) -> String? = { null },
)

private const val LOG_SOURCE_KEY = "overview.timeline.log.source"
private const val ALL_ROLES = "\u0000all-roles"

/**
 * The Log tab (docs/Keepers-Room.md): one log at a time, picked from a row of pills, oldest line
 * first, the newest at the foot; the list follows the foot while you are there and holds still when
 * you have scrolled up. Text is selectable and shown plain, never rendered as markup.
 */
@Composable
internal fun RunLogView(
    events: List<WorkflowEvent>,
    taskName: (TaskDefinitionId) -> String,
    sources: RunLogSources,
    modifier: Modifier = Modifier,
    /** Opens the creature a run-event row is about; rows with a creature are links when set. */
    onTaskSelected: ((String) -> Unit)? = null,
) {
    var picked by rememberDurableStringState(LOG_SOURCE_KEY, LogSource.Run.name)
    val available = remember(sources.memory) {
        LogSource.entries.filter { it != LogSource.Memory || sources.memory != null }
    }
    val source = LogSource.entries.firstOrNull { it.name == picked }?.takeIf { it in available } ?: LogSource.Run
    val agents by sources.agents.collectAsState()
    val app by sources.app.collectAsState()
    val memory = sources.memory?.collectAsState()?.value.orEmpty()
    val runLines = remember(events, sources.roleOfTask) { runEventLines(events, taskName, roleOf = sources.roleOfTask) }
    val agentLines = remember(agents, sources.agentScope, sources.roleOfSession) {
        agentActivityLines(agents, sources.agentScope, sources.roleOfSession)
    }
    val sourceLines = when (source) {
        LogSource.Activity -> remember(runLines, agentLines) { allActivityLines(runLines, agentLines) }
        LogSource.Run -> runLines
        LogSource.Agents -> agentLines
        LogSource.App -> app
        LogSource.Memory -> memory
    }
    // The role filter applies to what is about a run's steps; the app and memory logs are not.
    var role by remember { mutableStateOf<String?>(null) }
    val roles = remember(sourceLines) { rolesIn(sourceLines) }
    val roleShown = role?.takeIf { it in roles }
    val lines = remember(sourceLines, roleShown) { linesForRole(sourceLines, roleShown) }
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        AzphaltTabs(
            tabs = available.map { AzphaltTabItem(it.name, logSourceLabel(it)) },
            selectedKey = source.name,
            onSelected = { picked = it },
            seedPrefix = "log-source",
            keepSelectedInView = true,
            wrap = true,
        )
        Text(logSourceNote(source), style = AzphaltType.endCap, color = azphaltOnSurface())
        if (roles.isNotEmpty()) {
            AzphaltTabs(
                tabs = listOf(AzphaltTabItem(ALL_ROLES, stringResource(Res.string.log_roles_all))) +
                    roles.map { AzphaltTabItem(it, it) },
                selectedKey = roleShown ?: ALL_ROLES,
                onSelected = { role = it.takeIf { key -> key != ALL_ROLES } },
                seedPrefix = "log-role",
                keepSelectedInView = true,
            )
        }
        if (lines.isEmpty()) {
            Text(logSourceEmpty(source), style = AzphaltType.body, color = azphaltOnSurface())
            return@Column
        }
        val listState = rememberLazyListState()
        // A line keeps its identity when older ones are evicted, so the reader's place holds while the journal turns over.
        val keys = remember(lines) { stableLogKeys(lines) }
        // Follow the foot only when the reader is already at it, and a new log opens at its foot. The
        // trigger is the newest line, not the count: a full journal evicts as it grows, so its size holds still.
        val newest = keys.last()
        var previousNewest by remember(source) { mutableStateOf<String?>(null) }
        LaunchedEffect(source, newest) {
            val atFoot = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.key == previousNewest
            if (previousNewest == null || atFoot) listState.scrollToItem(lines.lastIndex)
            previousNewest = newest
        }
        SelectionContainer(Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(count = lines.size, key = { keys[it] }) { index -> LogRow(lines[index], onTaskSelected) }
            }
        }
    }
}

/** One identity per line: its time, scope and text, with a count to tell identical lines apart. */
internal fun stableLogKeys(lines: List<LogLine>): List<String> {
    val seen = HashMap<String, Int>()
    return lines.map { line ->
        val base = "${line.atEpochMillis}|${line.scope}|${line.text.hashCode()}"
        val n = seen.getOrElse(base) { 0 }
        seen[base] = n + 1
        "$base|$n"
    }
}

@Composable
private fun LogRow(line: LogLine, onTaskSelected: ((String) -> Unit)?) {
    val taskId = line.taskId
    Column(
        Modifier.fillMaxWidth().then(
            if (taskId != null && onTaskSelected != null) {
                Modifier.clickable(role = androidx.compose.ui.semantics.Role.Button) { onTaskSelected(taskId) }
            } else {
                Modifier
            },
        ),
    ) {
        Text(
            text = formatLogTime(line.atEpochMillis) + if (line.scope.isEmpty()) "" else "  ·  ${line.scope}" +
                (line.role?.let { "  ·  $it" } ?: ""),
            style = AzphaltType.endCap,
            color = azphaltOnSurface(),
        )
        Text(
            text = line.text,
            style = AzphaltType.body.copy(fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 18.sp),
            color = azphaltOnSurface(),
        )
    }
}

@Composable
private fun logSourceLabel(source: LogSource): String = stringResource(
    when (source) {
        LogSource.Activity -> Res.string.log_source_activity
        LogSource.Run -> Res.string.log_source_run
        LogSource.Agents -> Res.string.log_source_agents
        LogSource.App -> Res.string.log_source_app
        LogSource.Memory -> Res.string.log_source_memory
    },
)

@Composable
private fun logSourceNote(source: LogSource): String = stringResource(
    when (source) {
        LogSource.Activity -> Res.string.log_note_activity
        LogSource.Run -> Res.string.log_note_run
        LogSource.Agents -> Res.string.log_note_agents
        LogSource.App -> Res.string.log_note_app
        LogSource.Memory -> Res.string.log_note_memory
    },
)

@Composable
private fun logSourceEmpty(source: LogSource): String = stringResource(
    when (source) {
        LogSource.Activity -> Res.string.log_empty_activity
        LogSource.Run -> Res.string.log_empty_run
        LogSource.Agents -> Res.string.log_empty_agents
        LogSource.App -> Res.string.log_empty_app
        LogSource.Memory -> Res.string.log_empty_memory
    },
)
