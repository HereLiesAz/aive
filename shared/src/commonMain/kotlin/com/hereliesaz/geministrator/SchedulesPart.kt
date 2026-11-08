@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.hereliesaz.geministrator

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.hereliesaz.geministrator.log.formatLogTime
import com.hereliesaz.geministrator.resources.Res
import com.hereliesaz.geministrator.resources.schedule_add_suite
import com.hereliesaz.geministrator.resources.schedule_cadence_daily
import com.hereliesaz.geministrator.resources.schedule_cadence_hours
import com.hereliesaz.geministrator.resources.schedule_cadence_weekly
import com.hereliesaz.geministrator.resources.schedule_day_1
import com.hereliesaz.geministrator.resources.schedule_day_2
import com.hereliesaz.geministrator.resources.schedule_day_3
import com.hereliesaz.geministrator.resources.schedule_day_4
import com.hereliesaz.geministrator.resources.schedule_day_5
import com.hereliesaz.geministrator.resources.schedule_day_6
import com.hereliesaz.geministrator.resources.schedule_day_7
import com.hereliesaz.geministrator.resources.schedule_day_earlier
import com.hereliesaz.geministrator.resources.schedule_day_later
import com.hereliesaz.geministrator.resources.schedule_hour_earlier
import com.hereliesaz.geministrator.resources.schedule_hour_later
import com.hereliesaz.geministrator.resources.schedule_hours_less
import com.hereliesaz.geministrator.resources.schedule_hours_more
import com.hereliesaz.geministrator.resources.schedule_kind_daily
import com.hereliesaz.geministrator.resources.schedule_kind_hours
import com.hereliesaz.geministrator.resources.schedule_kind_weekly
import com.hereliesaz.geministrator.resources.schedule_last
import com.hereliesaz.geministrator.resources.schedule_last_never
import com.hereliesaz.geministrator.resources.schedule_next
import com.hereliesaz.geministrator.resources.schedule_next_off
import com.hereliesaz.geministrator.resources.schedule_none
import com.hereliesaz.geministrator.resources.schedule_note
import com.hereliesaz.geministrator.resources.schedule_off
import com.hereliesaz.geministrator.resources.schedule_on
import com.hereliesaz.geministrator.resources.schedule_remove
import com.hereliesaz.geministrator.resources.schedule_run_now
import com.hereliesaz.geministrator.resources.schedule_run_now_note
import com.hereliesaz.geministrator.resources.schedule_suite_added
import com.hereliesaz.geministrator.resources.schedule_suite_present
import com.hereliesaz.geministrator.workflow.schedule.ScheduleCadence
import com.hereliesaz.geministrator.workflow.schedule.ScheduleClock
import com.hereliesaz.geministrator.workflow.schedule.WorkflowSchedule
import com.hereliesaz.geministrator.workflow.schedule.WorkflowScheduleStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/** The keeper's schedules, for the Keeper's room; null where nothing is kept. */
internal val LocalWorkflowScheduleStore = compositionLocalOf<WorkflowScheduleStore?> { null }

@OptIn(ExperimentalTime::class)
internal fun currentEpochMillis(): Long = Clock.System.now().toEpochMilliseconds()

@Composable
private fun dayLabel(day: Int): String = stringResource(
    when (day) {
        1 -> Res.string.schedule_day_1
        2 -> Res.string.schedule_day_2
        3 -> Res.string.schedule_day_3
        4 -> Res.string.schedule_day_4
        5 -> Res.string.schedule_day_5
        6 -> Res.string.schedule_day_6
        else -> Res.string.schedule_day_7
    },
)

private fun hourLabel(hour: Int) = hour.toString().padStart(2, '0') + ":00"

/** "Daily at 07:00 UTC", "Tuesday at 08:00 UTC", "Every 6 hours". */
@Composable
internal fun cadenceText(cadence: ScheduleCadence): String = when (cadence.kind) {
    ScheduleCadence.Kind.EveryHours -> stringResource(Res.string.schedule_cadence_hours, cadence.everyHours)
    ScheduleCadence.Kind.Daily -> stringResource(Res.string.schedule_cadence_daily, hourLabel(cadence.hourUtc))
    ScheduleCadence.Kind.Weekly -> stringResource(Res.string.schedule_cadence_weekly, dayLabel(cadence.dayOfWeek), hourLabel(cadence.hourUtc))
}

/**
 * Schedules (docs/Scheduled-Workflows.md): add the coding suite, and for each schedule turn it on, change
 * when it runs, run it now, or remove it. What the app decided about each (started, held behind an
 * active run, could not start) is written on it and in the app log.
 */
@Composable
internal fun SchedulesPart(compact: Boolean) {
    val store = LocalWorkflowScheduleStore.current ?: return
    val host = LocalWorkflowLibraryHost.current
    val scope = rememberCoroutineScope()
    var items by remember { mutableStateOf<List<WorkflowSchedule>>(emptyList()) }
    var reload by remember { mutableStateOf(0) }
    var message by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(reload) { items = store.all() }
    // The ticker writes what it decided on the schedule; read it while this is open.
    LaunchedEffect(Unit) {
        while (true) {
            delay(5_000)
            items = store.all()
        }
    }
    fun change(schedule: WorkflowSchedule) = scope.launch { store.put(schedule); reload += 1 }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(stringResource(Res.string.schedule_note), style = AzphaltType.body, color = azphaltOnSurface())
        if (host != null) {
            AzphaltPill(
                label = stringResource(Res.string.schedule_add_suite),
                seed = "schedule-add-suite",
                tone = PillTone.Primary,
                onClick = {
                    scope.launch {
                        val added = runCatching { host.installCodingSuite(store, currentEpochMillis()) }.getOrNull()
                        message = if (added == null || added == 0) {
                            getString(Res.string.schedule_suite_present)
                        } else {
                            getString(Res.string.schedule_suite_added, added)
                        }
                        reload += 1
                    }
                },
            )
        }
        message?.let { Text(it, style = AzphaltType.body, color = azphaltOnSurface(), modifier = Modifier.azphaltLiveRegion()) }
        if (items.isEmpty()) {
            Text(stringResource(Res.string.schedule_none), style = AzphaltType.body, color = azphaltOnSurface())
        }
        items.forEach { schedule -> ScheduleCard(schedule, onChange = ::change, onRemove = { scope.launch { store.remove(schedule.id); reload += 1 } }) }
    }
}

@Composable
private fun ScheduleCard(schedule: WorkflowSchedule, onChange: (WorkflowSchedule) -> Unit, onRemove: () -> Unit) {
    val next = if (schedule.enabled) {
        stringResource(Res.string.schedule_next, formatLogTime(ScheduleClock.nextDue(schedule)))
    } else {
        stringResource(Res.string.schedule_next_off)
    }
    val last = schedule.lastStartedAtEpochMillis?.let { at ->
        stringResource(Res.string.schedule_last, formatLogTime(at) + (schedule.lastNote?.let { " · $it" } ?: ""))
    } ?: (schedule.lastNote ?: stringResource(Res.string.schedule_last_never))
    val runNow = stringResource(Res.string.schedule_run_now_note)
    AzphaltRecord(
        seed = "schedule-${schedule.id}",
        eyebrow = cadenceText(schedule.cadence),
        title = schedule.name,
        body = listOf(next, last).joinToString("\n"),
        endCap = stringResource(if (schedule.enabled) Res.string.schedule_on else Res.string.schedule_off),
        well = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    AzphaltPill(
                        // The state it is in, filled while on; tapping flips it.
                        label = stringResource(if (schedule.enabled) Res.string.schedule_on else Res.string.schedule_off),
                        seed = "schedule-toggle-${schedule.id}",
                        selected = schedule.enabled,
                        onClick = { onChange(schedule.copy(enabled = !schedule.enabled)) },
                    )
                    AzphaltPill(
                        label = stringResource(Res.string.schedule_run_now),
                        seed = "schedule-run-${schedule.id}",
                        // Due at the next look: an earlier clock for its slot, and on.
                        onClick = {
                            onChange(schedule.copy(enabled = true, createdAtEpochMillis = 0L, lastStartedAtEpochMillis = null, lastNote = runNow))
                        },
                    )
                    AzphaltPill(
                        label = stringResource(Res.string.schedule_remove),
                        seed = "schedule-remove-${schedule.id}",
                        tone = PillTone.Danger,
                        onClick = onRemove,
                    )
                }
                CadenceEditor(schedule.cadence) { onChange(schedule.copy(cadence = it, createdAtEpochMillis = currentEpochMillis(), lastStartedAtEpochMillis = null)) }
            }
        },
    )
}

/** Kind of cadence, then its numbers one step at a time; every change starts counting from now. */
@Composable
private fun CadenceEditor(cadence: ScheduleCadence, onChange: (ScheduleCadence) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(
                ScheduleCadence.Kind.EveryHours to Res.string.schedule_kind_hours,
                ScheduleCadence.Kind.Daily to Res.string.schedule_kind_daily,
                ScheduleCadence.Kind.Weekly to Res.string.schedule_kind_weekly,
            ).forEach { (kind, label) ->
                AzphaltPill(
                    label = stringResource(label),
                    seed = "cadence-kind-$kind",
                    selected = cadence.kind == kind,
                    onClick = { if (cadence.kind != kind) onChange(cadence.copy(kind = kind)) },
                )
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when (cadence.kind) {
                ScheduleCadence.Kind.EveryHours -> {
                    AzphaltPill(stringResource(Res.string.schedule_hours_less), "cadence-hours-less", onClick = { onChange(cadence.copy(everyHours = (cadence.everyHours - 1).coerceAtLeast(1))) })
                    AzphaltPill(stringResource(Res.string.schedule_hours_more), "cadence-hours-more", onClick = { onChange(cadence.copy(everyHours = (cadence.everyHours + 1).coerceAtMost(24 * 31))) })
                }
                ScheduleCadence.Kind.Daily, ScheduleCadence.Kind.Weekly -> {
                    AzphaltPill(stringResource(Res.string.schedule_hour_earlier), "cadence-hour-earlier", onClick = { onChange(cadence.copy(hourUtc = (cadence.hourUtc + 23) % 24)) })
                    AzphaltPill(stringResource(Res.string.schedule_hour_later), "cadence-hour-later", onClick = { onChange(cadence.copy(hourUtc = (cadence.hourUtc + 1) % 24)) })
                    if (cadence.kind == ScheduleCadence.Kind.Weekly) {
                        AzphaltPill(stringResource(Res.string.schedule_day_earlier), "cadence-day-earlier", onClick = { onChange(cadence.copy(dayOfWeek = (cadence.dayOfWeek + 5) % 7 + 1)) })
                        AzphaltPill(stringResource(Res.string.schedule_day_later), "cadence-day-later", onClick = { onChange(cadence.copy(dayOfWeek = cadence.dayOfWeek % 7 + 1)) })
                    }
                }
            }
        }
    }
}
