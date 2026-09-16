package com.hereliesaz.geministrator.workflow

import com.hereliesaz.geministrator.domain.ArtifactId
import com.hereliesaz.geministrator.domain.ArtifactKind
import com.hereliesaz.geministrator.domain.ArtifactRef
import com.hereliesaz.geministrator.domain.TaskExecutor
import com.hereliesaz.geministrator.domain.TaskRunStatus
import com.hereliesaz.geministrator.inference.GenealogyConsensusGroup
import com.hereliesaz.geministrator.inference.GenealogyGovernanceFindingKind
import com.hereliesaz.geministrator.inference.GenealogyGovernancePolicy
import com.hereliesaz.geministrator.inference.GenealogyGovernanceRequest
import com.hereliesaz.geministrator.inference.GenealogyGovernanceReport
import com.hereliesaz.geministrator.inference.INFERENCE_INVOCATION_ID_METADATA_KEY
import com.hereliesaz.geministrator.inference.InferenceGenealogyGovernanceRuntime

/**
 * Deterministic system executor for centralized-MoA ancestry gating.
 *
 * It evaluates only structural provenance. It never chooses a candidate, resolves a memory
 * disagreement, or labels a claim true/false. Shared source evidence makes candidate agreement
 * non-independent, but does not prevent synthesis of those candidates; the aggregator and verifier
 * receive that finding and must not treat agreement itself as proof.
 */
class GenealogyGovernanceExecutorIntegration(
    private val governance: InferenceGenealogyGovernanceRuntime,
) : TaskExecutorIntegration {
    override fun supports(executor: TaskExecutor): Boolean =
        executor is TaskExecutor.ExternalService && executor.service == GENEALOGY_GOVERNANCE_SERVICE

    override suspend fun dispatch(context: TaskExecutorContext): TaskExecutorExecution = evaluate(context)

    override suspend fun reconcile(context: TaskExecutorContext): TaskExecutorExecution = evaluate(context)

    private suspend fun evaluate(context: TaskExecutorContext): TaskExecutorExecution {
        val invocationIds = context.task.dependsOn
            .mapNotNull(context.run.taskRuns::get)
            .flatMap { it.artifacts }
            .mapNotNullTo(linkedSetOf()) { artifact ->
                artifact.metadata[INFERENCE_INVOCATION_ID_METADATA_KEY]?.takeIf(String::isNotBlank)
            }

        val report = if (invocationIds.size >= 2) {
            governance.evaluate(
                GenealogyGovernanceRequest(
                    invocationIds = invocationIds,
                    consensusGroups = listOf(
                        GenealogyConsensusGroup(
                            groupId = context.task.id.value,
                            invocationIds = invocationIds,
                        ),
                    ),
                    policy = GenealogyGovernancePolicy(
                        requireEvidence = false,
                        minimumIndependentMembersForConsensus = 2,
                    ),
                ),
            )
        } else {
            GenealogyGovernanceReport(
                invocationIds = invocationIds,
                findings = emptyList(),
                pairwiseIndependence = emptyList(),
            )
        }

        val blockingKinds = setOf(
            GenealogyGovernanceFindingKind.MissingGenealogy,
            GenealogyGovernanceFindingKind.CircularDerivation,
        )
        val blockingFindings = report.findings.filter { it.kind in blockingKinds }
        val lineageComplete = invocationIds.size >= 2
        val gatePassed = lineageComplete && blockingFindings.isEmpty()
        val structurallyIndependent = lineageComplete &&
            report.pairwiseIndependence.isNotEmpty() &&
            report.pairwiseIndependence.all { it.independent }
        val text = report.render(lineageComplete)

        return TaskExecutorExecution(
            status = if (gatePassed) TaskRunStatus.Completed else TaskRunStatus.Failed,
            externalRunId = "genealogy:${context.run.id.value}:${context.task.id.value}",
            artifacts = listOf(
                ArtifactRef(
                    id = ArtifactId("${context.taskRun.id.value}:genealogy-governance:${context.taskRun.attempt}"),
                    kind = ArtifactKind.Verification,
                    taskRunId = context.taskRun.id,
                    label = "Genealogy governance: ${context.task.name}",
                    textContent = text,
                    mediaType = "text/plain",
                    metadata = mapOf(
                        "source" to GENEALOGY_GOVERNANCE_SERVICE,
                        "gatePassed" to gatePassed.toString(),
                        "structurallyIndependent" to structurallyIndependent.toString(),
                        "candidateInvocationCount" to invocationIds.size.toString(),
                    ),
                    createdAtEpochMillis = context.nowEpochMillis,
                ),
            ),
            progress = if (gatePassed) 1f else null,
            progressMessage = if (!lineageComplete) {
                "Genealogy gate requires at least two candidate invocation records"
            } else if (blockingFindings.isNotEmpty()) {
                blockingFindings.joinToString("; ") { it.message }
            } else if (structurallyIndependent) {
                "Candidate genealogy is complete and candidate lineages are structurally independent"
            } else {
                "Candidate genealogy is complete; shared ancestry is recorded as advisory and agreement must not be treated as independent evidence"
            },
        )
    }

    private fun GenealogyGovernanceReport.render(lineageComplete: Boolean): String = buildString {
        appendLine("Genealogy governance report")
        appendLine("Epistemic verdict: none (structural provenance only)")
        appendLine("Candidate invocation count: ${invocationIds.size}")
        appendLine("Lineage complete for MoA gate: $lineageComplete")
        if (pairwiseIndependence.isEmpty()) {
            appendLine("Pairwise independence: unavailable")
        } else {
            appendLine("Pairwise independence:")
            pairwiseIndependence.forEach { assessment ->
                appendLine(
                    "- ${assessment.leftInvocationId} vs ${assessment.rightInvocationId}: " +
                        if (assessment.independent) "independent" else "shared ancestry/evidence",
                )
            }
        }
        if (findings.isEmpty()) {
            appendLine("Findings: none")
        } else {
            appendLine("Findings:")
            findings.forEach { finding -> appendLine("- ${finding.kind}: ${finding.message}") }
        }
    }
}
