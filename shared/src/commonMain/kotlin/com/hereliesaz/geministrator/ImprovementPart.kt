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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.hereliesaz.geministrator.domain.RoleDefinition
import com.hereliesaz.geministrator.log.formatLogTime
import com.hereliesaz.geministrator.resources.Res
import com.hereliesaz.geministrator.resources.improvement_evidence
import com.hereliesaz.geministrator.resources.improvement_adopt
import com.hereliesaz.geministrator.resources.improvement_already_decided
import com.hereliesaz.geministrator.resources.improvement_breaks_loop
import com.hereliesaz.geministrator.resources.improvement_not_recorded
import com.hereliesaz.geministrator.resources.improvement_replaces
import com.hereliesaz.geministrator.resources.improvement_save_failed
import com.hereliesaz.geministrator.resources.improvement_would_break_loop
import com.hereliesaz.geministrator.resources.improvement_add_to_hive
import com.hereliesaz.geministrator.resources.improvement_source_nanny
import com.hereliesaz.geministrator.resources.improvement_source_queen
import com.hereliesaz.geministrator.resources.improvement_source_habit
import com.hereliesaz.geministrator.resources.improvement_source_loop
import com.hereliesaz.geministrator.resources.improvement_source_role
import com.hereliesaz.geministrator.resources.improvement_status_superseded
import com.hereliesaz.geministrator.resources.improvement_habit_added
import com.hereliesaz.geministrator.resources.improvement_habit_no_memory
import com.hereliesaz.geministrator.resources.improvement_stale
import com.hereliesaz.geministrator.resources.improvement_body
import com.hereliesaz.geministrator.resources.improvement_cannot
import com.hereliesaz.geministrator.resources.improvement_dismiss
import com.hereliesaz.geministrator.resources.improvement_lead_note
import com.hereliesaz.geministrator.resources.improvement_manual_noted
import com.hereliesaz.geministrator.resources.improvement_meta
import com.hereliesaz.geministrator.resources.improvement_no_loop
import com.hereliesaz.geministrator.resources.improvement_none
import com.hereliesaz.geministrator.resources.improvement_revert
import com.hereliesaz.geministrator.resources.improvement_revert_conflict
import com.hereliesaz.geministrator.resources.improvement_run_cycle
import com.hereliesaz.geministrator.resources.improvement_run_cycle_note
import com.hereliesaz.geministrator.resources.improvement_standing_examined
import com.hereliesaz.geministrator.resources.improvement_standing_never
import com.hereliesaz.geministrator.resources.improvement_standing_overdue
import com.hereliesaz.geministrator.resources.improvement_standing_skipped
import com.hereliesaz.geministrator.resources.improvement_status_adopted
import com.hereliesaz.geministrator.resources.improvement_status_dismissed
import com.hereliesaz.geministrator.resources.improvement_status_proposed
import com.hereliesaz.geministrator.resources.improvement_status_reverted
import com.hereliesaz.geministrator.domain.WorkflowDefinition
import com.hereliesaz.geministrator.domain.WorkflowDefinitionId
import com.hereliesaz.geministrator.memory.MemoryLayerController
import com.hereliesaz.geministrator.workflow.improvement.ImprovementApplier
import com.hereliesaz.geministrator.workflow.improvement.ImprovementKind
import com.hereliesaz.geministrator.workflow.improvement.ImprovementSource
import com.hereliesaz.geministrator.workflow.improvement.ProposalMirrors
import com.hereliesaz.geministrator.workflow.improvement.ImprovementProposal
import com.hereliesaz.geministrator.workflow.improvement.ImprovementState
import com.hereliesaz.geministrator.workflow.improvement.ImprovementStore
import com.hereliesaz.geministrator.workflow.improvement.LoopObligation
import com.hereliesaz.geministrator.workflow.improvement.ProposalStatus
import com.hereliesaz.geministrator.workflow.improvement.SelfImprovementLoop
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource

/** The loop's proposals and cycle records, for the Keeper's room; null where nothing is kept. */
internal val LocalImprovementStore = compositionLocalOf<ImprovementStore?> { null }

/**
 * Improvement (docs/architecture/SELF_IMPROVEMENT.md): what the loop proposes, and nothing else. A proposal
 * changes a role, a hive or a schedule only when it is adopted here, by the keeper, with the exact
 * text shown; adoption records the text before and after, and **Revert** puts it back (unless something
 * edited it since). A proposal about the loop itself is marked, and is adopted like any other, except that a change
 * that would leave the loop breaking its own rules is refused ([ImprovementAdoption]).
 */
@Composable
internal fun ImprovementPart(
    /** The habits layer, whose formed habits are offered here as steps for a hive; null where there is none. */
    memoryLayer: MemoryLayerController? = null,
    /** Replaces the instructions of one installed role (the Mind's save path); returns once it is saved and throws if it could not be. */
    onSaveRoleInstructions: suspend (roleId: String, instructions: String) -> Unit,
) {
    val store = LocalImprovementStore.current ?: return
    val host = LocalWorkflowLibraryHost.current
    val schedules = LocalWorkflowScheduleStore.current
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf(ImprovementState()) }
    var roles by remember { mutableStateOf<List<RoleDefinition>>(emptyList()) }
    var authored by remember { mutableStateOf<List<WorkflowDefinition>>(emptyList()) }
    // A decision is running: its buttons are off until it ends, so a second tap cannot start a second one.
    var deciding by remember { mutableStateOf(false) }
    val saveRole by rememberUpdatedState(onSaveRoleInstructions)
    val adoption = remember(store, host) { ImprovementAdoption(store, host, { roleId, instructions -> saveRole(roleId, instructions) }) }
    var hasLoop by remember { mutableStateOf(true) }
    var message by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableStateOf(0) }
    LaunchedEffect(reload) {
        // The other two mechanisms are mirrored into the one ledger, so all three are read and decided here.
        syncSources(store, memoryLayer)
        state = store.state()
        roles = host?.installedRoles().orEmpty()
        authored = host?.entries()?.filter { it.origin == WorkflowLibraryOrigin.Authored }?.map { it.definition }.orEmpty()
        hasLoop = host?.entries()?.any { it.definition.id.value == SelfImprovementLoop.HIVE_ID } ?: true
    }
    LaunchedEffect(Unit) {
        while (true) {
            delay(5_000)
            state = store.state()
        }
    }
    val standing = LoopObligation.standing(state, currentEpochMillis())

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(stringResource(Res.string.improvement_lead_note), style = AzphaltType.body, color = azphaltOnSurface())
        // The cycle is added and run in Schedules: a standing line that asks for it opens that part.
        val openSchedules = LocalPlaceLinks.current.openKeeperPart?.let { open -> { open(KeeperSection.Schedules) } }
        LinkText(
            stringResource(
                when (standing) {
                    LoopObligation.Standing.Examined -> Res.string.improvement_standing_examined
                    LoopObligation.Standing.NeverRan -> Res.string.improvement_standing_never
                    LoopObligation.Standing.Overdue -> Res.string.improvement_standing_overdue
                    LoopObligation.Standing.LastCycleSkippedItself -> Res.string.improvement_standing_skipped
                },
            ),
            onClick = openSchedules.takeIf { standing != LoopObligation.Standing.Examined },
            modifier = Modifier.azphaltLiveRegion(),
        )
        if (!hasLoop) {
            LinkText(stringResource(Res.string.improvement_no_loop), onClick = openSchedules)
        } else if (schedules != null) {
            AzphaltPill(
                label = stringResource(Res.string.improvement_run_cycle),
                seed = "improvement-run-cycle",
                tone = PillTone.Primary,
                onClick = {
                    scope.launch {
                        message = getString(
                            if (ImprovementAdoption.runCycleNow(schedules)) Res.string.improvement_run_cycle_note else Res.string.improvement_no_loop,
                        )
                    }
                },
            )
        }
        message?.let { Text(it, style = AzphaltType.body, color = azphaltOnSurface(), modifier = Modifier.azphaltLiveRegion()) }
        // The queen's suggestions are per run and decided in Messages, not here.
        val listed = state.proposals.filter { it.source != ImprovementSource.Queen }
        if (listed.isEmpty()) Text(stringResource(Res.string.improvement_none), style = AzphaltType.body, color = azphaltOnSurface())
        listed.sortedByDescending { it.createdAtEpochMillis }.forEach { proposal ->
            ProposalCard(
                proposal = proposal,
                hives = authored,
                roles = roles,
                busy = deciding,
                onAdopt = {
                    decide(scope, { deciding }, { deciding = it }) {
                        message = describe(adoption.adopt(proposal.id))
                        reload += 1
                    }
                },
                onAddToHive = { hive ->
                    decide(scope, { deciding }, { deciding = it }) {
                        message = adoptHabit(proposal, hive, store, host, memoryLayer)
                        reload += 1
                    }
                },
                onDismiss = {
                    decide(scope, { deciding }, { deciding = it }) {
                        message = describe(adoption.dismiss(proposal.id))
                        reload += 1
                    }
                },
                onRevert = {
                    decide(scope, { deciding }, { deciding = it }) {
                        message = describe(adoption.revert(proposal.id))
                        reload += 1
                    }
                },
            )
        }
    }
}

/** Runs one decision, unless another is already running; the screen's buttons are off meanwhile. */
private fun decide(scope: CoroutineScope, running: () -> Boolean, setRunning: (Boolean) -> Unit, block: suspend () -> Unit) {
    if (running()) return
    setRunning(true)
    scope.launch {
        try {
            block()
        } finally {
            setRunning(false)
        }
    }
}

/** The words for what a decision came to; nothing to say when it simply worked. */
private suspend fun describe(result: AdoptionResult): String = when (result) {
    AdoptionResult.Done -> ""
    AdoptionResult.Noted -> getString(Res.string.improvement_manual_noted)
    AdoptionResult.Stale -> getString(Res.string.improvement_stale)
    AdoptionResult.AlreadyDecided -> getString(Res.string.improvement_already_decided)
    is AdoptionResult.Cannot -> getString(Res.string.improvement_cannot, result.why)
    is AdoptionResult.BreaksLoop -> getString(Res.string.improvement_breaks_loop, result.violations.joinToString(" "))
    AdoptionResult.RevertConflict -> getString(Res.string.improvement_revert_conflict)
    is AdoptionResult.SaveFailed -> getString(Res.string.improvement_save_failed, result.reason)
    AdoptionResult.NotRecorded -> getString(Res.string.improvement_not_recorded)
}

/** Mirrors the role-instruction proposals and the formed habits into the one ledger. Reading only: nothing is decided. */
private suspend fun syncSources(store: ImprovementStore, memoryLayer: MemoryLayerController?) {
    val now = currentEpochMillis()
    store.merge { ProposalMirrors.roleHistory(it, RoleInstructionProposalsHost.ledger.state.value, now) }
    val banks = memoryLayer?.knownBanks?.value.orEmpty()
    val habits = banks.flatMap { bank ->
        runCatching { memoryLayer!!.formedHabits(bank) }.getOrDefault(emptyList()).map { bank to it }
    }
    if (habits.isNotEmpty()) store.merge { ProposalMirrors.habits(it, habits, now) }
}

/** A habit becomes a step in the chosen hive, behind its own approval step (WorkflowCompositionService.addProposedTasks). */
private suspend fun adoptHabit(
    proposal: ImprovementProposal,
    hive: WorkflowDefinition,
    store: ImprovementStore,
    host: WorkflowLibraryHost?,
    memoryLayer: MemoryLayerController?,
): String {
    if (host == null || memoryLayer == null || proposal.sourceRef == null) return getString(Res.string.improvement_habit_no_memory)
    if (store.state().proposals.none { it.id == proposal.id && it.status == ProposalStatus.Proposed }) return getString(Res.string.improvement_already_decided)
    val taskId = "habit-" + proposal.target.filter { it.isLetterOrDigit() }.take(24).ifEmpty { "step" }
    return when (val result = memoryLayer.proposeHabitTask(proposal.sourceRef!!, proposal.target, taskId)) {
        is com.hereliesaz.geministrator.memory.procedural.HabitInvocation.Result.Proposed -> {
            val saved = runCatching { host.addProposedTasks(hive.id, listOf(result.task)) }
            saved.exceptionOrNull()?.let { return it.message ?: getString(Res.string.improvement_habit_no_memory) }
            val addedNote = getString(Res.string.improvement_habit_added, hive.name)
            val decided = store.decide(proposal.id) {
                it.copy(status = ProposalStatus.Adopted, decidedAtEpochMillis = currentEpochMillis(), note = addedNote)
            }
            appLog("Improvement", "Adopted: ${proposal.summary} (into ${hive.id.value})")
            if (decided) getString(Res.string.improvement_habit_added, hive.name) else getString(Res.string.improvement_not_recorded)
        }
        is com.hereliesaz.geministrator.memory.procedural.HabitInvocation.Result.Refused -> result.reason
    }
}

@Composable
private fun ProposalCard(
    proposal: ImprovementProposal,
    hives: List<WorkflowDefinition>,
    roles: List<RoleDefinition>,
    busy: Boolean,
    onAdopt: () -> Unit,
    onAddToHive: (WorkflowDefinition) -> Unit,
    onDismiss: () -> Unit,
    onRevert: () -> Unit,
) {
    val status = stringResource(
        when (proposal.status) {
            ProposalStatus.Proposed -> Res.string.improvement_status_proposed
            ProposalStatus.Adopted -> Res.string.improvement_status_adopted
            ProposalStatus.Dismissed -> Res.string.improvement_status_dismissed
            ProposalStatus.Reverted -> Res.string.improvement_status_reverted
            ProposalStatus.Superseded -> Res.string.improvement_status_superseded
        },
    )
    val source = stringResource(
        when (proposal.source) {
            ImprovementSource.Loop -> Res.string.improvement_source_loop
            ImprovementSource.RoleHistory -> Res.string.improvement_source_role
            ImprovementSource.Habit -> Res.string.improvement_source_habit
            ImprovementSource.Queen -> Res.string.improvement_source_queen
            ImprovementSource.Nanny -> Res.string.improvement_source_nanny
            ImprovementSource.QueenKept -> Res.string.improvement_source_queen
        },
    )
    // A hive-step proposal replaces a step's whole objective: show what it replaces, and what it would break in the loop.
    val preview = if (proposal.status == ProposalStatus.Proposed) ImprovementApplier.preview(proposal, roles, hives) else null
    val eyebrow = status + " · " + source + " · " + proposal.kind.label + " · " + proposal.target + if (proposal.meta) " · " + stringResource(Res.string.improvement_meta) else ""
    AzphaltRecord(
        seed = "improvement-${proposal.id}",
        eyebrow = eyebrow,
        title = proposal.summary,
        body = stringResource(
            Res.string.improvement_body,
            proposal.expectedEffect, proposal.undo, proposal.newText.ifBlank { "-" }, formatLogTime(proposal.createdAtEpochMillis),
        ) + (preview?.let { "\n" + stringResource(Res.string.improvement_replaces, it.before) } ?: "") +
            (preview?.loopViolations?.takeIf { it.isNotEmpty() }?.let { "\n" + stringResource(Res.string.improvement_would_break_loop, it.joinToString(" ")) } ?: "") +
            (proposal.evidence.takeIf { it.isNotBlank() }?.let { "\n" + stringResource(Res.string.improvement_evidence, it) } ?: "") + (proposal.note?.let { "\n$it" } ?: ""),
        well = if (proposal.status != ProposalStatus.Proposed && !(proposal.status == ProposalStatus.Adopted && proposal.appliedBefore != null)) null else {
            {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when (proposal.status) {
                    ProposalStatus.Proposed -> {
                        if (proposal.kind == ImprovementKind.HabitTask) {
                            hives.forEach { hive ->
                                AzphaltPill(
                                    stringResource(Res.string.improvement_add_to_hive, hive.name), "improvement-habit-${proposal.id}-${hive.id.value}",
                                    tone = PillTone.Primary, enabled = !busy, onClick = { onAddToHive(hive) },
                                )
                            }
                        } else {
                            AzphaltPill(stringResource(Res.string.improvement_adopt), "improvement-adopt-${proposal.id}", tone = PillTone.Primary, enabled = !busy, onClick = onAdopt)
                        }
                        AzphaltPill(stringResource(Res.string.improvement_dismiss), "improvement-dismiss-${proposal.id}", enabled = !busy, onClick = onDismiss)
                    }
                    ProposalStatus.Adopted -> if (proposal.appliedBefore != null) {
                        AzphaltPill(stringResource(Res.string.improvement_revert), "improvement-revert-${proposal.id}", enabled = !busy, onClick = onRevert)
                    }
                    else -> Unit
                }
            }
            }
        },
    )
}
