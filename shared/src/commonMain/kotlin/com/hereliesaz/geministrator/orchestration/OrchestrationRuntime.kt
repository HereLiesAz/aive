package com.hereliesaz.geministrator.orchestration

import kotlinx.serialization.Serializable

@Serializable
data class OrchestrationRole(
    val id: String,
    val name: String,
    val authorities: List<String>,
)

@Serializable
data class OrchestrationFailure(
    val stepId: String,
    val summary: String,
    val attempt: Int,
)

@Serializable
data class OrchestrationPlanStep(
    val id: String,
    val name: String,
    val objective: String,
    val roleId: String,
    val dependsOn: List<String> = emptyList(),
    val requiresHumanApproval: Boolean = false,
    /**
     * A step made from a catalog template (docs/Step-Templates.md): the template's id, and a literal or `@<step>.<port>`
     * per input. The role is then chosen from the template, so [roleId] may be blank. Null for an ordinary step.
     */
    val templateId: String? = null,
    val templateInputs: Map<String, String> = emptyMap(),
)

/** A template the planner may use: its id, title, input ports (required ones first) and what it may change. Not research text. */
@Serializable
data class OrchestrationTemplate(
    val id: String,
    val title: String,
    val requiredPorts: List<String>,
    val optionalPorts: List<String> = emptyList(),
    val effects: List<String> = emptyList(),
)

@Serializable
data class OrchestrationPlan(
    val steps: List<OrchestrationPlanStep>,
    /**
     * What the planner expects to show that the hive is reached, as `done:step-id`, `artifact:step-id` or `nothing-failed`. Optional:
     * the queen reads only the words she knows and checks them against the run (see queen/Guidance.kt).
     */
    val hiveSigns: List<String> = emptyList(),
    /**
     * What this hive still needs created or obtained, each as `sentence`, `sentence => template-id` or
     * `sentence => template-id @ step-id`; optional. Read and checked by queen/Blockers.kt, like [hiveSigns].
     */
    val blockers: List<String> = emptyList(),
)

@Serializable
data class OrchestrationPacket(
    val objective: String,
    val currentState: String = "new",
    val acceptanceCriteria: List<String> = emptyList(),
    val availableAgents: List<OrchestrationRole>,
    val availableTools: List<String> = emptyList(),
    /** The closed catalog of step templates the planner may use; separate from [availableTools], which is untrusted text. */
    val availableTemplates: List<OrchestrationTemplate> = emptyList(),
    val availableModels: List<String> = emptyList(),
    val resourceConstraints: Map<String, String> = emptyMap(),
    val artifacts: List<String> = emptyList(),
    val previousSteps: List<String> = emptyList(),
    val currentPlan: List<OrchestrationPlanStep> = emptyList(),
    val failures: List<OrchestrationFailure> = emptyList(),
    val instruction: String,
)

interface OrchestrationAgentRuntime {
    suspend fun plan(packet: OrchestrationPacket): OrchestrationPlan
    suspend fun repair(packet: OrchestrationPacket): OrchestrationPlan

    /** The Source that answered the last call, when this runtime knows it (the reviewer prefers another); null otherwise. */
    val lastSourceId: String? get() = null
}

object NoOrchestrationAgentRuntime : OrchestrationAgentRuntime {
    override suspend fun plan(packet: OrchestrationPacket): OrchestrationPlan =
        error("The orchestration model runtime is unavailable on this platform")

    override suspend fun repair(packet: OrchestrationPacket): OrchestrationPlan =
        error("The orchestration model runtime is unavailable on this platform")
}

fun OrchestrationPlan.validateAgainst(packet: OrchestrationPacket): OrchestrationPlan {
    require(steps.isNotEmpty()) { "Planner returned an empty plan" }
    val ids = steps.map { it.id.trim() }
    require(ids.all(String::isNotEmpty)) { "Planner returned a blank step id" }
    require(ids.size == ids.toSet().size) { "Planner returned duplicate step ids" }
    val roleIds = packet.availableAgents.mapTo(mutableSetOf()) { it.id }
    steps.forEach { step ->
        require(step.name.isNotBlank()) { "Plan step ${step.id} has no name" }
        require(step.objective.isNotBlank()) { "Plan step ${step.id} has no objective" }
        val templateId = step.templateId
        if (templateId == null) {
            require(step.roleId in roleIds) { "Plan step ${step.id} references unavailable role ${step.roleId}" }
        } else {
            val template = packet.availableTemplates.firstOrNull { it.id == templateId }
            require(template != null) { "Plan step ${step.id} uses a template that is not offered: $templateId" }
            // The queen may make only steps that change nothing outside their own output.
            val builtIn = com.hereliesaz.geministrator.template.BuiltInTemplates.byId[templateId]
            require(builtIn != null && !builtIn.effectful) { "Plan step ${step.id} uses a template the planner may not use: $templateId" }
            val known = template.requiredPorts + template.optionalPorts
            step.templateInputs.keys.forEach { port -> require(port in known) { "Plan step ${step.id} gives an input the template does not have: $port" } }
            template.requiredPorts.forEach { port ->
                require(!step.templateInputs[port].isNullOrBlank()) { "Plan step ${step.id} has no value for the required input $port" }
            }
            step.templateInputs.values.mapNotNull(com.hereliesaz.geministrator.template.StepInstantiation::boundTask).forEach { from ->
                require(from.value in ids && from.value != step.id) { "Plan step ${step.id} takes an input from an unknown step ${from.value}" }
            }
        }
        require(step.id !in step.dependsOn) { "Plan step ${step.id} depends on itself" }
        step.dependsOn.forEach { dependency ->
            require(dependency in ids) { "Plan step ${step.id} references unknown dependency $dependency" }
        }
    }
    val visiting = mutableSetOf<String>()
    val visited = mutableSetOf<String>()
    val byId = steps.associateBy { it.id }
    fun visit(id: String) {
        if (id in visited) return
        require(visiting.add(id)) { "Planner returned a cyclic dependency involving $id" }
        byId.getValue(id).dependsOn.forEach(::visit)
        visiting.remove(id)
        visited.add(id)
    }
    ids.forEach(::visit)
    return this
}
