package com.hereliesaz.geministrator.providers.llm

import com.hereliesaz.geministrator.orchestration.OrchestrationAgentRuntime
import com.hereliesaz.geministrator.orchestration.OrchestrationPacket
import com.hereliesaz.geministrator.orchestration.OrchestrationPlan
import com.hereliesaz.geministrator.orchestration.reportLaunchProgress
import com.hereliesaz.geministrator.orchestration.validateAgainst
import kotlinx.serialization.json.Json

/**
 * Workflow planner and plan repairer backed by the user's linked cloud LLM.
 *
 * Planning is not done on device: no model small enough to ship could pick a workflow with useful
 * accuracy. The model sees one bounded [OrchestrationPacket] and returns a plan, which is validated
 * against the packet's roles before use.
 */
class TextGenerationOrchestrationAgentRuntime(
    private val api: TextGenerationApi,
    private val json: Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    },
) : OrchestrationAgentRuntime {
    override suspend fun plan(packet: OrchestrationPacket): OrchestrationPlan =
        invoke(PLAN_INSTRUCTION, packet)

    override suspend fun repair(packet: OrchestrationPacket): OrchestrationPlan =
        invoke(REPAIR_INSTRUCTION, packet)

    private suspend fun invoke(instruction: String, packet: OrchestrationPacket): OrchestrationPlan {
        reportLaunchProgress("Waiting for the linked LLM…")
        val response = api.generate(buildPrompt(instruction, packet)).text
        reportLaunchProgress("Reading the plan…")
        val plan = json.decodeFromString(OrchestrationPlan.serializer(), response.extractJsonObject("Planner"))
        return plan.validateAgainst(packet)
    }

    private fun buildPrompt(instruction: String, packet: OrchestrationPacket): String = buildString {
        append("You are Aive's workflow planner. ")
        append(instruction)
        append(" Use only role ids from availableAgents. Keep the plan as small as the objective allows.")
        append(" Return exactly one JSON object, no markdown or prose, matching this schema:\n")
        append(PLAN_SCHEMA)
        append("\n\nPlanning packet:\n")
        append(json.encodeToString(OrchestrationPacket.serializer(), packet))
    }

    private companion object {
        const val PLAN_INSTRUCTION =
            "Create an executable, dependency-correct workflow DAG for the objective."
        const val REPAIR_INSTRUCTION =
            "Repair the current DAG after the reported failures, preserving valid completed work."
        const val PLAN_SCHEMA =
            """{"steps":[{"id":"step-id","name":"short name","objective":"bounded worker objective","roleId":"available-role-id","dependsOn":["prior-step-id"],"requiresHumanApproval":false}]}"""
    }
}
