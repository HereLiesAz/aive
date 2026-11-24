package com.hereliesaz.geministrator.queen

import com.hereliesaz.geministrator.domain.BuiltInRoles
import com.hereliesaz.geministrator.domain.RoleDefinition
import com.hereliesaz.geministrator.domain.RoleExecutionSource
import com.hereliesaz.geministrator.domain.promptInstructions
import com.hereliesaz.geministrator.orchestration.bySimilarityTo

/**
 * Recruit up to two distinct eligible roles from the current catalog. This is advisory activation,
 * not launching a task or giving a role its tools. Respect explicit pins and favor a different
 * configured source for the second assessment; provider diversity is not proof of independence.
 * Factory calls construct text-only adapters, not inference calls.
 */
internal fun selectCollectiveSpecialists(
    question: String,
    roster: List<RoleDefinition>,
    facilitatorSources: List<ModelUse>,
    modelFor: ((String?) -> QueenModel?)?,
    fallback: Map<String, QueenModel>,
): List<Pair<RoleDefinition, QueenModel>> {
    val candidates = roster.filter {
        it.enabled && it.executionSource == RoleExecutionSource.Agent && it.id != BuiltInRoles.Orchestrator.id
    }.groupBy { it.id }.values.mapNotNull { it.singleOrNull() }
        .sortedWith(bySimilarityTo<RoleDefinition>(question) { "${it.name} ${it.description}" }.thenBy { it.id.value })
    val used = mutableSetOf<String>()
    val sourceIds = (modelFor?.invoke(null)?.uses.orEmpty() + facilitatorSources).map { it.sourceId }.distinct()
    val selected = mutableListOf<Pair<RoleDefinition, QueenModel>>()
    for (role in candidates) {
        val pin = role.preferredProviderId?.value
        val choices = if (pin != null) listOf(pin) else sourceIds.sortedBy { it in used }
        val model = if (modelFor == null) {
            fallback[role.id.value]?.takeIf {
                pin == null || (it.uses.isNotEmpty() && it.uses.all { use -> use.sourceId == pin })
            }
        } else {
            choices.firstNotNullOfOrNull { source ->
                modelFor(source)?.takeIf { it.uses.isNotEmpty() && it.uses.all { use -> use.sourceId == source } }
            }?.let { voiceModel(it, role.name, role.promptInstructions()) }
        } ?: continue
        selected += role to model
        used += model.uses.map { it.sourceId }
        if (selected.size == 2) break
    }
    return selected
}
