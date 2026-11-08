package com.hereliesaz.geministrator

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.hereliesaz.geministrator.domain.TaskDefinition
import com.hereliesaz.geministrator.memory.MemoryLayerController
import com.hereliesaz.geministrator.memory.MemoryLayerSettings
import com.hereliesaz.geministrator.memory.procedural.FormedHabit
import com.hereliesaz.geministrator.memory.procedural.FormedSequence
import com.hereliesaz.geministrator.memory.procedural.HabitInvocation
import com.hereliesaz.geministrator.memory.procedural.HabitLog
import com.hereliesaz.geministrator.memory.procedural.HabitLogEntry
import com.hereliesaz.geministrator.memory.procedural.HabitOffers
import com.hereliesaz.geministrator.memory.procedural.ProcedureDeliberationOutcome
import com.hereliesaz.geministrator.resources.Res
import com.hereliesaz.geministrator.resources.memory_habits
import com.hereliesaz.geministrator.resources.memory_habits_find
import com.hereliesaz.geministrator.resources.memory_habits_log_copied
import com.hereliesaz.geministrator.resources.memory_habits_log_copy
import com.hereliesaz.geministrator.resources.memory_habits_log_copy_failed
import com.hereliesaz.geministrator.resources.memory_habits_log_empty
import com.hereliesaz.geministrator.resources.memory_habits_log_hide
import com.hereliesaz.geministrator.resources.memory_habits_log_recent
import com.hereliesaz.geministrator.resources.memory_habits_log_show
import com.hereliesaz.geministrator.resources.memory_habits_no_match
import com.hereliesaz.geministrator.resources.memory_habits_add_failed
import com.hereliesaz.geministrator.resources.memory_habits_add_to
import com.hereliesaz.geministrator.resources.memory_habits_added
import com.hereliesaz.geministrator.resources.memory_habits_no_authored
import com.hereliesaz.geministrator.resources.memory_habits_propose_sequence
import com.hereliesaz.geministrator.resources.memory_habits_alternative
import com.hereliesaz.geministrator.resources.memory_habits_alternative_operation
import com.hereliesaz.geministrator.resources.memory_habits_close
import com.hereliesaz.geministrator.resources.memory_habits_flag
import com.hereliesaz.geministrator.resources.memory_habits_hint
import com.hereliesaz.geministrator.resources.memory_habits_learning
import com.hereliesaz.geministrator.resources.memory_habits_none
import com.hereliesaz.geministrator.resources.memory_habits_not_recorded
import com.hereliesaz.geministrator.resources.memory_habits_note
import com.hereliesaz.geministrator.resources.memory_habits_off
import com.hereliesaz.geministrator.resources.memory_habits_override
import com.hereliesaz.geministrator.resources.memory_habits_propose
import com.hereliesaz.geministrator.resources.memory_habits_proposed
import com.hereliesaz.geministrator.resources.memory_habits_refused
import com.hereliesaz.geministrator.resources.memory_habits_recorded
import com.hereliesaz.geministrator.resources.memory_habits_replace
import com.hereliesaz.geministrator.resources.memory_habits_sequence_line
import com.hereliesaz.geministrator.resources.memory_habits_sequences
import com.hereliesaz.geministrator.resources.memory_habits_stats
import com.hereliesaz.geministrator.resources.memory_habits_stands
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * The keeper's view of the habits the swarm has formed (docs/architecture/HABITS_AS_MEMORY.md):
 * the switch that lets receipts be kept, each formed habit as the facts an agent is offered, and the
 * keeper's own words on one: a flag, a decision that it stands or is replaced, or an override with
 * what to do instead. All of it is information; none of it hides or blocks a habit.
 */
@OptIn(ExperimentalTime::class)
@Composable
internal fun MemoryHabitsSection(controller: MemoryLayerController, settings: MemoryLayerSettings) {
    val scope = rememberCoroutineScope()
    val knownBanks by controller.knownBanks.collectAsState()
    val selectedBank by controller.selectedBank.collectAsState()
    val snapshot by controller.snapshot.collectAsState()
    val bank = selectedBank ?: knownBanks.firstOrNull()
    val capturing = settings.proceduralTraceCaptureEnabled

    Text(stringResource(Res.string.memory_habits).uppercase(), style = AzphaltType.eyebrow, color = azphaltOnSurface())
    Text(stringResource(Res.string.memory_habits_hint), style = AzphaltType.body, color = azphaltOnSurface())
    AzphaltPill(
        if (capturing) stringResource(Res.string.memory_habits_learning) else stringResource(Res.string.memory_habits_off),
        "memory-habits-capture",
        selected = capturing,
        onClick = { scope.launch { controller.updateSettings { it.copy(proceduralTraceCaptureEnabled = !it.proceduralTraceCaptureEnabled) } } },
    )
    if (!capturing || bank == null) return

    var refresh by remember { mutableIntStateOf(0) }
    var habits by remember { mutableStateOf<List<FormedHabit>>(emptyList()) }
    var sequences by remember { mutableStateOf<List<FormedSequence>>(emptyList()) }
    LaunchedEffect(bank, snapshot, refresh, capturing) {
        habits = runCatching { controller.formedHabits(bank) }.getOrDefault(emptyList())
        sequences = runCatching { controller.formedSequences(bank) }.getOrDefault(emptyList())
    }
    val now = Clock.System.now().toEpochMilliseconds()
    var open by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    // Finding a habit, and the receipts behind one (or behind all of them).
    var query by remember { mutableStateOf("") }
    var logFor by remember { mutableStateOf<String?>(null) }
    var showRecent by remember { mutableStateOf(false) }
    var logEntries by remember { mutableStateOf<List<HabitLogEntry>>(emptyList()) }
    LaunchedEffect(bank, snapshot, refresh, logFor, showRecent) {
        logEntries = if (logFor == null && !showRecent) {
            emptyList()
        } else {
            runCatching { controller.habitLog(bank, logFor, if (logFor == null) RECENT_LOG else HabitLog.DEFAULT_LIMIT) }
                .getOrDefault(emptyList())
        }
    }
    @Suppress("DEPRECATION")
    val clipboard = LocalClipboardManager.current
    // A proposal the keeper is looking at, and the workflows of their own it could be added to.
    var proposal by remember { mutableStateOf<List<TaskDefinition>?>(null) }
    val host = LocalWorkflowLibraryHost.current
    var authored by remember { mutableStateOf<List<WorkflowLibraryEntry>>(emptyList()) }
    LaunchedEffect(proposal, host) {
        authored = if (proposal == null || host == null) {
            emptyList()
        } else {
            runCatching { host.entries() }.getOrDefault(emptyList()).filter { it.origin == WorkflowLibraryOrigin.Authored }
        }
    }

    if (habits.isEmpty()) {
        Text(stringResource(Res.string.memory_habits_none), style = AzphaltType.body, color = azphaltOnSurface())
    } else {
        val stats = HabitOffers.stats(habits, sequences)
        Text(
            stringResource(
                Res.string.memory_habits_stats,
                stats.habits.toString(), stats.offered.toString(), stats.uses.toString(), stats.failedUses.toString(),
                stats.flagsUsed.toString(), stats.flagsHandled.toString(), stats.flagsKeeper.toString(),
                stats.openContrastsUnanswered.toString(), stats.activeOverrides.toString(),
                stats.superseded.toString(), stats.sequences.toString(),
            ),
            style = AzphaltType.body,
            color = azphaltOnSurface(),
        )
    }
    if (habits.isNotEmpty()) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text(stringResource(Res.string.memory_habits_find)) },
            modifier = Modifier.fillMaxWidth(),
        )
        AzphaltPill(
            stringResource(if (showRecent) Res.string.memory_habits_log_hide else Res.string.memory_habits_log_show) +
                " · " + stringResource(Res.string.memory_habits_log_recent),
            "memory-habits-log-recent",
            selected = showRecent,
            onClick = { showRecent = !showRecent; if (showRecent) logFor = null },
        )
        if (showRecent) HabitLogView(logEntries, clipboard, scope) { message = it }
    }
    val shownHabits = habits.filter { habit ->
        val needle = query.trim().lowercase()
        needle.isEmpty() || habit.operation.lowercase().contains(needle) ||
            habit.flags.newest.any { it.text.lowercase().contains(needle) } ||
            (habit.lastDeliberation?.text?.lowercase()?.contains(needle) == true)
    }
    if (habits.isNotEmpty() && shownHabits.isEmpty()) {
        Text(stringResource(Res.string.memory_habits_no_match), style = AzphaltType.body, color = azphaltOnSurface())
    }
    shownHabits.forEach { habit ->
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SelectionContainer {
                Text(HabitOffers.describe(habit, now), style = AzphaltType.body, color = azphaltOnSurface())
            }
            AzphaltPill(
                stringResource(if (logFor == habit.operation) Res.string.memory_habits_log_hide else Res.string.memory_habits_log_show),
                "memory-habit-log-${habit.operation}",
                selected = logFor == habit.operation,
                onClick = { logFor = if (logFor == habit.operation) null else habit.operation; showRecent = false },
            )
            if (logFor == habit.operation) HabitLogView(logEntries, clipboard, scope) { message = it }
            if (open != habit.operation) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AzphaltPill(
                        stringResource(Res.string.memory_habits_flag),
                        "memory-habit-open-${habit.operation}",
                        onClick = { open = habit.operation; message = null },
                    )
                    AzphaltPill(
                        stringResource(Res.string.memory_habits_propose),
                        "memory-habit-propose-${habit.operation}",
                        onClick = {
                            scope.launch {
                                val taskId = "habit-" + habit.operation.replace(Regex("[^A-Za-z0-9-]"), "-")
                                message = when (val result = controller.proposeHabitTask(bank, habit.operation, taskId)) {
                                    is HabitInvocation.Result.Proposed -> {
                                        proposal = listOf(result.task)
                                        getString(Res.string.memory_habits_proposed, result.task.name, result.task.objective)
                                    }
                                    is HabitInvocation.Result.Refused -> {
                                        proposal = null
                                        getString(Res.string.memory_habits_refused, result.reason)
                                    }
                                }
                            }
                        },
                    )
                }
            } else {
                HabitWordsEditor(
                    onRecord = { kind, note, alternative, alternativeOperation ->
                        scope.launch {
                            val recorded = when (kind) {
                                HabitWords.Flag -> controller.flagHabit(bank, habit.operation, note)
                                HabitWords.Stands -> controller.deliberateHabit(
                                    bank, habit.operation, ProcedureDeliberationOutcome.Stands, "keeper", note,
                                )
                                HabitWords.Replace -> controller.deliberateHabit(
                                    bank, habit.operation, ProcedureDeliberationOutcome.Superseded, "keeper", note,
                                )
                                HabitWords.Override -> controller.deliberateHabit(
                                    bank, habit.operation, ProcedureDeliberationOutcome.Overrides, "keeper", note,
                                    alternative.ifBlank { null }, alternativeOperation.ifBlank { null },
                                )
                            }
                            message = if (recorded) getString(Res.string.memory_habits_recorded) else getString(Res.string.memory_habits_not_recorded)
                            if (recorded) {
                                open = null
                                refresh++
                            }
                        }
                    },
                    onClose = { open = null },
                )
            }
        }
    }
    if (sequences.isNotEmpty()) {
        Text(stringResource(Res.string.memory_habits_sequences).uppercase(), style = AzphaltType.eyebrow, color = azphaltOnSurface())
        sequences.forEach { sequence ->
            Text(
                stringResource(
                    Res.string.memory_habits_sequence_line,
                    sequence.operations.joinToString(", then "),
                    sequence.runs.toString(),
                    sequence.cleanRuns.toString(),
                ),
                style = AzphaltType.body,
                color = azphaltOnSurface(),
            )
            AzphaltPill(
                stringResource(Res.string.memory_habits_propose_sequence),
                "memory-sequence-propose-${sequence.operations.first()}",
                onClick = {
                    message = null
                    when (val result = HabitInvocation.proposeSequence(sequence, habits, "habit")) {
                        is HabitInvocation.SequenceResult.Proposed -> {
                            proposal = result.tasks
                            scope.launch {
                                message = getString(
                                    Res.string.memory_habits_proposed,
                                    result.tasks.joinToString(", then ") { it.name },
                                    result.tasks.first().objective,
                                )
                            }
                        }
                        is HabitInvocation.SequenceResult.Refused -> {
                            proposal = null
                            scope.launch { message = getString(Res.string.memory_habits_refused, result.reason) }
                        }
                    }
                },
            )
        }
    }
    message?.let { Text(it, style = AzphaltType.body, color = azphaltOnSurface()) }
    val shown = proposal
    if (shown != null && host != null) {
        if (authored.isEmpty()) {
            Text(stringResource(Res.string.memory_habits_no_authored), style = AzphaltType.body, color = azphaltOnSurface())
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            authored.forEach { entry ->
                AzphaltPill(
                    stringResource(Res.string.memory_habits_add_to, entry.definition.name),
                    "memory-habit-add-${entry.definition.id.value}",
                    onClick = {
                        scope.launch {
                            message = try {
                                host.addProposedTasks(entry.definition.id, shown)
                                proposal = null
                                getString(Res.string.memory_habits_added, shown.size.toString(), entry.definition.name)
                            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                                throw cancelled
                            } catch (error: Exception) {
                                getString(Res.string.memory_habits_add_failed, error.message ?: error::class.simpleName.orEmpty())
                            }
                        }
                    },
                )
            }
        }
    }
}

private enum class HabitWords { Flag, Stands, Replace, Override }

@Composable
private fun HabitWordsEditor(
    onRecord: (kind: HabitWords, note: String, alternative: String, alternativeOperation: String) -> Unit,
    onClose: () -> Unit,
) {
    var note by remember { mutableStateOf("") }
    var alternative by remember { mutableStateOf("") }
    var alternativeOperation by remember { mutableStateOf("") }
    OutlinedTextField(
        value = note,
        onValueChange = { note = it },
        label = { Text(stringResource(Res.string.memory_habits_note)) },
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = alternative,
        onValueChange = { alternative = it },
        label = { Text(stringResource(Res.string.memory_habits_alternative)) },
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = alternativeOperation,
        onValueChange = { alternativeOperation = it },
        label = { Text(stringResource(Res.string.memory_habits_alternative_operation)) },
        modifier = Modifier.fillMaxWidth(),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        AzphaltPill(stringResource(Res.string.memory_habits_flag), "memory-habit-flag", onClick = { onRecord(HabitWords.Flag, note, "", "") })
        AzphaltPill(stringResource(Res.string.memory_habits_stands), "memory-habit-stands", onClick = { onRecord(HabitWords.Stands, note, "", "") })
        AzphaltPill(stringResource(Res.string.memory_habits_replace), "memory-habit-replace", onClick = { onRecord(HabitWords.Replace, note, "", "") })
        AzphaltPill(
            stringResource(Res.string.memory_habits_override),
            "memory-habit-override",
            onClick = { onRecord(HabitWords.Override, note, alternative, alternativeOperation) },
        )
        AzphaltPill(stringResource(Res.string.memory_habits_close), "memory-habit-close", onClick = onClose)
    }
}

private const val RECENT_LOG = 30

/** The receipts behind a habit as plain lines the keeper can select or copy. */
@Suppress("DEPRECATION")
@Composable
private fun HabitLogView(
    entries: List<HabitLogEntry>,
    clipboard: androidx.compose.ui.platform.ClipboardManager,
    scope: kotlinx.coroutines.CoroutineScope,
    report: (String) -> Unit,
) {
    if (entries.isEmpty()) {
        Text(stringResource(Res.string.memory_habits_log_empty), style = AzphaltType.body, color = azphaltOnSurface())
        return
    }
    SelectionContainer {
        Text(HabitLog.render(entries), style = AzphaltType.body, color = azphaltOnSurface())
    }
    AzphaltPill(
        stringResource(Res.string.memory_habits_log_copy),
        "memory-habits-log-copy",
        onClick = {
            scope.launch {
                report(
                    runCatching { clipboard.setText(AnnotatedString(HabitLog.render(entries))) }.fold(
                        onSuccess = { getString(Res.string.memory_habits_log_copied) },
                        onFailure = { getString(Res.string.memory_habits_log_copy_failed) },
                    ),
                )
            }
        },
    )
}

