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
import com.hereliesaz.geministrator.resources.schedule_add_film
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
import com.hereliesaz.geministrator.resources.confirm_discard_schedules_action
import com.hereliesaz.geministrator.resources.confirm_discard_schedules_body
import com.hereliesaz.geministrator.resources.confirm_discard_schedules_title
import com.hereliesaz.geministrator.resources.confirm_remove_schedule_action
import com.hereliesaz.geministrator.resources.confirm_remove_schedule_body
import com.hereliesaz.geministrator.resources.confirm_remove_schedule_title
import com.hereliesaz.geministrator.resources.schedule_remove
import com.hereliesaz.geministrator.resources.schedule_dst_eu
import com.hereliesaz.geministrator.resources.schedule_dst_none
import com.hereliesaz.geministrator.resources.schedule_dst_us
import com.hereliesaz.geministrator.resources.schedule_pill_named
import com.hereliesaz.geministrator.resources.schedule_unreadable
import com.hereliesaz.geministrator.resources.schedule_unreadable_discard
import com.hereliesaz.geministrator.resources.schedule_zone_east
import com.hereliesaz.geministrator.resources.schedule_zone_west
import com.hereliesaz.geministrator.resources.schedule_run_now
import com.hereliesaz.geministrator.resources.schedule_run_now_note
import com.hereliesaz.geministrator.resources.schedule_film_added
import com.hereliesaz.geministrator.resources.schedule_film_present
import com.hereliesaz.geministrator.resources.schedule_suite_added
import com.hereliesaz.geministrator.resources.schedule_suite_present
import com.hereliesaz.geministrator.workflow.schedule.DaylightSaving
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

/** "UTC", "UTC-6", "UTC+5:30". */
internal fun offsetLabel(minutes: Int): String {
    if (minutes == 0) return "UTC"
    val sign = if (minutes < 0) "-" else "+"
    val abs = kotlin.math.abs(minutes)
    return "UTC$sign${abs / 60}" + if (abs % 60 != 0) ":" + (abs % 60).toString().padStart(2, '0') else ""
}

/** "Daily at 07:00 UTC-6", "Tuesday at 08:00 UTC", "Every 6 hours". */
@Composable
internal fun cadenceText(cadence: ScheduleCadence, utcOffsetMinutes: Int = 0): String = when (cadence.kind) {
    ScheduleCadence.Kind.EveryHours -> stringResource(Res.string.schedule_cadence_hours, cadence.everyHours)
    ScheduleCadence.Kind.Daily -> stringResource(Res.string.schedule_cadence_daily, hourLabel(cadence.hourUtc) + " " + offsetLabel(utcOffsetMinutes).let { it })
    ScheduleCadence.Kind.Weekly -> stringResource(Res.string.schedule_cadence_weekly, dayLabel(cadence.dayOfWeek), hourLabel(cadence.hourUtc) + " " + offsetLabel(utcOffsetMinutes))
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
    var unreadable by remember { mutableStateOf(false) }
    var confirmingDiscard by remember { mutableStateOf(false) }
    suspend fun refresh() {
        items = store.all()
        unreadable = store.unreadable()
    }
    LaunchedEffect(reload) { refresh() }
    // The ticker writes what it decided on the schedule; read it while this is open.
    LaunchedEffect(Unit) {
        while (true) {
            delay(5_000)
            refresh()
        }
    }
    // Every change is made to the schedule as it is stored now, never to the copy this screen last read (up to 5 s old), so it
    // cannot roll back what the ticker just wrote. A write the store refuses (an unreadable list) is said, not lost.
    fun edit(id: String, change: (WorkflowSchedule) -> WorkflowSchedule?) = scope.launch {
        runCatching { store.update(id, change) }.onFailure { message = it.message }
        reload += 1
    }
    // Turning a schedule on awaits the host's vetting; a second tap meanwhile would vet and write twice.
    var toggling by remember { mutableStateOf<String?>(null) }
    fun toggle(schedule: WorkflowSchedule) = scope.launch {
        if (toggling == schedule.id) return@launch
        toggling = schedule.id
        try {
        if (schedule.enabled) {
            runCatching { store.update(schedule.id) { it.copy(enabled = false) } }.onFailure { message = it.message }
        } else {
            // Turning it on is the keeper's review: it takes the hive as it is now, unless the hive may never be scheduled.
            val vetting = host?.scheduleVetting(schedule.workflowId)
            if (vetting?.refusal != null) {
                message = vetting.refusal
            } else {
                runCatching {
                    store.update(schedule.id) { it.copy(enabled = true, definitionFingerprint = vetting?.fingerprint ?: it.definitionFingerprint) }
                }.onFailure { message = it.message }
            }
        }
        } finally {
            toggling = null
        }
        reload += 1
    }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(stringResource(Res.string.schedule_note), style = AzphaltType.body, color = azphaltOnSurface())
        if (unreadable) {
            Text(stringResource(Res.string.schedule_unreadable), style = AzphaltType.body, color = azphaltOnSurface(), modifier = Modifier.azphaltLiveRegion())
            AzphaltPill(
                label = stringResource(Res.string.schedule_unreadable_discard),
                seed = "schedule-discard-unreadable",
                tone = PillTone.Danger,
                onClick = { confirmingDiscard = true },
            )
            if (confirmingDiscard) {
                AzphaltConfirm(
                    title = stringResource(Res.string.confirm_discard_schedules_title),
                    text = stringResource(Res.string.confirm_discard_schedules_body),
                    confirmLabel = stringResource(Res.string.confirm_discard_schedules_action),
                    onConfirm = { confirmingDiscard = false; scope.launch { store.discardUnreadable(); reload += 1 } },
                    onDismiss = { confirmingDiscard = false },
                )
            }
        }
        if (host != null && !unreadable) {
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
            // The creative hives spend money, so they are never scheduled: this only puts them on the device.
            AzphaltPill(
                label = stringResource(Res.string.schedule_add_film),
                seed = "schedule-add-film",
                tone = PillTone.Primary,
                onClick = {
                    scope.launch {
                        val added = runCatching { host.installCreativeSuite() }.getOrNull()
                        message = if (added == null || added == 0) {
                            getString(Res.string.schedule_film_present)
                        } else {
                            getString(Res.string.schedule_film_added)
                        }
                    }
                },
            )
        }
        message?.let { Text(it, style = AzphaltType.body, color = azphaltOnSurface(), modifier = Modifier.azphaltLiveRegion()) }
        if (items.isEmpty()) {
            Text(stringResource(Res.string.schedule_none), style = AzphaltType.body, color = azphaltOnSurface())
        }
        items.forEach { schedule ->
            ScheduleCard(
                schedule,
                onEdit = { change -> edit(schedule.id, change) },
                onToggle = { toggle(schedule) },
                onRemove = { scope.launch { runCatching { store.remove(schedule.id) }.onFailure { message = it.message }; reload += 1 } },
            )
        }
    }
}

@Composable
private fun ScheduleCard(
    schedule: WorkflowSchedule,
    onEdit: ((WorkflowSchedule) -> WorkflowSchedule?) -> Unit,
    onToggle: () -> Unit,
    onRemove: () -> Unit,
) {
    val next = if (schedule.enabled) {
        stringResource(Res.string.schedule_next, formatLogTime(ScheduleClock.nextDue(schedule)))
    } else {
        stringResource(Res.string.schedule_next_off)
    }
    val last = schedule.lastStartedAtEpochMillis?.let { at ->
        stringResource(Res.string.schedule_last, formatLogTime(at) + (schedule.lastNote?.let { " · $it" } ?: ""))
    } ?: (schedule.lastNote ?: stringResource(Res.string.schedule_last_never))
    val runNow = stringResource(Res.string.schedule_run_now_note)
    // A pill says what it acts on: "Run now, Nightly dependency audit", not an unnamed "Run now" among several.
    val onLabel = stringResource(if (schedule.enabled) Res.string.schedule_on else Res.string.schedule_off)
    val runNowLabel = stringResource(Res.string.schedule_run_now)
    val removeLabel = stringResource(Res.string.schedule_remove)
    val onNamed = stringResource(Res.string.schedule_pill_named, onLabel, schedule.name)
    val runNowNamed = stringResource(Res.string.schedule_pill_named, runNowLabel, schedule.name)
    val removeNamed = stringResource(Res.string.schedule_pill_named, removeLabel, schedule.name)
    var confirmingRemove by remember(schedule.id) { mutableStateOf(false) }
    AzphaltRecord(
        seed = "schedule-${schedule.id}",
        eyebrow = cadenceText(schedule.cadence, schedule.utcOffsetMinutes),
        title = schedule.name,
        body = listOf(next, last).joinToString("\n"),
        endCap = stringResource(if (schedule.enabled) Res.string.schedule_on else Res.string.schedule_off),
        well = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    AzphaltPill(
                        // The state it is in, filled while on; tapping flips it.
                        label = onLabel,
                        description = onNamed,
                        seed = "schedule-toggle-${schedule.id}",
                        selected = schedule.enabled,
                        onClick = onToggle,
                    )
                    AzphaltPill(
                        label = runNowLabel,
                        description = runNowNamed,
                        seed = "schedule-run-${schedule.id}",
                        // One start at the next look; the schedule is not turned on and keeps its own cadence.
                        onClick = { onEdit { it.copy(runNowRequested = true, lastNote = runNow) } },
                    )
                    AzphaltPill(
                        label = removeLabel,
                        description = removeNamed,
                        seed = "schedule-remove-${schedule.id}",
                        tone = PillTone.Danger,
                        onClick = { confirmingRemove = true },
                    )
                    if (confirmingRemove) {
                        AzphaltConfirm(
                            title = stringResource(Res.string.confirm_remove_schedule_title, schedule.name),
                            text = stringResource(Res.string.confirm_remove_schedule_body),
                            confirmLabel = stringResource(Res.string.confirm_remove_schedule_action),
                            onConfirm = { confirmingRemove = false; onRemove() },
                            onDismiss = { confirmingRemove = false },
                        )
                    }
                }
                if (schedule.cadence.kind != ScheduleCadence.Kind.EveryHours) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        AzphaltPill(
                            label = stringResource(Res.string.schedule_zone_west) + " (" + offsetLabel(schedule.utcOffsetMinutes) + ")",
                            seed = "schedule-zone-west-${schedule.id}",
                            onClick = { onEdit { it.copy(utcOffsetMinutes = (it.utcOffsetMinutes - 30).coerceAtLeast(-14 * 60), lastStartedAtEpochMillis = null, createdAtEpochMillis = currentEpochMillis()) } },
                        )
                        AzphaltPill(
                            label = stringResource(Res.string.schedule_zone_east),
                            seed = "schedule-zone-east-${schedule.id}",
                            onClick = { onEdit { it.copy(utcOffsetMinutes = (it.utcOffsetMinutes + 30).coerceAtMost(14 * 60), lastStartedAtEpochMillis = null, createdAtEpochMillis = currentEpochMillis()) } },
                        )
                        // None, then the US rule, then the EU rule: the offset above is the winter one.
                        AzphaltPill(
                            label = stringResource(
                                when (schedule.daylightSaving) {
                                    DaylightSaving.None -> Res.string.schedule_dst_none
                                    DaylightSaving.UnitedStates -> Res.string.schedule_dst_us
                                    DaylightSaving.Europe -> Res.string.schedule_dst_eu
                                },
                            ),
                            seed = "schedule-dst-${schedule.id}",
                            onClick = {
                                onEdit {
                                    it.copy(
                                        daylightSaving = DaylightSaving.entries[(it.daylightSaving.ordinal + 1) % DaylightSaving.entries.size],
                                        lastStartedAtEpochMillis = null,
                                        createdAtEpochMillis = currentEpochMillis(),
                                    )
                                }
                            },
                        )
                    }
                }
                CadenceEditor(schedule.cadence) { cadence -> onEdit { it.copy(cadence = cadence, createdAtEpochMillis = currentEpochMillis(), lastStartedAtEpochMillis = null) } }
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
