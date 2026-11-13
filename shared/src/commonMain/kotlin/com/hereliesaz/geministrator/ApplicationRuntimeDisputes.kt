package com.hereliesaz.geministrator

import com.hereliesaz.geministrator.queen.Dispute
import com.hereliesaz.geministrator.queen.DisputeOutcome
import com.hereliesaz.geministrator.queen.DisputeResolution
import com.hereliesaz.geministrator.queen.Disputant
import com.hereliesaz.geministrator.queen.Escalation
import com.hereliesaz.geministrator.queen.EscalationReason
import com.hereliesaz.geministrator.queen.InvestigationLauncher
import com.hereliesaz.geministrator.queen.QueenModel
import com.hereliesaz.geministrator.queen.disputeNotes
import com.hereliesaz.geministrator.queen.settleWithAgreement

/**
 * The one place a dispute between two creatures is raised for the queen to settle. Nothing in the app calls it yet: no code
 * path detects two creatures in dispute today (the Nanny freeze is still settled by its own review), so this is the hook those
 * callers will use. No ruling, research or test starts until both [parties] agree to her stated approach (see
 * [settleWithAgreement]); what she says about it, or about handing it to the keeper, is kept for [runId] and shown in Messages.
 * An agreed investigation is handed to [launcher]; with none, or when it cannot start one, the keeper has it
 * (TODO(queen-investigation-launcher): no launcher exists yet). [mayCall] is where the keeper's spend caps are checked before
 * a model call; the models should already be metered.
 */
internal suspend fun ApplicationRuntime.raiseDispute(
    runId: String,
    dispute: Dispute,
    parties: List<Disputant>,
    queenModel: QueenModel?,
    voices: Map<String, QueenModel>,
    launcher: InvestigationLauncher? = null,
    /** True when this dispute already had its one investigation: she may not commission another. */
    investigated: Boolean = false,
    mayCall: () -> Boolean = { true },
): DisputeResolution {
    var resolution = settleWithAgreement(dispute, parties, queenModel, voices, allowInvestigation = !investigated, mayCall = mayCall)
    var investigationRunId: String? = null
    val agreed = resolution as? DisputeResolution.Agreed
    val request = (agreed?.outcome as? DisputeOutcome.Investigate)?.request
    if (agreed != null && request != null) {
        investigationRunId = try {
            launcher?.launch(request)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
        if (investigationRunId == null) {
            resolution = DisputeResolution.Escalated(Escalation(EscalationReason.CannotInvestigate, agreed.approach, emptyList(), request.missingFact))
        }
    }
    rememberDisputeNotes(runId, disputeNotes(runId, dispute.question, parties, resolution, investigationRunId))
    return resolution
}
