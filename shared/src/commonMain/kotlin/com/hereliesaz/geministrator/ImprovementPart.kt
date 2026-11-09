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
import com.hereliesaz.geministrator.domain.RoleDefinition
import com.hereliesaz.geministrator.log.formatLogTime
import com.hereliesaz.geministrator.resources.Res
import com.hereliesaz.geministrator.resources.improvement_adopt
import com.hereliesaz.geministrator.resources.improvement_add_to_hive
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
import com.hereliesaz.geministrator.memory.procedural.RoleInstructionApproval
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
import com.hereliesaz.geministrator.workflow.schedule.WorkflowScheduleStore
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
 * edited it since). A proposal about the loop itself is marked, and is adopted exactly like any other.
 */
@Composable
internal fun ImprovementPart(
    /** The habits layer, whose formed habits are offered here as steps for a hive; null where there is none. */
    memoryLayer: MemoryLayerController? = null,
    /** Replaces the instructions of one installed role (the Mind's save path); true when it was saved. */
    onSaveRoleInstructions: suspend (roleId: String, instructions: String) -> Unit,
) {
    val store = LocalImprovementStore.current ?: return
    val host = LocalWorkflowLibraryHost.current
    val schedules = LocalWorkflowScheduleStore.current
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf(ImprovementState()) }
    var roles by remember { mutableStateOf<List<RoleDefinition>>(emptyList()) }
    var authored by remember { mutableStateOf<List<WorkflowDefinition>>(emptyList()) }
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
        Text(
            stringResource(
                when (standing) {
                    LoopObligation.Standing.Examined -> Res.string.improvement_standing_examined
                    LoopObligation.Standing.NeverRan -> Res.string.improvement_standing_never
                    LoopObligation.Standing.Overdue -> Res.string.improvement_standing_overdue
                    LoopObligation.Standing.LastCycleSkippedItself -> Res.string.improvement_standing_skipped
                },
            ),
            style = AzphaltType.body, color = azphaltOnSurface(), modifier = Modifier.azphaltLiveRegion(),
        )
        if (!hasLoop) {
            Text(stringResource(Res.string.improvement_no_loop), style = AzphaltType.body, color = azphaltOnSurface())
        } else if (schedules != null) {
            AzphaltPill(
                label = stringResource(Res.string.improvement_run_cycle),
                seed = "improvement-run-cycle",
                tone = PillTone.Primary,
                onClick = { scope.launch { runCycleNow(schedules); message = getString(Res.string.improvement_run_cycle_note) } },
            )
        }
        message?.let { Text(it, style = AzphaltType.body, color = azphaltOnSurface(), modifier = Modifier.azphaltLiveRegion()) }
        if (state.proposals.isEmpty()) Text(stringResource(Res.string.improvement_none), style = AzphaltType.body, color = azphaltOnSurface())
        state.proposals.sortedByDescending { it.createdAtEpochMillis }.forEach { proposal ->
            ProposalCard(
                proposal = proposal,
                hives = authored,
                onAdopt = {
                    scope.launch {
                        message = adopt(proposal, store, host, roles, onSaveRoleInstructions)
                        reload += 1
                    }
                },
                onAddToHive = { hive ->
                    scope.launch {
                        message = adoptHabit(proposal, hive, store, host, memoryLayer)
                        reload += 1
                    }
                },
                onDismiss = {
                    scope.launch {
                        // A proposal from run history is dismissed there too, so its lines are never offered again.
                        if (proposal.source == ImprovementSource.RoleHistory) proposal.sourceRef?.let { RoleInstructionProposalsHost.ledger.dismiss(it) }
                        store.decide(proposal.id) { it.copy(status = ProposalStatus.Dismissed, decidedAtEpochMillis = currentEpochMillis()) }
                        reload += 1
                    }
                },
                onRevert = {
                    scope.launch {
                        message = revert(proposal, store, host, roles, onSaveRoleInstructions)
                        reload += 1
                    }
                },
            )
        }
    }
}

/** Makes the loop's schedule due at the next look: on, and counted from long ago. */
private suspend fun runCycleNow(schedules: WorkflowScheduleStore) {
    val schedule = schedules.all().firstOrNull { it.id == SelfImprovementLoop.SCHEDULE_ID } ?: return
    schedules.put(schedule.copy(enabled = true, createdAtEpochMillis = 0L, lastStartedAtEpochMillis = null))
}

private suspend fun adopt(
    proposal: ImprovementProposal,
    store: ImprovementStore,
    host: WorkflowLibraryHost?,
    roles: List<RoleDefinition>,
    saveRole: suspend (String, String) -> Unit,
): String {
    val hives = host?.entries()?.filter { it.origin == WorkflowLibraryOrigin.Authored }?.map { it.definition }.orEmpty()
    val now = currentEpochMillis()
    if (proposal.source == ImprovementSource.RoleHistory && proposal.sourceRef != null) {
        // Decided by the mechanism that made it: its own check that the role is still as it was drafted against.
        val before = roles.firstOrNull { it.id.value == proposal.target }?.instructions
        return when (val result = RoleInstructionProposalsHost.ledger.approve(proposal.sourceRef!!, roles)) {
            is RoleInstructionApproval.Applied -> {
                saveRole(proposal.target, result.role.instructions)
                store.decide(proposal.id) {
                    it.copy(status = ProposalStatus.Adopted, decidedAtEpochMillis = now, appliedBefore = before, appliedAfter = result.role.instructions)
                }
                appLog("Improvement", "Adopted: ${proposal.summary}")
                ""
            }
            RoleInstructionApproval.Stale -> {
                store.decide(proposal.id) { it.copy(status = ProposalStatus.Superseded, decidedAtEpochMillis = now, note = "The role changed since it was drafted.") }
                getString(Res.string.improvement_stale)
            }
            RoleInstructionApproval.NotFound -> getString(Res.string.improvement_stale)
        }
    }
    return when (val plan = ImprovementApplier.plan(proposal, roles, hives)) {
        is ImprovementApplier.Plan.RoleInstructions -> {
            saveRole(plan.roleId, plan.after)
            store.decide(proposal.id) { it.copy(status = ProposalStatus.Adopted, decidedAtEpochMillis = now, appliedBefore = plan.before, appliedAfter = plan.after) }
            appLog("Improvement", "Adopted: ${proposal.summary} (role ${plan.roleId})")
            ""
        }
        is ImprovementApplier.Plan.HiveStepObjective -> {
            val hive = hives.first { it.id.value == plan.hiveId }
            host!!.saveAuthored(ImprovementApplier.applyTo(hive, plan))
            store.decide(proposal.id) { it.copy(status = ProposalStatus.Adopted, decidedAtEpochMillis = now, appliedBefore = plan.before, appliedAfter = plan.after) }
            appLog("Improvement", "Adopted: ${proposal.summary} (${plan.hiveId}/${plan.taskId})")
            ""
        }
        is ImprovementApplier.Plan.Manual -> {
            store.decide(proposal.id) { it.copy(status = ProposalStatus.Adopted, decidedAtEpochMillis = now, note = plan.why) }
            appLog("Improvement", "Noted: ${proposal.summary}")
            getString(Res.string.improvement_manual_noted)
        }
        is ImprovementApplier.Plan.Cannot -> getString(Res.string.improvement_cannot, plan.why)
    }
}

private suspend fun revert(
    proposal: ImprovementProposal,
    store: ImprovementStore,
    host: WorkflowLibraryHost?,
    roles: List<RoleDefinition>,
    saveRole: suspend (String, String) -> Unit,
): String {
    val before = proposal.appliedBefore
    if (before == null) {
        store.decide(proposal.id) { it.copy(status = ProposalStatus.Reverted, decidedAtEpochMillis = currentEpochMillis()) }
        return ""
    }
    val now = currentEpochMillis()
    when (proposal.kind) {
        com.hereliesaz.geministrator.workflow.improvement.ImprovementKind.RoleInstruction -> {
            val current = roles.firstOrNull { it.id.value == proposal.target }?.instructions
            if (!ImprovementApplier.canRevert(proposal, current)) return getString(Res.string.improvement_revert_conflict)
            saveRole(proposal.target, before)
        }
        else -> {
            val hiveId = proposal.target.substringBefore('/')
            val taskId = proposal.target.substringAfter('/', "")
            val hive = host?.entries()?.firstOrNull { it.definition.id.value == hiveId && it.origin == WorkflowLibraryOrigin.Authored }?.definition
                ?: return getString(Res.string.improvement_revert_conflict)
            val current = hive.tasks.firstOrNull { it.id.value == taskId }?.objective
            if (!ImprovementApplier.canRevert(proposal, current)) return getString(Res.string.improvement_revert_conflict)
            host.saveAuthored(hive.copy(tasks = hive.tasks.map { if (it.id.value == taskId) it.copy(objective = before) else it }))
        }
    }
    store.decide(proposal.id) { it.copy(status = ProposalStatus.Reverted, decidedAtEpochMillis = now) }
    appLog("Improvement", "Reverted: ${proposal.summary}")
    return ""
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
    val taskId = "habit-" + proposal.target.filter { it.isLetterOrDigit() }.take(24).ifEmpty { "step" }
    return when (val result = memoryLayer.proposeHabitTask(proposal.sourceRef!!, proposal.target, taskId)) {
        is com.hereliesaz.geministrator.memory.procedural.HabitInvocation.Result.Proposed -> {
            val saved = runCatching { host.addProposedTasks(hive.id, listOf(result.task)) }
            saved.exceptionOrNull()?.let { return it.message ?: getString(Res.string.improvement_habit_no_memory) }
            store.decide(proposal.id) {
                it.copy(status = ProposalStatus.Adopted, decidedAtEpochMillis = currentEpochMillis(), note = "Added to ${hive.name}, behind its own approval step.")
            }
            appLog("Improvement", "Adopted: ${proposal.summary} (into ${hive.id.value})")
            getString(Res.string.improvement_habit_added, hive.name)
        }
        is com.hereliesaz.geministrator.memory.procedural.HabitInvocation.Result.Refused -> result.reason
    }
}

@Composable
private fun ProposalCard(
    proposal: ImprovementProposal,
    hives: List<WorkflowDefinition>,
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
        },
    )
    val eyebrow = status + " · " + source + " · " + proposal.kind.label + " · " + proposal.target + if (proposal.meta) " · " + stringResource(Res.string.improvement_meta) else ""
    AzphaltRecord(
        seed = "improvement-${proposal.id}",
        eyebrow = eyebrow,
        title = proposal.summary,
        body = stringResource(
            Res.string.improvement_body,
            proposal.expectedEffect, proposal.undo, proposal.newText.ifBlank { "-" }, formatLogTime(proposal.createdAtEpochMillis),
        ) + (proposal.evidence.takeIf { it.isNotBlank() }?.let { "\nEvidence: $it" } ?: "") + (proposal.note?.let { "\n$it" } ?: ""),
        well = if (proposal.status != ProposalStatus.Proposed && !(proposal.status == ProposalStatus.Adopted && proposal.appliedBefore != null)) null else {
            {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when (proposal.status) {
                    ProposalStatus.Proposed -> {
                        if (proposal.kind == ImprovementKind.HabitTask) {
                            hives.forEach { hive ->
                                AzphaltPill(
                                    stringResource(Res.string.improvement_add_to_hive, hive.name), "improvement-habit-${proposal.id}-${hive.id.value}",
                                    tone = PillTone.Primary, onClick = { onAddToHive(hive) },
                                )
                            }
                        } else {
                            AzphaltPill(stringResource(Res.string.improvement_adopt), "improvement-adopt-${proposal.id}", tone = PillTone.Primary, onClick = onAdopt)
                        }
                        AzphaltPill(stringResource(Res.string.improvement_dismiss), "improvement-dismiss-${proposal.id}", onClick = onDismiss)
                    }
                    ProposalStatus.Adopted -> if (proposal.appliedBefore != null) {
                        AzphaltPill(stringResource(Res.string.improvement_revert), "improvement-revert-${proposal.id}", onClick = onRevert)
                    }
                    else -> Unit
                }
            }
            }
        },
    )
}
