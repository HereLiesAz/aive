package com.hereliesaz.geministrator.orchestration

import com.hereliesaz.geministrator.domain.ArtifactKind
import com.hereliesaz.geministrator.domain.TaskRunStatus
import com.hereliesaz.geministrator.domain.WorkflowDefinition
import com.hereliesaz.geministrator.domain.WorkflowRun
import kotlinx.serialization.Serializable

/** Bounded local utility roles. These utilities decide routing/context/evidence; they do not solve user work. */
@Serializable
enum class OrchestrationUtilityRole {
    MemoryQueryComposer,
    ContextPacker,
    AgentRouter,
    ToolRouter,
    HandoffComposer,
    EscalationGate,
    CompletionGate,
    ExecutionStateSummarizer,
    VerificationPlanner,
}

@Serializable
enum class MemoryResolution { Category, Summary, Phrase, Entity, Action, GranularEvidence }

@Serializable
data class MemoryQueryInput(
    val objective: String,
    val knownEntities: List<String> = emptyList(),
    val knownActions: List<String> = emptyList(),
    val codeSymbols: List<String> = emptyList(),
    val chronologicalContextRequired: Boolean = false,
    val alreadyRetrievedEvidenceCount: Int = 0,
    val maxQueries: Int = 6,
)

@Serializable
data class MemoryQuerySpec(
    val text: String,
    val resolution: MemoryResolution,
    val reasonCode: String,
)

@Serializable
data class MemoryQueryPlan(
    val queries: List<MemoryQuerySpec>,
    val enoughEvidence: Boolean,
)

@Serializable
data class ContextEvidence(
    val id: String,
    val estimatedTokens: Int,
    val priority: Int = 0,
    val required: Boolean = false,
    /** Evidence sharing the same non-null group is kept or dropped atomically to preserve disagreement. */
    val conflictGroup: String? = null,
)

@Serializable
data class ContextPackingInput(
    val tokenBudget: Int,
    val evidence: List<ContextEvidence>,
)

@Serializable
data class ContextPackingPlan(
    val selectedEvidenceIds: List<String>,
    val totalEstimatedTokens: Int,
    val omittedEvidenceIds: List<String>,
)

@Serializable
data class AgentRouteCandidate(
    val id: String,
    val capabilities: Set<String>,
    val estimatedCost: Double = 0.0,
    val contextLimitTokens: Int = Int.MAX_VALUE,
    val available: Boolean = true,
    /** Lower values preserve explicit/preferred routing order without pretending rank is monetary cost. */
    val preferenceRank: Int = Int.MAX_VALUE,
)

@Serializable
data class AgentRoutingInput(
    val requiredCapabilities: Set<String> = emptySet(),
    val requiredContextTokens: Int = 0,
    val candidates: List<AgentRouteCandidate>,
    val requiredContextType: String = "task",
)

@Serializable
enum class AgentRouteDecision { Local, Escalate }

@Serializable
data class AgentRoute(
    val decision: AgentRouteDecision,
    val selectedAgent: String? = null,
    val fallbackAgent: String? = null,
    val reasonCode: String,
    val requiredContextType: String,
)

@Serializable
data class ToolCapability(
    val id: String,
    val operationClasses: Set<String>,
    val available: Boolean = true,
    /** Lower values preserve executor-integration priority. */
    val preferenceRank: Int = Int.MAX_VALUE,
)

@Serializable
enum class ToolRouteDecision { Tool, NoTool, UnavailableCapability }

@Serializable
data class ToolRoutingInput(
    val operationClass: String? = null,
    val capabilities: List<ToolCapability>,
    val requiredInputs: List<String> = emptyList(),
)

@Serializable
data class ToolRoute(
    val decision: ToolRouteDecision,
    val tool: String? = null,
    val operationClass: String? = null,
    val requiredInputs: List<String> = emptyList(),
    val reasonCode: String,
)

@Serializable
data class HandoffInput(
    val objective: String,
    val completed: List<String> = emptyList(),
    val artifacts: List<String> = emptyList(),
    val state: Map<String, String> = emptyMap(),
    val unresolved: List<String> = emptyList(),
    val failures: List<String> = emptyList(),
    val nextAction: String? = null,
    val acceptanceCriteria: List<String> = emptyList(),
    val provenance: List<String> = emptyList(),
)

@Serializable
data class HandoffPacket(
    val objective: String,
    val completed: List<String>,
    val artifacts: List<String>,
    val state: Map<String, String>,
    val unresolved: List<String>,
    val failures: List<String>,
    val nextAction: String?,
    val acceptanceCriteria: List<String>,
    val provenance: List<String>,
)

@Serializable
data class CapabilityAssessment(
    val malformedInput: Boolean = false,
    val ambiguousObjective: Boolean = false,
    val requiresMultiStepReasoning: Boolean = false,
    val requiresCodebaseWideReasoning: Boolean = false,
    val requiresContradictionReconciliation: Boolean = false,
    val requiresArchitecturalDecision: Boolean = false,
    val requiredContextTokens: Int = 0,
    val localContextLimitTokens: Int = Int.MAX_VALUE,
    val requiredToolAvailable: Boolean = true,
    val hasEnoughEvidence: Boolean = true,
    val recommendedTier: String = "local",
)

@Serializable
enum class EscalationDecision { Local, Escalate, NeedMoreContext, NeedTool }

@Serializable
data class EscalationResult(
    val decision: EscalationDecision,
    val reasonCodes: List<String>,
    val recommendedTier: String,
)

@Serializable
enum class EvidenceStatus { Passed, Failed, Blocked, NotRun }

@Serializable
data class CriterionEvidence(
    val criterion: String,
    val evidenceIds: List<String> = emptyList(),
    val status: EvidenceStatus = EvidenceStatus.NotRun,
)

@Serializable
enum class CompletionDecision { Complete, Incomplete, Blocked, Failed, NeedsVerification }

@Serializable
data class CompletionInput(
    val objective: String,
    val criteria: List<CriterionEvidence>,
    val taskTerminal: Boolean,
)

@Serializable
data class CompletionResult(
    val decision: CompletionDecision,
    val unsatisfiedCriteria: List<String>,
    val evidenceIds: List<String>,
)

@Serializable
data class ExecutionStateSummary(
    val completedSteps: List<String>,
    val activeSteps: List<String>,
    val blockedSteps: List<String>,
    val failedSteps: List<String>,
    val waitingForApprovalSteps: List<String>,
    val artifacts: List<String>,
    val knownConstraints: List<String>,
    val openQuestions: List<String>,
)

@Serializable
data class VerificationPlanningInput(
    val objective: String,
    val acceptanceCriteria: List<String>,
    val artifactKinds: Set<ArtifactKind> = emptySet(),
    val targetPlatforms: List<String> = emptyList(),
)

@Serializable
data class VerificationStep(
    val id: String,
    val operationClass: String,
    val reasonCode: String,
    val criterion: String? = null,
)

@Serializable
data class VerificationPlan(
    val steps: List<VerificationStep>,
)

interface LocalOrchestrationUtilityFamily {
    fun composeMemoryQueries(input: MemoryQueryInput): MemoryQueryPlan
    fun packContext(input: ContextPackingInput): ContextPackingPlan
    fun routeAgent(input: AgentRoutingInput): AgentRoute
    fun routeTool(input: ToolRoutingInput): ToolRoute
    fun composeHandoff(input: HandoffInput): HandoffPacket
    fun evaluateEscalation(input: CapabilityAssessment): EscalationResult
    fun evaluateCompletion(input: CompletionInput): CompletionResult
    fun summarizeExecution(definition: WorkflowDefinition, run: WorkflowRun): ExecutionStateSummary
    fun planVerification(input: VerificationPlanningInput): VerificationPlan
}

/**
 * Conservative executable baseline for every local orchestration utility.
 *
 * A trained specialist can replace any method behind this contract, but the deterministic baseline
 * remains safe: it never invents unavailable agents/tools/evidence, never silently drops one side of
 * a conflict group, and never declares completion without explicit passed evidence for every criterion.
 */
object DeterministicLocalOrchestrationUtilities : LocalOrchestrationUtilityFamily {
    override fun composeMemoryQueries(input: MemoryQueryInput): MemoryQueryPlan {
        require(input.maxQueries >= 1) { "maxQueries must be at least one" }
        val queries = linkedMapOf<String, MemoryQuerySpec>()
        fun add(text: String, resolution: MemoryResolution, reason: String) {
            val clean = text.trim()
            if (clean.isNotEmpty() && queries.size < input.maxQueries) {
                val key = "${resolution.name}:${clean.lowercase()}"
                if (key !in queries) {
                    queries[key] = MemoryQuerySpec(clean, resolution, reason)
                }
            }
        }

        input.codeSymbols.forEach { add(it, MemoryResolution.GranularEvidence, "EXACT_SYMBOL") }
        input.knownEntities.forEach { add(it, MemoryResolution.Entity, "KNOWN_ENTITY") }
        input.knownActions.forEach { add(it, MemoryResolution.Action, "KNOWN_ACTION") }

        if (queries.size < input.maxQueries && input.objective.isNotBlank()) {
            add(input.objective.trim(), MemoryResolution.Summary, "OBJECTIVE_CONTEXT")
        }
        if (input.chronologicalContextRequired && queries.size < input.maxQueries) {
            add(input.objective.trim(), MemoryResolution.GranularEvidence, "CHRONOLOGICAL_EVIDENCE")
        }

        return MemoryQueryPlan(queries.values.toList(), enoughEvidence = false)
    }

    override fun packContext(input: ContextPackingInput): ContextPackingPlan {
        require(input.tokenBudget >= 0) { "tokenBudget must be non-negative" }
        require(input.evidence.all { it.id.isNotBlank() && it.estimatedTokens >= 0 }) {
            "evidence ids must be non-blank and token estimates non-negative"
        }
        require(input.evidence.map { it.id }.distinct().size == input.evidence.size) {
            "evidence ids must be unique"
        }

        val groups = input.evidence.groupBy { it.conflictGroup ?: "__single__:${it.id}" }
        val ordered = groups.values.sortedWith(
            compareByDescending<List<ContextEvidence>> { group -> group.any { it.required } }
                .thenByDescending { group -> group.maxOfOrNull { it.priority } ?: 0 }
                .thenBy { group -> group.sumOf { it.estimatedTokens } },
        )

        val selected = mutableListOf<ContextEvidence>()
        var used = 0
        for (group in ordered) {
            val cost = group.sumOf { it.estimatedTokens }
            val required = group.any { it.required }
            if (used + cost <= input.tokenBudget) {
                selected += group
                used += cost
            } else if (required) {
                error(
                    "Required context group ${group.first().conflictGroup ?: group.first().id} " +
                        "does not fit token budget",
                )
            }
        }
        val selectedIds = selected.map { it.id }.toSet()
        return ContextPackingPlan(
            selectedEvidenceIds = selected.map { it.id },
            totalEstimatedTokens = used,
            omittedEvidenceIds = input.evidence.map { it.id }.filterNot(selectedIds::contains),
        )
    }

    override fun routeAgent(input: AgentRoutingInput): AgentRoute {
        require(input.requiredContextTokens >= 0) { "requiredContextTokens must be non-negative" }
        val eligible = input.candidates
            .filter { candidate ->
                candidate.available &&
                    candidate.contextLimitTokens >= input.requiredContextTokens &&
                    candidate.capabilities.containsAll(input.requiredCapabilities)
            }
            .sortedWith(
                compareBy<AgentRouteCandidate> { it.preferenceRank }
                    .thenBy { it.estimatedCost }
                    .thenBy { it.id },
            )

        if (eligible.isEmpty()) {
            return AgentRoute(
                decision = AgentRouteDecision.Escalate,
                reasonCode = "NO_CAPABLE_LOCAL_AGENT",
                requiredContextType = input.requiredContextType,
            )
        }
        return AgentRoute(
            decision = AgentRouteDecision.Local,
            selectedAgent = eligible.first().id,
            fallbackAgent = eligible.getOrNull(1)?.id,
            reasonCode = "CAPABILITY_MATCH",
            requiredContextType = input.requiredContextType,
        )
    }

    override fun routeTool(input: ToolRoutingInput): ToolRoute {
        val operation = input.operationClass?.trim().orEmpty()
        if (operation.isEmpty()) {
            return ToolRoute(
                decision = ToolRouteDecision.NoTool,
                reasonCode = "NO_TOOL_REQUIRED",
            )
        }
        val tool = input.capabilities
            .filter { it.available && operation in it.operationClasses }
            .minWithOrNull(compareBy<ToolCapability> { it.preferenceRank }.thenBy { it.id })
            ?: return ToolRoute(
                decision = ToolRouteDecision.UnavailableCapability,
                operationClass = operation,
                requiredInputs = input.requiredInputs.distinct(),
                reasonCode = "UNAVAILABLE_CAPABILITY",
            )
        return ToolRoute(
            decision = ToolRouteDecision.Tool,
            tool = tool.id,
            operationClass = operation,
            requiredInputs = input.requiredInputs.distinct(),
            reasonCode = "SUPPORTED_OPERATION",
        )
    }

    override fun composeHandoff(input: HandoffInput): HandoffPacket = HandoffPacket(
        objective = input.objective.trim(),
        completed = input.completed.distinct(),
        artifacts = input.artifacts.distinct(),
        state = input.state,
        unresolved = input.unresolved.distinct(),
        failures = input.failures.distinct(),
        nextAction = input.nextAction?.trim()?.takeIf(String::isNotEmpty),
        acceptanceCriteria = input.acceptanceCriteria.distinct(),
        provenance = input.provenance.distinct(),
    )

    override fun evaluateEscalation(input: CapabilityAssessment): EscalationResult {
        val reasons = mutableListOf<String>()
        if (input.malformedInput) reasons += "MALFORMED_INPUT"
        if (input.requiredContextTokens > input.localContextLimitTokens) reasons += "CONTEXT_LIMIT_EXCEEDED"
        if (!input.hasEnoughEvidence) reasons += "INSUFFICIENT_CONTEXT"
        if (!input.requiredToolAvailable) reasons += "REQUIRED_TOOL_UNAVAILABLE"
        if (input.ambiguousObjective) reasons += "AMBIGUOUS_OBJECTIVE"
        if (input.requiresMultiStepReasoning) reasons += "MULTI_STEP_REASONING"
        if (input.requiresCodebaseWideReasoning) reasons += "CODEBASE_WIDE_REASONING"
        if (input.requiresContradictionReconciliation) reasons += "CONTRADICTION_RECONCILIATION"
        if (input.requiresArchitecturalDecision) reasons += "ARCHITECTURAL_DECISION"

        val decision = when {
            input.malformedInput || input.ambiguousObjective -> EscalationDecision.Escalate
            !input.requiredToolAvailable -> EscalationDecision.NeedTool
            !input.hasEnoughEvidence || input.requiredContextTokens > input.localContextLimitTokens ->
                EscalationDecision.NeedMoreContext
            input.requiresMultiStepReasoning ||
                input.requiresCodebaseWideReasoning ||
                input.requiresContradictionReconciliation ||
                input.requiresArchitecturalDecision -> EscalationDecision.Escalate
            else -> EscalationDecision.Local
        }
        return EscalationResult(
            decision = decision,
            reasonCodes = reasons,
            recommendedTier = if (decision == EscalationDecision.Local) "local" else input.recommendedTier,
        )
    }

    override fun evaluateCompletion(input: CompletionInput): CompletionResult {
        val evidenceIds = input.criteria.flatMap { it.evidenceIds }.distinct()
        val unsatisfied = input.criteria
            .filter { it.status != EvidenceStatus.Passed || it.evidenceIds.isEmpty() }
            .map { it.criterion }

        val decision = when {
            input.criteria.any { it.status == EvidenceStatus.Failed } -> CompletionDecision.Failed
            input.criteria.any { it.status == EvidenceStatus.Blocked } -> CompletionDecision.Blocked
            !input.taskTerminal -> CompletionDecision.Incomplete
            input.criteria.any { it.status == EvidenceStatus.NotRun } -> CompletionDecision.NeedsVerification
            unsatisfied.isNotEmpty() -> CompletionDecision.Incomplete
            else -> CompletionDecision.Complete
        }
        return CompletionResult(decision, unsatisfied, evidenceIds)
    }

    override fun summarizeExecution(
        definition: WorkflowDefinition,
        run: WorkflowRun,
    ): ExecutionStateSummary {
        val byId = definition.tasks.associateBy { it.id }
        val completed = mutableListOf<String>()
        val active = mutableListOf<String>()
        val blocked = mutableListOf<String>()
        val failed = mutableListOf<String>()
        val approvals = mutableListOf<String>()
        val artifacts = mutableListOf<String>()
        val constraints = mutableListOf<String>()
        val questions = mutableListOf<String>()

        run.taskRuns.forEach { (id, taskRun) ->
            val name = byId[id]?.name ?: id.value
            when (taskRun.status) {
                TaskRunStatus.Completed -> completed += name
                TaskRunStatus.Planning,
                TaskRunStatus.Running,
                TaskRunStatus.Verifying,
                TaskRunStatus.Retrying -> active += name
                TaskRunStatus.AwaitingApproval -> approvals += name
                TaskRunStatus.Blocked -> blocked += name
                TaskRunStatus.Failed,
                TaskRunStatus.Escalated,
                TaskRunStatus.Cancelled -> failed += name
                TaskRunStatus.Created,
                TaskRunStatus.Ready -> Unit
            }
            taskRun.artifacts.forEach { artifacts += it.id.value }
            taskRun.blockingReason?.let {
                constraints += "${name}: ${it.code}"
                questions += "${name}: ${it.message}"
            }
        }

        return ExecutionStateSummary(
            completedSteps = completed,
            activeSteps = active,
            blockedSteps = blocked,
            failedSteps = failed,
            waitingForApprovalSteps = approvals,
            artifacts = artifacts.distinct(),
            knownConstraints = constraints.distinct(),
            openQuestions = questions.distinct(),
        )
    }

    override fun planVerification(input: VerificationPlanningInput): VerificationPlan {
        val steps = linkedMapOf<String, VerificationStep>()
        fun add(operation: String, reason: String, criterion: String? = null) {
            val key = "$operation|${criterion.orEmpty()}"
            if (key !in steps) {
                steps[key] = VerificationStep(
                    id = "verify-${steps.size + 1}",
                    operationClass = operation,
                    reasonCode = reason,
                    criterion = criterion,
                )
            }
        }

        val criteria = input.acceptanceCriteria.filter(String::isNotBlank)
        criteria.forEach { criterion ->
            val lower = criterion.lowercase()
            when {
                "lint" in lower -> add("lint", "ACCEPTANCE_CRITERION", criterion)
                "test" in lower -> add("test", "ACCEPTANCE_CRITERION", criterion)
                "build" in lower || "compile" in lower -> add("build", "ACCEPTANCE_CRITERION", criterion)
                "deploy" in lower || "endpoint" in lower || "health" in lower ->
                    add("health-check", "ACCEPTANCE_CRITERION", criterion)
                "source" in lower || "citation" in lower || "date" in lower ->
                    add("source-verification", "ACCEPTANCE_CRITERION", criterion)
                else -> add("evidence-check", "ACCEPTANCE_CRITERION", criterion)
            }
        }

        if (ArtifactKind.CodeChange in input.artifactKinds &&
            steps.values.none { it.operationClass == "build" || it.operationClass == "test" }
        ) {
            add("build", "CODE_ARTIFACT")
            add("test", "CODE_ARTIFACT")
        }
        if (ArtifactKind.Research in input.artifactKinds &&
            steps.values.none { it.operationClass == "source-verification" }
        ) {
            add("source-verification", "RESEARCH_ARTIFACT")
        }
        if (ArtifactKind.Release in input.artifactKinds) {
            add("release-artifact-check", "RELEASE_ARTIFACT")
        }
        input.targetPlatforms.distinct().forEach { platform ->
            add("platform-check:$platform", "TARGET_PLATFORM")
        }

        return VerificationPlan(steps.values.toList())
    }
}
