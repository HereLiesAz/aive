package com.hereliesaz.geministrator

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.hereliesaz.geministrator.domain.RoleDefinition
import com.hereliesaz.geministrator.domain.RoleDefinitionId
import com.hereliesaz.geministrator.memory.procedural.RoleInstructionApproval
import com.hereliesaz.geministrator.memory.procedural.RoleInstructionEvidenceKind
import com.hereliesaz.geministrator.memory.procedural.RoleInstructionProposalLedger
import com.hereliesaz.geministrator.memory.procedural.RoleObservation
import com.hereliesaz.geministrator.memory.procedural.RoleObservationKind
import com.hereliesaz.geministrator.resources.Res
import com.hereliesaz.geministrator.resources.mind_proposal_approve
import com.hereliesaz.geministrator.resources.mind_proposal_diff
import com.hereliesaz.geministrator.resources.mind_proposal_dismiss
import com.hereliesaz.geministrator.resources.mind_proposal_evidence
import com.hereliesaz.geministrator.resources.mind_proposal_evidence_failure
import com.hereliesaz.geministrator.resources.mind_proposal_evidence_fix
import com.hereliesaz.geministrator.resources.mind_proposal_evidence_keeper
import com.hereliesaz.geministrator.resources.mind_proposal_kind_failures_then_fix
import com.hereliesaz.geministrator.resources.mind_proposal_kind_keeper_correction
import com.hereliesaz.geministrator.resources.mind_proposal_kind_repeated_failure
import com.hereliesaz.geministrator.resources.mind_proposal_stale
import com.hereliesaz.geministrator.resources.mind_proposals_hint
import com.hereliesaz.geministrator.resources.mind_proposals_title
import org.jetbrains.compose.resources.stringResource

/** The app's one queue of proposed role-instruction edits, kept with the other durable UI state. */
internal object RoleInstructionProposalsHost {
    private const val KEY = "procedural.role-instruction-proposals.v1"

    val ledger: RoleInstructionProposalLedger by lazy {
        RoleInstructionProposalLedger(
            load = { DurableUiState.store.getString(KEY) },
            save = { DurableUiState.store.putString(KEY, it) },
        )
    }
}

/** How many proposals wait on the keeper for [roleId]; shown on the creature's Mind tab. */
@Composable
internal fun pendingRoleInstructionProposals(roleId: RoleDefinitionId?): Int {
    val state by RoleInstructionProposalsHost.ledger.state.collectAsState()
    return roleId?.let { state.pendingFor(it).size } ?: 0
}

/**
 * The Mind tab's queue (docs/Keepers-Room.md, docs/architecture/PROCEDURAL_MEMORY.md §10): each
 * proposed edit of the role's instructions as current -> proposed text with the evidence behind it.
 * Approve saves through the Mind's own save path ([onSaveRoleCollection] with [roles], the same
 * collection its form saves); Dismiss changes nothing. Nothing is applied without Approve.
 */
@Composable
internal fun RoleInstructionProposalPanel(
    roleId: RoleDefinitionId,
    roles: List<RoleDefinition>,
    onSaveRoleCollection: (List<RoleDefinition>) -> Unit,
    modifier: Modifier = Modifier,
) {
    val ledger = RoleInstructionProposalsHost.ledger
    val state by ledger.state.collectAsState()
    val pending = state.pendingFor(roleId)
    var stale by remember(roleId) { mutableStateOf(false) }
    if (pending.isEmpty() && !stale) return
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(stringResource(Res.string.mind_proposals_title), style = AzphaltType.eyebrow, color = azphaltOnSurface())
        Text(stringResource(Res.string.mind_proposals_hint), style = AzphaltType.body, color = azphaltOnSurface())
        if (stale) Text(stringResource(Res.string.mind_proposal_stale), style = AzphaltType.body, color = azphaltOnSurface())
        pending.forEach { proposal ->
            Text(stringResource(Res.string.mind_proposal_diff), style = AzphaltType.eyebrow, color = azphaltOnSurface())
            SelectionContainer {
                Text(
                    proposal.diff(),
                    style = AzphaltType.body.copy(fontFamily = FontFamily.Monospace),
                    color = azphaltOnSurface(),
                )
            }
            Text(stringResource(Res.string.mind_proposal_evidence), style = AzphaltType.eyebrow, color = azphaltOnSurface())
            proposal.changes.forEach { change ->
                Text(change.kind.label(), style = AzphaltType.eyebrow, color = azphaltOnSurface())
                change.evidence.forEach { observation ->
                    Text("· " + observation.describe(), style = AzphaltType.body, color = azphaltOnSurface())
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AzphaltPill(
                    label = stringResource(Res.string.mind_proposal_approve),
                    seed = "proposal-approve-${proposal.id}",
                    tone = PillTone.Primary,
                    onClick = {
                        when (val result = ledger.approve(proposal.id, roles)) {
                            is RoleInstructionApproval.Applied -> {
                                stale = false
                                onSaveRoleCollection(result.roles)
                            }
                            RoleInstructionApproval.Stale -> stale = true
                            RoleInstructionApproval.NotFound -> Unit
                        }
                    },
                )
                AzphaltPill(
                    label = stringResource(Res.string.mind_proposal_dismiss),
                    seed = "proposal-dismiss-${proposal.id}",
                    onClick = { ledger.dismiss(proposal.id) },
                )
            }
        }
    }
}

@Composable
private fun RoleInstructionEvidenceKind.label(): String = stringResource(
    when (this) {
        RoleInstructionEvidenceKind.RepeatedFailure -> Res.string.mind_proposal_kind_repeated_failure
        RoleInstructionEvidenceKind.FailuresThenFix -> Res.string.mind_proposal_kind_failures_then_fix
        RoleInstructionEvidenceKind.KeeperCorrection -> Res.string.mind_proposal_kind_keeper_correction
    },
)

@Composable
private fun RoleObservation.describe(): String {
    val task = taskDefinitionId ?: "—"
    return when (kind) {
        RoleObservationKind.Failure ->
            stringResource(Res.string.mind_proposal_evidence_failure, workflowRunId, task, attempt, detail)
        RoleObservationKind.Fix -> stringResource(Res.string.mind_proposal_evidence_fix, workflowRunId, task)
        RoleObservationKind.KeeperMessage ->
            stringResource(Res.string.mind_proposal_evidence_keeper, workflowRunId, task, detail)
    }
}
