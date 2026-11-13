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
        val response = generatePlanText(buildPrompt(instruction, packet))
        reportLaunchProgress("Reading the plan…")
        val plan = json.decodeFromString(OrchestrationPlan.serializer(), response.extractJsonObject("Planner"))
        return plan.validateAgainst(packet)
    }

    /**
     * Asks for the plan with a generous output budget ([PlanOutputBudget], clamped to the API's cap).
     * A reply cut off at the limit is asked for once more on the same provider with a larger budget
     * and an instruction to be more compact; if that is cut off too, the [OutputTruncatedException]
     * goes up so the failover runtime can try the next provider.
     */
    private suspend fun generatePlanText(prompt: String): String {
        try {
            return ask(prompt, PlanOutputBudget.first(api))
        } catch (_: OutputTruncatedException) {
            reportLaunchProgress("The plan was cut off. Asking again, more briefly…")
        }
        return ask(prompt + COMPACT_RETRY, PlanOutputBudget.retry(api))
    }

    private suspend fun ask(prompt: String, budget: Int?): String =
        (if (budget != null) api.generateWithBudget(prompt, budget) else api.generate(prompt)).text

    private fun buildPrompt(instruction: String, packet: OrchestrationPacket): String = buildString {
        append("You are Aive's workflow planner. ")
        append(instruction)
        append(" Use only role ids from availableAgents. Keep the plan as small as the objective allows.")
        append(" Be compact: step names of at most 4 words, each objective one sentence of at most 25 words,")
        append(" no prose or explanation outside the JSON, and no boilerplate repeated across steps.")
        append(" Return exactly one JSON object, no markdown or prose, matching this schema:\n")
        append(PLAN_SCHEMA)
        append("\n\nPlanning packet:\n")
        append(json.encodeToString(OrchestrationPacket.serializer(), packet))
    }

    private companion object {
        const val PLAN_INSTRUCTION =
            "Create an executable, dependency-correct workflow DAG for the objective. " +
                "In hiveSigns, optionally name what would show the objective is reached, each as done:step-id, artifact:step-id or nothing-failed. " +
                "In blockers, optionally list what must still be created or obtained for the hive to be complete, each as one " +
                "plain line, optionally followed by ` => template-id` from availableTemplates and ` @ step-id` of the step that makes it."
        const val REPAIR_INSTRUCTION =
            "Repair the current DAG after the reported failures, preserving valid completed work."
        const val COMPACT_RETRY =
            "\n\nYour previous reply was cut off because it was too long. Reply again with a much more compact " +
                "plan: about half as many steps, names of at most 3 words, each objective at most 15 words, " +
                "no optional fields, nothing outside the JSON object."
        const val PLAN_SCHEMA =
            """{"steps":[{"id":"step-id","name":"short name","objective":"bounded worker objective","roleId":"available-role-id","dependsOn":["prior-step-id"],"requiresHumanApproval":false}],"hiveSigns":["done:last-step-id"]}"""
    }
}
