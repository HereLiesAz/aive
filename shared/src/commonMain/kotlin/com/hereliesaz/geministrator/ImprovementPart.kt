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
import com.hereliesaz.geministrator.workflow.improvement.ImprovementApplier
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
    /** Replaces the instructions of one installed role (the Mind's save path); true when it was saved. */
    onSaveRoleInstructions: suspend (roleId: String, instructions: String) -> Unit,
) {
    val store = LocalImprovementStore.current ?: return
    val host = LocalWorkflowLibraryHost.current
    val schedules = LocalWorkflowScheduleStore.current
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf(ImprovementState()) }
    var roles by remember { mutableStateOf<List<RoleDefinition>>(emptyList()) }
    var hasLoop by remember { mutableStateOf(true) }
    var message by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableStateOf(0) }
    LaunchedEffect(reload) {
        state = store.state()
        roles = host?.installedRoles().orEmpty()
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
                onAdopt = {
                    scope.launch {
                        message = adopt(proposal, store, host, roles, onSaveRoleInstructions)
                        reload += 1
                    }
                },
                onDismiss = {
                    scope.launch {
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

@Composable
private fun ProposalCard(proposal: ImprovementProposal, onAdopt: () -> Unit, onDismiss: () -> Unit, onRevert: () -> Unit) {
    val status = stringResource(
        when (proposal.status) {
            ProposalStatus.Proposed -> Res.string.improvement_status_proposed
            ProposalStatus.Adopted -> Res.string.improvement_status_adopted
            ProposalStatus.Dismissed -> Res.string.improvement_status_dismissed
            ProposalStatus.Reverted -> Res.string.improvement_status_reverted
        },
    )
    val eyebrow = status + " · " + proposal.kind.label + " · " + proposal.target + if (proposal.meta) " · " + stringResource(Res.string.improvement_meta) else ""
    AzphaltRecord(
        seed = "improvement-${proposal.id}",
        eyebrow = eyebrow,
        title = proposal.summary,
        body = stringResource(
            Res.string.improvement_body,
            proposal.expectedEffect, proposal.undo, proposal.newText.ifBlank { "-" }, formatLogTime(proposal.createdAtEpochMillis),
        ),
        well = {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when (proposal.status) {
                    ProposalStatus.Proposed -> {
                        AzphaltPill(stringResource(Res.string.improvement_adopt), "improvement-adopt-${proposal.id}", tone = PillTone.Primary, onClick = onAdopt)
                        AzphaltPill(stringResource(Res.string.improvement_dismiss), "improvement-dismiss-${proposal.id}", onClick = onDismiss)
                    }
                    ProposalStatus.Adopted -> if (proposal.appliedBefore != null) {
                        AzphaltPill(stringResource(Res.string.improvement_revert), "improvement-revert-${proposal.id}", onClick = onRevert)
                    }
                    else -> Unit
                }
            }
        },
    )
}
