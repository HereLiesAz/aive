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
)

@Serializable
data class OrchestrationPlan(
    val steps: List<OrchestrationPlanStep>,
)

@Serializable
data class OrchestrationPacket(
    val objective: String,
    val currentState: String = "new",
    val acceptanceCriteria: List<String> = emptyList(),
    val availableAgents: List<OrchestrationRole>,
    val availableTools: List<String> = emptyList(),
    val availableModels: List<String> = emptyList(),
    val resourceConstraints: Map<String, String> = emptyMap(),
    val retrievedMemory: List<String> = emptyList(),
    val artifacts: List<String> = emptyList(),
    val previousSteps: List<String> = emptyList(),
    val currentPlan: List<OrchestrationPlanStep> = emptyList(),
    val failures: List<OrchestrationFailure> = emptyList(),
    val instruction: String,
)

interface OrchestrationAgentRuntime {
    suspend fun plan(packet: OrchestrationPacket): OrchestrationPlan
    suspend fun repair(packet: OrchestrationPacket): OrchestrationPlan
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
        require(step.roleId in roleIds) { "Plan step ${step.id} references unavailable role ${step.roleId}" }
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
