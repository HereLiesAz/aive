package com.hereliesaz.geministrator.inference

import com.hereliesaz.geministrator.domain.ArtifactId
import com.hereliesaz.geministrator.domain.TaskRunId
import com.hereliesaz.geministrator.domain.WorkflowRunId

/**
 * Provider-neutral execution topology for model-backed work.
 *
 * The topology is orchestration metadata. It does not change workflow semantics and it does not
 * grant a model authority that its workflow role does not already have.
 */
enum class CompoundInferenceStrategy {
    Single,
    CentralizedMixtureOfAgents,
    ParallelIndependent,
    SequentialPipeline,
    Escalate,
}

/**
 * Direct information ancestry for one model invocation.
 *
 * Genealogy records where the invocation's usable context came from. It deliberately records
 * ancestry without deciding whether any ancestor is true, correct, or preferred. Epistemic
 * judgment remains the responsibility of the active reasoning/orchestration layer.
 */
data class InferenceGenealogy(
    val invocationId: String,
    val upstreamInvocationIds: Set<String> = emptySet(),
    val upstreamTaskRunIds: Set<TaskRunId> = emptySet(),
    val upstreamArtifactIds: Set<ArtifactId> = emptySet(),
    val memoryAddresses: Set<String> = emptySet(),
    val toolEvidenceIds: Set<String> = emptySet(),
    val promptFingerprint: String? = null,
    val configurationFingerprint: String? = null,
) {
    init {
        require(invocationId.isNotBlank()) { "Inference genealogy invocationId must not be blank" }
        require(invocationId !in upstreamInvocationIds) {
            "Inference genealogy cannot list its own invocation as a direct ancestor"
        }
        require(upstreamInvocationIds.none(String::isBlank)) { "Upstream invocation IDs must not be blank" }
        require(memoryAddresses.none(String::isBlank)) { "Memory addresses must not be blank" }
        require(toolEvidenceIds.none(String::isBlank)) { "Tool evidence IDs must not be blank" }
    }
}

/**
 * Compound-inference metadata attached to every provider-backed agent task request.
 *
 * [candidateBudget] and [aggregatorDepth] describe the maximum collaboration shape authorized for
 * this invocation. The initial runtime uses [CompoundInferenceStrategy.Single]; centralized MoA,
 * Skeleton-of-Thought, and other strategies will reuse this contract rather than inventing a
 * second execution path.
 */
data class CompoundInferenceContext(
    val strategy: CompoundInferenceStrategy,
    val genealogy: InferenceGenealogy,
    val candidateBudget: Int,
    val aggregatorDepth: Int,
) {
    init {
        require(candidateBudget >= 1) { "candidateBudget must be at least 1" }
        require(aggregatorDepth >= 0) { "aggregatorDepth must not be negative" }
        if (strategy == CompoundInferenceStrategy.CentralizedMixtureOfAgents) {
            require(candidateBudget >= 2) {
                "Centralized mixture-of-agents requires at least two candidates"
            }
            require(aggregatorDepth >= 1) {
                "Centralized mixture-of-agents requires at least one aggregation layer"
            }
        }
    }

    companion object {
        /**
         * Builds the baseline single-model context used by provider requests today.
         *
         * Dependency artifacts contribute both artifact and producing-task ancestry. A workflow
         * run ID, when available, namespaces the invocation for stable cross-task genealogy.
         */
        fun single(
            taskRunId: TaskRunId,
            workflowRunId: WorkflowRunId? = null,
            upstreamTaskRunIds: Set<TaskRunId> = emptySet(),
            upstreamArtifactIds: Set<ArtifactId> = emptySet(),
        ): CompoundInferenceContext {
            val invocationId = if (workflowRunId == null) {
                "task-run:${taskRunId.value}"
            } else {
                "workflow:${workflowRunId.value}:task-run:${taskRunId.value}"
            }
            return CompoundInferenceContext(
                strategy = CompoundInferenceStrategy.Single,
                genealogy = InferenceGenealogy(
                    invocationId = invocationId,
                    upstreamTaskRunIds = upstreamTaskRunIds,
                    upstreamArtifactIds = upstreamArtifactIds,
                ),
                candidateBudget = 1,
                aggregatorDepth = 0,
            )
        }
    }
}
