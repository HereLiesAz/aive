package com.hereliesaz.geministrator.orchestration

import com.hereliesaz.geministrator.inference.LocalModelLibrary
import com.hereliesaz.geministrator.inference.LocalModelLoadPlan
import com.hereliesaz.geministrator.inference.LocalModelRuntimeCapabilities

object OrchestrationSpecialistIds {
    fun specialistId(role: OrchestrationUtilityRole): String = when (role) {
        OrchestrationUtilityRole.MemoryQueryComposer -> "orchestration:memory-query-composer"
        OrchestrationUtilityRole.ContextPacker -> "orchestration:context-packer"
        OrchestrationUtilityRole.AgentRouter -> "orchestration:agent-router"
        OrchestrationUtilityRole.ToolRouter -> "orchestration:tool-router"
        OrchestrationUtilityRole.HandoffComposer -> "orchestration:handoff-composer"
        OrchestrationUtilityRole.EscalationGate -> "orchestration:escalation-gate"
        OrchestrationUtilityRole.CompletionGate -> "orchestration:completion-gate"
        OrchestrationUtilityRole.ExecutionStateSummarizer -> "orchestration:execution-state-summarizer"
        OrchestrationUtilityRole.VerificationPlanner -> "orchestration:verification-planner"
    }
}

/**
 * Platform executor for one concrete catalog load plan.
 *
 * Android/Desktop implementations own model download/verification/session management. Common code
 * supplies the exact immutable artifact plan and expects JSON matching the utility output contract.
 */
fun interface LocalOrchestrationModelExecutor {
    fun generate(
        role: OrchestrationUtilityRole,
        plan: LocalModelLoadPlan,
        inputJson: String,
    ): String?
}

/**
 * Resolves the released specialist for each utility through the same cryptographically identified
 * [LocalModelLibrary] used by the rest of Aive's local-model infrastructure.
 */
class CatalogBackedLocalOrchestrationSpecialistRuntime(
    private val library: LocalModelLibrary,
    private val runtimeCapabilities: LocalModelRuntimeCapabilities,
    private val executor: LocalOrchestrationModelExecutor,
) : LocalOrchestrationSpecialistRuntime {
    override fun infer(role: OrchestrationUtilityRole, inputJson: String): String? {
        val plan = runCatching {
            library.plan(
                specialistId = OrchestrationSpecialistIds.specialistId(role),
                runtime = runtimeCapabilities,
            )
        }.getOrNull() ?: return null

        return runCatching {
            executor.generate(role, plan, inputJson)
        }.getOrNull()
    }
}
