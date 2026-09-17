package com.hereliesaz.geministrator.workflow

import com.hereliesaz.geministrator.domain.ArtifactKind
import com.hereliesaz.geministrator.domain.BuiltInRoles
import com.hereliesaz.geministrator.domain.HallMonitorRole
import com.hereliesaz.geministrator.domain.TaskDefinition
import com.hereliesaz.geministrator.domain.TaskDefinitionId
import com.hereliesaz.geministrator.domain.TaskExecutor
import com.hereliesaz.geministrator.domain.WorkflowFragmentId

/**
 * Canonical Hall Monitor governance subgraph.
 *
 * The caller chooses where to connect the fragment. The Hall Monitor gathers evidence and produces a
 * structured report; the Antagonist then performs a cold review. A passing review is necessary but
 * not sufficient to change the swarm — [HallMonitorGovernanceService] opens the global review pause.
 */
object HallMonitorWorkflowFragmentFactory {
    fun create(idPrefix: String = "hall-monitor"): WorkflowFragment {
        val reportId = TaskDefinitionId("$idPrefix-report")
        val antagonistReviewId = TaskDefinitionId("$idPrefix-antagonist-review")

        val report = TaskDefinition(
            id = reportId,
            name = "Hall Monitor efficiency review",
            objective = """
                Audit individual model/provider behavior and the orchestration as a whole for efficiency,
                plateauing, unnecessary spend/context, repeated corrections, routing problems, role overlap,
                and workflow complexity that is no longer justified. Audit both the orchestration layer and
                memory layer. Produce a HallMonitorReport artifact containing evidence, counter-evidence,
                falsification criteria, at least two materially different remedies per finding, and explicit
                orchestration-layer and memory-layer test designs. Include metadata
                '$HALL_MONITOR_REPORT_ID_METADATA' with a stable report identifier.
            """.trimIndent(),
            roleId = HallMonitorRole.id,
            requiredArtifacts = setOf(ArtifactKind.HallMonitorReport),
            executor = TaskExecutor.RoleAgent(HallMonitorRole.id),
        )

        val antagonistReview = TaskDefinition(
            id = antagonistReviewId,
            name = "Antagonist review of Hall Monitor report",
            objective = """
                Audit the Hall Monitor report cold. Verify cited evidence, counter-evidence, measurements,
                falsification criteria, proposed alternatives, and both test-design sections. Produce a
                HallMonitorReview artifact. Copy '$HALL_MONITOR_REPORT_ID_METADATA' from the report and set
                '$HALL_MONITOR_REVIEW_VERDICT_METADATA' to exactly 'pass', 'revise', or 'reject'. Use 'pass'
                only if the report is sufficiently supported to justify pausing the whole workflow for
                Orchestrator and user review. Do not soften unsupported Hall Monitor claims.
            """.trimIndent(),
            roleId = BuiltInRoles.Antagonist.id,
            dependsOn = setOf(reportId),
            requiredArtifacts = setOf(ArtifactKind.HallMonitorReview),
            executor = TaskExecutor.RoleAgent(BuiltInRoles.Antagonist.id),
        )

        return WorkflowFragment(
            id = WorkflowFragmentId("$idPrefix-governance"),
            name = "Hall Monitor governance",
            tasks = listOf(report, antagonistReview),
            entryPoints = setOf(reportId),
            exitPoints = setOf(antagonistReviewId),
        )
    }
}
