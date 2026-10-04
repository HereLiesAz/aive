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
    /** Inputs this tool cannot run without. */
    val requiredInputs: Set<String> = emptySet(),
)

@Serializable
enum class ToolRouteDecision { Tool, NoTool, UnavailableCapability, MissingInputs }

@Serializable
data class ToolRoutingInput(
    val operationClass: String? = null,
    val capabilities: List<ToolCapability>,
    val requiredInputs: List<String> = emptyList(),
    /** Inputs the caller can supply; null when unknown, which skips the input check. */
    val providedInputs: List<String>? = null,
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
    /** Structural gaps in the handoff, e.g. NEXT_ACTION_MISSING. */
    val warnings: List<String> = emptyList(),
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
    /** Optional criteria never block completion; their gaps are reported only. */
    val required: Boolean = true,
    /** The evidence predates the latest change it should cover (a timestamp check by the caller). */
    val stale: Boolean = false,
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
    /** Why the decision is what it is, e.g. NO_CRITERIA, STALE_EVIDENCE, OPTIONAL_UNSATISFIED. */
    val reasonCodes: List<String> = emptyList(),
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
    /** Defined tasks that have not started (no run, Created or Ready). */
    val pendingSteps: List<String> = emptyList(),
    val escalatedSteps: List<String> = emptyList(),
    val cancelledSteps: List<String> = emptyList(),
    /** Not-yet-finished task -> the nearest failed, blocked or approval-waiting task it depends on. */
    val blockedBy: Map<String, String> = emptyMap(),
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

        // The objective always gets a query (and the chronological one, when asked): one list of
        // symbols, entities or actions can no longer use up every slot.
        val objective = input.objective.trim()
        val reserved = (if (objective.isNotEmpty()) 1 else 0) + (if (input.chronologicalContextRequired && objective.isNotEmpty()) 1 else 0)
        val sources = listOf(
            input.codeSymbols.map { Triple(it, MemoryResolution.GranularEvidence, "EXACT_SYMBOL") },
            input.knownEntities.map { Triple(it, MemoryResolution.Entity, "KNOWN_ENTITY") },
            input.knownActions.map { Triple(it, MemoryResolution.Action, "KNOWN_ACTION") },
        )
        // Round-robin across the three lists until only the reserved slots remain.
        val longest = sources.maxOf { it.size }
        for (i in 0 until longest) {
            sources.forEach { list ->
                if (queries.size < input.maxQueries - reserved) list.getOrNull(i)?.let { (text, resolution, reason) -> add(text, resolution, reason) }
            }
        }
        if (objective.isNotEmpty()) add(objective, MemoryResolution.Summary, "OBJECTIVE_CONTEXT")
        if (input.chronologicalContextRequired && objective.isNotEmpty()) {
            add(objective, MemoryResolution.GranularEvidence, "CHRONOLOGICAL_EVIDENCE")
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
        // The fallback is the next eligible agent that is genuinely stronger (more context or a
        // capability superset), so a failure does not retry an equivalent agent; else the next one.
        val selected = eligible.first()
        val fallback = eligible.drop(1).firstOrNull { candidate ->
            candidate.contextLimitTokens > selected.contextLimitTokens ||
                (candidate.capabilities.containsAll(selected.capabilities) && candidate.capabilities.size > selected.capabilities.size)
        } ?: eligible.getOrNull(1)
        return AgentRoute(
            decision = AgentRouteDecision.Local,
            selectedAgent = selected.id,
            fallbackAgent = fallback?.id,
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
        val supporting = input.capabilities
            .filter { it.available && operation in it.operationClasses }
            .sortedWith(compareBy<ToolCapability> { it.preferenceRank }.thenBy { it.id })
        val provided = input.providedInputs?.toSet()
        val runnable = if (provided == null) supporting else supporting.filter { provided.containsAll(it.requiredInputs) }
        if (supporting.isNotEmpty() && runnable.isEmpty()) {
            return ToolRoute(
                decision = ToolRouteDecision.MissingInputs,
                tool = supporting.first().id,
                operationClass = operation,
                requiredInputs = (supporting.first().requiredInputs - provided.orEmpty()).sorted(),
                reasonCode = "MISSING_REQUIRED_INPUTS",
            )
        }
        val tool = runnable.firstOrNull()
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
        warnings = buildList {
            val nextAction = input.nextAction?.trim().orEmpty()
            if (nextAction.isEmpty() && (input.unresolved.isNotEmpty() || input.failures.isNotEmpty())) add("NEXT_ACTION_MISSING")
            if (input.objective.isBlank()) add("OBJECTIVE_MISSING")
        },
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
            reasoningLoad(input) >= ESCALATION_THRESHOLD -> EscalationDecision.Escalate
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
            .filter { it.status != EvidenceStatus.Passed || it.evidenceIds.isEmpty() || it.stale }
            .map { it.criterion }
        val required = input.criteria.filter { it.required }
        val reasons = mutableListOf<String>()

        val decision = when {
            required.any { it.status == EvidenceStatus.Failed } -> CompletionDecision.Failed.also { reasons += "CRITERION_FAILED" }
            required.any { it.status == EvidenceStatus.Blocked } -> CompletionDecision.Blocked.also { reasons += "CRITERION_BLOCKED" }
            !input.taskTerminal -> CompletionDecision.Incomplete.also { reasons += "TASK_NOT_TERMINAL" }
            // Nothing to check against is not completion.
            required.isEmpty() -> CompletionDecision.NeedsVerification.also { reasons += "NO_CRITERIA" }
            required.any { it.status == EvidenceStatus.NotRun } -> CompletionDecision.NeedsVerification.also { reasons += "CRITERION_NOT_RUN" }
            required.any { it.status == EvidenceStatus.Passed && it.stale } -> CompletionDecision.NeedsVerification.also { reasons += "STALE_EVIDENCE" }
            required.any { it.evidenceIds.isEmpty() } -> CompletionDecision.Incomplete.also { reasons += "MISSING_EVIDENCE" }
            else -> CompletionDecision.Complete
        }
        if (input.criteria.any { !it.required && it.criterion in unsatisfied }) reasons += "OPTIONAL_UNSATISFIED"
        return CompletionResult(decision, unsatisfied, evidenceIds, reasons)
    }

    override fun summarizeExecution(
        definition: WorkflowDefinition,
        run: WorkflowRun,
    ): ExecutionStateSummary {
        val completed = mutableListOf<String>()
        val active = mutableListOf<String>()
        val blocked = mutableListOf<String>()
        val failed = mutableListOf<String>()
        val escalated = mutableListOf<String>()
        val cancelled = mutableListOf<String>()
        val pending = mutableListOf<String>()
        val approvals = mutableListOf<String>()
        val artifacts = mutableListOf<String>()
        val constraints = mutableListOf<String>()
        val questions = mutableListOf<String>()

        // Every defined task, in dependency order (definition order breaks ties); runs for tasks
        // missing from the definition follow.
        val ordered = executionOrder(definition) + run.taskRuns.keys.filter { id -> definition.tasks.none { it.id == id } }
        val names = definition.tasks.associate { it.id to it.name }
        fun nameOf(id: com.hereliesaz.geministrator.domain.TaskDefinitionId) = names[id] ?: id.value
        ordered.forEach { id ->
            val name = nameOf(id)
            val taskRun = run.taskRuns[id]
            when (taskRun?.status) {
                null, TaskRunStatus.Created, TaskRunStatus.Ready -> pending += name
                TaskRunStatus.Completed -> completed += name
                TaskRunStatus.Planning,
                TaskRunStatus.Running,
                TaskRunStatus.Verifying,
                TaskRunStatus.Retrying -> active += name
                TaskRunStatus.AwaitingApproval -> approvals += name
                TaskRunStatus.Blocked -> blocked += name
                TaskRunStatus.Failed -> failed += name
                TaskRunStatus.Escalated -> escalated += name
                TaskRunStatus.Cancelled -> cancelled += name
            }
            taskRun?.artifacts?.forEach { artifacts += it.id.value }
            taskRun?.blockingReason?.let {
                constraints += "${name}: ${it.code}"
                if (it.message.isNotBlank()) questions += "${name}: ${it.message}"
            }
        }

        // Root cause of waiting: walk dependencies back to the nearest task that stopped.
        val stopped = setOf(TaskRunStatus.Failed, TaskRunStatus.Blocked, TaskRunStatus.AwaitingApproval, TaskRunStatus.Escalated, TaskRunStatus.Cancelled)
        val dependencies = definition.tasks.associate { it.id to it.dependsOn }
        val blockedBy = linkedMapOf<String, String>()
        ordered.forEach { id ->
            val status = run.taskRuns[id]?.status
            if (status == TaskRunStatus.Completed || status in stopped) return@forEach
            val seen = hashSetOf(id)
            var frontier = dependencies[id].orEmpty().toList()
            while (frontier.isNotEmpty()) {
                val root = frontier.firstOrNull { run.taskRuns[it]?.status in stopped }
                if (root != null) {
                    blockedBy[nameOf(id)] = nameOf(root)
                    break
                }
                frontier = frontier.flatMap { dependencies[it].orEmpty() }.filter(seen::add)
            }
        }

        return ExecutionStateSummary(
            completedSteps = completed,
            activeSteps = active,
            blockedSteps = blocked,
            // Kept as "did not succeed" for existing readers; escalated and cancelled are also listed apart.
            failedSteps = failed + escalated + cancelled,
            waitingForApprovalSteps = approvals,
            artifacts = artifacts.distinct(),
            knownConstraints = constraints.distinct(),
            openQuestions = questions.distinct(),
            pendingSteps = pending,
            escalatedSteps = escalated,
            cancelledSteps = cancelled,
            blockedBy = blockedBy,
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
            verificationOperationsFor(criterion).forEach { add(it, "ACCEPTANCE_CRITERION", criterion) }
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

/** Escalation points from the caller's reasoning flags; [ESCALATION_THRESHOLD] or more escalates. */
internal fun reasoningLoad(input: CapabilityAssessment): Int =
    (if (input.requiresMultiStepReasoning) 1 else 0) +
        (if (input.requiresCodebaseWideReasoning) 2 else 0) +
        (if (input.requiresArchitecturalDecision) 3 else 0) +
        (if (input.requiresContradictionReconciliation) 3 else 0)

internal const val ESCALATION_THRESHOLD = 3

/** Task ids in dependency order (Kahn's algorithm; definition order breaks ties; cycles appended). */
internal fun executionOrder(definition: WorkflowDefinition): List<com.hereliesaz.geministrator.domain.TaskDefinitionId> {
    val ids = definition.tasks.map { it.id }
    val known = ids.toSet()
    val remaining = definition.tasks.associate { it.id to it.dependsOn.filter(known::contains).toMutableSet() }.toMutableMap()
    val out = mutableListOf<com.hereliesaz.geministrator.domain.TaskDefinitionId>()
    while (remaining.isNotEmpty()) {
        val ready = ids.firstOrNull { it in remaining && remaining.getValue(it).isEmpty() }
        if (ready == null) {
            out += ids.filter { it in remaining }
            break
        }
        out += ready
        remaining.remove(ready)
        remaining.values.forEach { it.remove(ready) }
    }
    return out
}

/**
 * The verification operations an acceptance criterion's wording calls for. Matched on whole words
 * and base forms ("latest" is not a test, "update" is not a date), across each part of a
 * compound criterion ("lint and test" plans both). Given/When/Then and "shall" criteria are tests.
 */
internal fun verificationOperationsFor(criterion: String): List<String> {
    if (BEHAVIOUR_SPEC.containsMatchIn(criterion)) return listOf("test")
    val operations = CRITERION_PARTS.split(criterion).flatMap { part ->
        val words = VERIFICATION_WORD.findAll(part.lowercase()).map { verificationStem(it.value) }.toSet()
        VERIFICATION_LEXICON.filter { (_, stems) -> stems.any(words::contains) }.map { it.first }.take(1)
    }.distinct()
    return operations.ifEmpty { listOf("evidence-check") }
}

/** First matching operation; kept for single-operation callers. */
internal fun verificationOperationFor(criterion: String): String = verificationOperationsFor(criterion).first()

private fun verificationStem(word: String): String = when {
    word.endsWith("ing") && word.length > 5 -> word.dropLast(3)
    word.endsWith("ed") && word.length > 4 -> word.dropLast(2)
    word.endsWith("es") && word.length > 4 -> word.dropLast(2)
    word.endsWith("s") && word.length > 3 -> word.dropLast(1)
    else -> word
}

/** Operation -> stems that call for it, in precedence order. */
private val VERIFICATION_LEXICON: List<Pair<String, Set<String>>> = listOf(
    "lint" to setOf("lint", "linter", "format", "formatt", "style", "detekt", "ktlint", "eslint"),
    "test" to setOf("test", "pass", "assert", "spec", "coverage", "junit", "pytest", "regression"),
    "build" to setOf("build", "built", "compile", "compil", "assemble", "assembl"),
    "health-check" to setOf("deploy", "endpoint", "health", "healthy", "respond", "serv", "uptime", "reachable"),
    "source-verification" to setOf("source", "citation", "cite", "cit", "reference", "dated", "publication"),
)
private val VERIFICATION_WORD = Regex("[a-z][a-z0-9]+")
private val CRITERION_PARTS = Regex("\\s+and\\s+|[,;]\\s*|\\s+&\\s+")
private val BEHAVIOUR_SPEC = Regex("\\b(?:given\\b.*\\bwhen\\b.*\\bthen|the system shall|shall)\\b", RegexOption.IGNORE_CASE)
