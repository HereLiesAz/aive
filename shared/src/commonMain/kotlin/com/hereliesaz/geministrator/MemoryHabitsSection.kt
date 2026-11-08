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
import androidx.compose.ui.unit.dp
import com.hereliesaz.geministrator.memory.MemoryLayerController
import com.hereliesaz.geministrator.memory.MemoryLayerSettings
import com.hereliesaz.geministrator.memory.procedural.FormedHabit
import com.hereliesaz.geministrator.memory.procedural.FormedSequence
import com.hereliesaz.geministrator.memory.procedural.HabitOffers
import com.hereliesaz.geministrator.memory.procedural.ProcedureDeliberationOutcome
import com.hereliesaz.geministrator.resources.Res
import com.hereliesaz.geministrator.resources.memory_habits
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
import com.hereliesaz.geministrator.resources.memory_habits_recorded
import com.hereliesaz.geministrator.resources.memory_habits_replace
import com.hereliesaz.geministrator.resources.memory_habits_sequence_line
import com.hereliesaz.geministrator.resources.memory_habits_sequences
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

    if (habits.isEmpty()) {
        Text(stringResource(Res.string.memory_habits_none), style = AzphaltType.body, color = azphaltOnSurface())
    }
    habits.forEach { habit ->
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SelectionContainer {
                Text(HabitOffers.describe(habit, now), style = AzphaltType.body, color = azphaltOnSurface())
            }
            if (open != habit.operation) {
                AzphaltPill(
                    stringResource(Res.string.memory_habits_flag),
                    "memory-habit-open-${habit.operation}",
                    onClick = { open = habit.operation; message = null },
                )
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
        }
    }
    message?.let { Text(it, style = AzphaltType.body, color = azphaltOnSurface()) }
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
