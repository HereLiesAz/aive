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
        val specialistId = OrchestrationSpecialistIds.specialistId(role)
        // A specialist that has not been released yet is the normal case: fall back silently.
        if (!library.hasSpecialist(specialistId)) return null
        val plan = try {
            library.plan(
                specialistId = specialistId,
                runtime = runtimeCapabilities,
            )
        } catch (e: Exception) {
            // Released, but nothing this runtime can load: worth a warning, still a fallback.
            println("WARNING: CatalogBackedLocalOrchestrationSpecialistRuntime: " +
                "library.plan failed for specialist '$specialistId': $e")
            return null
        }

        return try {
            executor.generate(role, plan, inputJson)
        } catch (e: Exception) {
            println("WARNING: CatalogBackedLocalOrchestrationSpecialistRuntime: " +
                "executor.generate failed for role '$role' specialist '$specialistId': $e")
            null
        }
    }
}
