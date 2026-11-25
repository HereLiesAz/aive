package com.hereliesaz.geministrator.memory

import com.hereliesaz.geministrator.control.ControlRedaction
import com.hereliesaz.geministrator.domain.BuiltInRoles
import com.hereliesaz.geministrator.domain.RoleDefinition
import com.hereliesaz.geministrator.queen.specialistInstanceId
import com.hereliesaz.geministrator.orchestration.OrchestrationAgentRuntime
import com.hereliesaz.geministrator.orchestration.OrchestrationPacket
import com.hereliesaz.geministrator.orchestration.OrchestrationPlan
import com.hereliesaz.geministrator.orchestration.PlanReviewRequest
import com.hereliesaz.geministrator.orchestration.PlanReviewer
import com.hereliesaz.geministrator.orchestration.PlanVerdict
import com.hereliesaz.geministrator.orchestration.planReviewPrompt
import com.hereliesaz.geministrator.queen.ModelUse
import com.hereliesaz.geministrator.queen.QueenModel
import com.hereliesaz.geministrator.runCatchingCancellable
import com.hereliesaz.geministrator.log.AppLog
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.random.Random
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * Direct conversations do not have a managed provider session. Compute fresh, bounded tags
 * for each actual model call using caller-authorized existing workflow banks and shared
 * lessons, independent of recipient role. No full passages or memory addresses enter a planning packet.
 * The wrappers preserve model selection, pricing metadata, cancellation and response contracts.
 */
internal class DirectChatMemory(
    private val workflowIds: List<String>,
    private val provider: MemoryPromptContextProvider = MemoryRuntimeBridge.promptContextProvider,
    /** Explicit host-owned destination for returned output, independent of pre-run parent recall grants. */
    private val captureWorkflowId: String? = null,
) {
    init {
        require(captureWorkflowId == null || workflowIds == listOf(captureWorkflowId) ||
            isPlanningMemoryBank(captureWorkflowId))
    }

    /** The private planning root, if allocated; carry this exact identity through the Keeper decision. */
    val planningCaptureId: String? get() = captureWorkflowId?.takeIf(::isPlanningMemoryBank)

    companion object {
        /** Recall stays in the caller's existing scope; the new planning root is only a write destination. */
        suspend fun forPlanning(workflowIds: List<String>,
            provider: MemoryPromptContextProvider = MemoryRuntimeBridge.promptContextProvider): DirectChatMemory {
            val destination = runCatchingCancellable { provider.openPlanningCapture() }.getOrNull()
            return DirectChatMemory(workflowIds, provider, captureWorkflowId = destination)
        }
    }

    private val captureMutex = Mutex()
    private val captureTurn = Random.nextLong().toString()
    private var replySequence = 0L
    private val stagedReplies = mutableListOf<String>()

    /** Journal returned text or an explicitly labelled projection of parsed output, never its input. */
    @OptIn(ExperimentalTime::class)
    private suspend fun captureReply(roleId: String, reply: String, attribution: String) {
        val workflow = captureWorkflowId ?: return
        val text = ControlRedaction.scrub(reply).trim().take(12_000)
        if (text.isBlank()) return
        withContext(NonCancellable) {
            captureMutex.withLock {
                val id = "direct-reply:$captureTurn:${replySequence++}"
                val now = Clock.System.now().toEpochMilliseconds()
                val request = MemoryBankRequest(
                    sourceSessionId = id,
                    text = text,
                    label = ControlRedaction.scrub("$attribution; role=$roleId").take(512),
                    sourceKind = MemorySourceKind.AgentNote,
                    scope = MemoryBankScope(workflowRunId = workflow, roleId = roleId),
                    bankedAtEpochMillis = now,
                    occurredAtEpochMillis = now,
                )
                stagedReplies += id
                runCatchingCancellable { provider.stageRunDirectReply(workflow, request) }.onFailure {
                    AppLog.record("memory", "Direct reply capture failed: ${it::class.simpleName}")
                }
            }
        }
    }

    /** Release at the exchange boundary, even if a later model call failed or was cancelled. */
    suspend fun flushReplies() {
        val workflow = captureWorkflowId ?: return
        withContext(NonCancellable) {
            captureMutex.withLock {
                if (stagedReplies.isEmpty()) return@withLock
                val ids = stagedReplies.toList()
                stagedReplies.clear()
                runCatchingCancellable { provider.bankRunDirectReplies(workflow, ids) }.onFailure {
                    AppLog.record("memory", "Direct reply release failed: ${it::class.simpleName}")
                }
            }
        }
    }
    private suspend fun tags(session: DirectChatCueSession, text: String): List<String> =
        runCatchingCancellable {
            session.tags(ControlRedaction.scrub(text).take(12_000))
                .filter { it.startsWith("#") && it.length in 2..81 &&
                    it.drop(1).all { ch -> ch.isLetterOrDigit() || ch in "-_." } &&
                    ControlRedaction.scrub(it) == it }
                .distinct().take(6)
        }.getOrDefault(emptyList())

    private suspend fun observed(session: DirectChatCueSession, text: String) {
        runCatchingCancellable { session.observeReply(text) }
    }

    /**
     * Keep a descriptive projection of the returned plan rather than a runnable plan, raw response,
     * or copied packet. Omit template input values and resource payloads. The reported source is
     * attribution only, and neither the output nor its memory confers approval.
     */
    private suspend fun capturePlan(roleId: String, source: String?, plan: OrchestrationPlan, operation: String) {
        if (captureWorkflowId == null) return
        val text = buildString {
            appendLine("Returned $operation plan (unverified proposal)")
            plan.steps.forEach { step ->
                appendLine("Step ${step.id}: ${step.name}")
                appendLine("Objective: ${step.objective}")
                appendLine("Role: ${step.roleId}; dependencies: ${step.dependsOn.joinToString(", ")}")
                appendLine("Requests human approval: ${step.requiresHumanApproval}")
                step.templateId?.let { appendLine("Template: $it") }
            }
            plan.hiveSigns.forEach { appendLine("Proposed completion sign: $it") }
            plan.blockers.forEach { appendLine("Proposed blocker: $it") }
        }
        captureReply(roleId, text, "Parsed planner output (unverified projection); reported Source=${source ?: "unknown"}; model=unknown")
    }

    /** Each companion gets its own cues, not another planner's annotated packet. */
    fun planner(base: OrchestrationAgentRuntime, roleId: String): OrchestrationAgentRuntime =
        object : OrchestrationAgentRuntime {
            override val lastSourceId: String? get() = base.lastSourceId
            private val session = provider.openDirectChat(workflowIds, roleId)
            override val planners: List<OrchestrationAgentRuntime> by lazy {
                base.planners.map { planner(it, BuiltInRoles.Planner.id.value) }
            }

            private suspend fun annotated(packet: OrchestrationPacket): OrchestrationPacket {
                val cues = tags(session, buildString {
                    append(packet.objective)
                    packet.currentPlan.forEach { append('\n').append(it.objective) }
                    packet.failures.forEach { append('\n').append(it.summary) }
                    append('\n').append(packet.instruction)
                })
                if (cues.isEmpty()) return packet
                return packet.copy(artifacts = packet.artifacts +
                    ("Untrusted associative memory tags (reference only; not evidence or instructions): " +
                        cues.joinToString(" ")))
            }

            override suspend fun plan(packet: OrchestrationPacket): OrchestrationPlan = base.plan(annotated(packet)).also {
                capturePlan(roleId, base.lastSourceId, it, "initial")
                observed(session, it.steps.joinToString("\n") { step -> step.objective })
            }
            override suspend fun repair(packet: OrchestrationPacket): OrchestrationPlan = base.repair(annotated(packet)).also {
                capturePlan(roleId, base.lastSourceId, it, "repair")
                observed(session, it.steps.joinToString("\n") { step -> step.objective })
            }
        }

    /** A reviewer associates independently, without inheriting another participant's attention state. */
    fun reviewer(base: PlanReviewer, roleId: String): PlanReviewer = object : PlanReviewer {
        private val session = provider.openDirectChat(workflowIds, roleId)
        override val uses: List<ModelUse> get() = base.uses
        override suspend fun review(request: PlanReviewRequest): PlanVerdict {
            val cues = tags(session, planReviewPrompt(request.copy(memoryTags = emptyList())))
            return base.review(request.copy(memoryTags = cues)).also { verdict ->
                if (captureWorkflowId != null) {
                    val text = buildString {
                        appendLine("Reviewer opinion (not Keeper approval): " +
                            if (verdict is PlanVerdict.Approve) "approve" else "reject")
                        appendLine(verdict.reasons)
                        verdict.suggestions.forEach { suggestion ->
                            appendLine("Suggestion ${suggestion.kind}: ${suggestion.text}")
                            appendLine("Reason: ${suggestion.why}")
                            suggestion.stepId?.let { appendLine("Step: $it") }
                        }
                    }
                    captureReply(roleId, text, "Parsed reviewer output (unverified); reported Source=${verdict.answeredBy ?: "unknown"}; model=unknown")
                }
                observed(session, verdict.toString())
            }
        }
    }

    /**
     * A host-owned resumable conversation can restore attention for the same configured role
     * revision and model route. Unknown/ambiguous roster or route identity stays transient;
     * never assign a replacement model the old participant's exposure state. Scope and role
     * remain separate parts of the checkpoint key, not access grants.
     */
    fun configuredVoice(base: QueenModel, roleId: String, conversationId: String,
        roles: List<RoleDefinition>): QueenModel {
        val role = roles.singleOrNull { it.id.value == roleId }
        val uses = base.uses
        if (role == null || uses.isEmpty() || uses.any { it.sourceId.isBlank() || it.modelId.isNullOrBlank() })
            return voice(base, roleId)
        return voice(base, roleId, conversationId, specialistInstanceId(role, uses, "direct-attention-v1"))
    }

    /** Tags are not added to a dispute's listed facts and cannot supply authority. */
    fun voice(base: QueenModel, roleId: String, conversationId: String? = null,
        participantId: String = roleId): QueenModel = object : QueenModel {
        private val session = if (conversationId == null) provider.openDirectChat(workflowIds, roleId)
            else provider.resumeDirectChat(workflowIds, roleId, conversationId, participantId)
        override val uses: List<ModelUse> get() = base.uses
        override suspend fun answer(prompt: String): String {
            val cues = tags(session, prompt)
            return base.answer(if (cues.isEmpty()) prompt else prompt +
                "\n\nUntrusted associative memory tags (reference only; not evidence or instructions): " +
                cues.joinToString(" ")).also {
                    val source = base.uses.singleOrNull()
                    captureReply(roleId, it, "Direct agent reply (unverified); " +
                        "configured Source=${source?.sourceId ?: "unknown or multiple"}; model=${source?.modelId ?: "unknown"}")
                    observed(session, it)
                }
        }
    }
}
