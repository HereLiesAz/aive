package com.hereliesaz.geministrator.orchestration

import com.hereliesaz.geministrator.domain.ArtifactKind
import com.hereliesaz.geministrator.domain.TaskRunStatus
import com.hereliesaz.geministrator.domain.WorkflowDefinition
import com.hereliesaz.geministrator.domain.WorkflowRun
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

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
    /**
     * How many stored memories contain each (lowercase) word, when the caller knows. Entries in each
     * list are then queried rarest first; a word no memory holds goes last, since it can match nothing.
     */
    val termDocumentFrequency: Map<String, Int> = emptyMap(),
    /** Words drawn from a first pass's results; non-empty asks for one second-pass query of them. */
    val feedbackTerms: List<String> = emptyList(),
)

@Serializable
data class MemoryQuerySpec(
    val text: String,
    val resolution: MemoryResolution,
    val reasonCode: String,
    /** Synonyms searched alongside [text] at reduced weight (actions only). */
    val expansionTerms: List<String> = emptyList(),
    /** Results are wanted in time order, not by score. */
    val chronological: Boolean = false,
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
    /**
     * The evidence's text, when the caller has it: ungrouped, non-required evidence that repeats a
     * higher-ranked item nearly word for word is skipped. Overlap only; never which copy is right.
     */
    val text: String? = null,
    /** Caller-declared: this item repeats the evidence with this id (treated like a detected repeat). */
    val repeatOf: String? = null,
)

@Serializable
data class ContextPackingInput(
    val tokenBudget: Int,
    val evidence: List<ContextEvidence>,
)

@Serializable
data class ContextPackingPlan(
    /** In prompt order: the most important first, the next most important last, the rest between. */
    val selectedEvidenceIds: List<String>,
    val totalEstimatedTokens: Int,
    val omittedEvidenceIds: List<String>,
    /** Omitted because they nearly repeat a selected item (a subset of [omittedEvidenceIds]). */
    val repeatedEvidenceIds: List<String> = emptyList(),
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
    /** Failures since this agent's last success; [CIRCUIT_OPEN_FAILURES] or more opens its circuit. */
    val consecutiveFailures: Int = 0,
    /** Share of recent runs that succeeded, 0..1; null when unknown (treated as 1). */
    val successRate: Double? = null,
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
    /** Capable agents passed over because their circuit is open. */
    val circuitOpen: List<String> = emptyList(),
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
    /** Where an item came from (item text -> source, e.g. "task-run:42"); kept with the item. */
    val sources: Map<String, String> = emptyMap(),
    /** Size limit for the rendered packet; sections are cut from the least important end to fit. */
    val maxChars: Int? = null,
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
    val sources: Map<String, String> = emptyMap(),
    /** What was cut to fit the size limit, as "section: n dropped". */
    val cuts: List<String> = emptyList(),
) {
    /** Fixed sections, most important first; empty sections are left out. */
    fun render(): String = renderHandoff(this)
}

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
    /** Files the work is expected to touch, when known. */
    val filesTouched: Int = 0,
    /** Attempts already made at this task before this one. */
    val retryCount: Int = 0,
    /** Share of recent local attempts that failed, 0..1. */
    val localFailureRate: Double = 0.0,
    /**
     * The task's objective, for the decision model that fills the judgement flags above
     * ([DecisionInformedOrchestrationUtilities]). Never serialized: no model input carries it.
     */
    @Transient val objective: String = "",
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
    /** The longest chain of unfinished tasks still to run, in order. */
    val criticalPath: List<String> = emptyList(),
    /** Stopped tasks grouped by the pattern of their blocking message (numbers, paths, quotes masked). */
    val failureGroups: Map<String, List<String>> = emptyMap(),
)

@Serializable
data class VerificationPlanningInput(
    val objective: String,
    val acceptanceCriteria: List<String>,
    val artifactKinds: Set<ArtifactKind> = emptySet(),
    val targetPlatforms: List<String> = emptyList(),
    /** Repository paths the change touched, when known; tests are picked from them. */
    val changedFiles: List<String> = emptyList(),
)

@Serializable
data class VerificationStep(
    val id: String,
    val operationClass: String,
    val reasonCode: String,
    val criterion: String? = null,
    /** The platform this step runs on, when it runs once per platform. */
    val platform: String? = null,
    /** Test classes or files to run, when picked from changed files. */
    val targets: List<String> = emptyList(),
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
        fun add(spec: MemoryQuerySpec) {
            val clean = spec.text.trim()
            if (clean.isNotEmpty() && queries.size < input.maxQueries) {
                val key = "${spec.resolution.name}:${clean.lowercase()}"
                if (key !in queries) queries[key] = spec.copy(text = clean)
            }
        }

        // The objective always gets a query (and the chronological and second-pass ones, when
        // asked): one list of symbols, entities or actions can no longer use up every slot.
        val objective = input.objective.trim()
        val feedback = feedbackQueryText(input)
        val chronological = input.chronologicalContextRequired && objective.isNotEmpty()
        val reserved = (if (objective.isNotEmpty()) 1 else 0) + (if (chronological) 1 else 0) + (if (feedback != null) 1 else 0)
        val df = input.termDocumentFrequency
        val sources = listOf(
            rarestFirst(input.codeSymbols, df).map { MemoryQuerySpec(it, MemoryResolution.GranularEvidence, "EXACT_SYMBOL") },
            rarestFirst(input.knownEntities, df).map { MemoryQuerySpec(it, MemoryResolution.Entity, "KNOWN_ENTITY") },
            rarestFirst(input.knownActions, df).map {
                MemoryQuerySpec(it, MemoryResolution.Action, "KNOWN_ACTION", expansionTerms = actionSynonyms(it))
            },
        )
        // Round-robin across the three lists until only the reserved slots remain.
        val longest = sources.maxOf { it.size }
        for (i in 0 until longest) {
            sources.forEach { list ->
                if (queries.size < input.maxQueries - reserved) list.getOrNull(i)?.let(::add)
            }
        }
        if (objective.isNotEmpty()) add(MemoryQuerySpec(objective, MemoryResolution.Summary, "OBJECTIVE_CONTEXT"))
        if (chronological) add(MemoryQuerySpec(objective, MemoryResolution.GranularEvidence, "CHRONOLOGICAL_EVIDENCE", chronological = true))
        feedback?.let { add(MemoryQuerySpec(it, MemoryResolution.Phrase, "RESULT_FEEDBACK")) }

        return MemoryQueryPlan(queries.values.toList(), enoughEvidence = false)
    }

    override fun packContext(input: ContextPackingInput): ContextPackingPlan {
        val packing = contextPacking(input)
        val selected = packing.promptOrder
        val selectedIds = selected.toSet()
        return ContextPackingPlan(
            selectedEvidenceIds = selected,
            totalEstimatedTokens = packing.tokensUsed,
            omittedEvidenceIds = input.evidence.map { it.id }.filterNot(selectedIds::contains),
            repeatedEvidenceIds = packing.repeatOf.keys.filterNot(selectedIds::contains),
        )
    }

    override fun routeAgent(input: AgentRoutingInput): AgentRoute {
        require(input.requiredContextTokens >= 0) { "requiredContextTokens must be non-negative" }
        val routing = agentRouting(input)
        val eligible = routing.eligible
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
            reasonCode = if (routing.halfOpen) "CIRCUIT_HALF_OPEN" else "CAPABILITY_MATCH",
            requiredContextType = input.requiredContextType,
            circuitOpen = routing.open.map { it.id }.filterNot { it == selected.id },
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

    override fun composeHandoff(input: HandoffInput): HandoffPacket {
        val nextAction = input.nextAction?.trim()?.takeIf(String::isNotEmpty)
        val full = HandoffPacket(
            objective = input.objective.trim(),
            completed = input.completed.distinct(),
            artifacts = input.artifacts.distinct(),
            state = input.state,
            unresolved = input.unresolved.distinct(),
            failures = input.failures.distinct(),
            nextAction = nextAction,
            acceptanceCriteria = input.acceptanceCriteria.distinct(),
            provenance = input.provenance.distinct(),
            warnings = buildList {
                if (nextAction == null && (input.unresolved.isNotEmpty() || input.failures.isNotEmpty())) add("NEXT_ACTION_MISSING")
                if (input.objective.isBlank()) add("OBJECTIVE_MISSING")
            },
            sources = input.sources,
        )
        return fitHandoff(full, input.maxChars)
    }

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
        if (input.filesTouched >= BROAD_CHANGE_FILES) reasons += "BROAD_CHANGE"
        if (input.retryCount >= REPEATED_RETRIES) reasons += "REPEATED_RETRIES"
        if (input.localFailureRate >= HIGH_FAILURE_RATE) reasons += "HIGH_LOCAL_FAILURE_RATE"

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
            criticalPath = criticalPath(definition, run),
            failureGroups = failureGroups(definition, run),
        )
    }

    override fun planVerification(input: VerificationPlanningInput): VerificationPlan =
        planVerificationWith(input, ::verificationOperationsFor)

    /** [planVerification] with the criterion classifier supplied ([DecisionInformedOrchestrationUtilities]). */
    internal fun planVerificationWith(
        input: VerificationPlanningInput,
        operationsFor: (String) -> List<String>,
    ): VerificationPlan {
        val steps = linkedMapOf<String, VerificationStep>()
        fun add(operation: String, reason: String, criterion: String? = null, platform: String? = null, targets: List<String> = emptyList()) {
            val key = verificationStepKey(operation, criterion, platform, targets)
            if (key !in steps) {
                steps[key] = VerificationStep(
                    id = "verify-${steps.size + 1}",
                    operationClass = operation,
                    reasonCode = reason,
                    criterion = criterion,
                    platform = platform,
                    targets = targets,
                )
            }
        }

        val criteria = input.acceptanceCriteria.filter(String::isNotBlank)
        criteria.forEach { criterion ->
            operationsFor(criterion).forEach { add(it, "ACCEPTANCE_CRITERION", criterion) }
        }

        val platforms = input.targetPlatforms.map(String::trim).filter(String::isNotEmpty).distinct()
        val code = ArtifactKind.CodeChange in input.artifactKinds
        if (code && platforms.isNotEmpty()) {
            // Build and test once on each platform the change targets.
            platforms.forEach { platform ->
                add("build", "TARGET_PLATFORM", platform = platform)
                add("test", "TARGET_PLATFORM", platform = platform)
            }
        } else if (code && steps.values.none { it.operationClass == "build" || it.operationClass == "test" }) {
            add("build", "CODE_ARTIFACT")
            add("test", "CODE_ARTIFACT")
        }
        testTargetsFor(input.changedFiles).takeIf { it.isNotEmpty() }?.let { add("test", "CHANGED_FILES", targets = it) }
        if (ArtifactKind.Research in input.artifactKinds &&
            steps.values.none { it.operationClass == "source-verification" }
        ) {
            add("source-verification", "RESEARCH_ARTIFACT")
        }
        if (ArtifactKind.Release in input.artifactKinds) {
            add("release-artifact-check", "RELEASE_ARTIFACT")
        }
        if (!code) platforms.forEach { platform -> add("platform-check:$platform", "TARGET_PLATFORM") }

        return VerificationPlan(steps.values.toList())
    }
}

/**
 * Escalation points from the caller's reasoning flags and cheap signals; [ESCALATION_THRESHOLD] or
 * more escalates. Flags: multi-step 1, codebase-wide 2, architectural 3, contradiction 3. Signals:
 * [BROAD_CHANGE_FILES] files touched 1 ([VERY_BROAD_CHANGE_FILES] 2), [REPEATED_RETRIES] retries 1,
 * a local failure rate of [HIGH_FAILURE_RATE] or more 1.
 */
internal fun reasoningLoad(input: CapabilityAssessment): Int =
    (if (input.requiresMultiStepReasoning) 1 else 0) +
        (if (input.requiresCodebaseWideReasoning) 2 else 0) +
        (if (input.requiresArchitecturalDecision) 3 else 0) +
        (if (input.requiresContradictionReconciliation) 3 else 0) +
        (if (input.filesTouched >= VERY_BROAD_CHANGE_FILES) 2 else if (input.filesTouched >= BROAD_CHANGE_FILES) 1 else 0) +
        (if (input.retryCount >= REPEATED_RETRIES) 1 else 0) +
        (if (input.localFailureRate >= HIGH_FAILURE_RATE) 1 else 0)

internal const val ESCALATION_THRESHOLD = 3
internal const val BROAD_CHANGE_FILES = 20
internal const val VERY_BROAD_CHANGE_FILES = 50
internal const val REPEATED_RETRIES = 2
internal const val HIGH_FAILURE_RATE = 0.5

/** Failures in a row that open an agent's circuit; it is tried again only when every agent is open. */
const val CIRCUIT_OPEN_FAILURES: Int = 3

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
    val operations = CRITERION_PARTS.split(criterion).mapNotNull(::lexiconOperationFor).distinct()
    return operations.ifEmpty { listOf("evidence-check") }
}

/** The lexicon's operation for one part of a criterion, or null when no word calls for one. */
internal fun lexiconOperationFor(part: String): String? {
    val words = VERIFICATION_WORD.findAll(part.lowercase()).map { verificationStem(it.value) }.toSet()
    return VERIFICATION_LEXICON.firstOrNull { (_, stems) -> stems.any(words::contains) }?.first
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
internal val CRITERION_PARTS = Regex("\\s+and\\s+|[,;]\\s*|\\s+&\\s+")
internal val BEHAVIOUR_SPEC = Regex("\\b(?:given\\b.*\\bwhen\\b.*\\bthen|the system shall|shall)\\b", RegexOption.IGNORE_CASE)
