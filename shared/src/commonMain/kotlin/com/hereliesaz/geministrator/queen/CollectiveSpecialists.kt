package com.hereliesaz.geministrator.queen

import com.hereliesaz.geministrator.domain.BuiltInRoles
import com.hereliesaz.geministrator.domain.RoleDefinition
import com.hereliesaz.geministrator.domain.RoleExecutionSource
import com.hereliesaz.geministrator.domain.promptInstructions
import com.hereliesaz.geministrator.orchestration.bySimilarityTo
import com.hereliesaz.geministrator.memory.CollectiveExpertiseMatch

/**
 * Rank distinct eligible roles from the current catalog for initial and requested advisory recruitment.
 * This never launches a task or gives a role its tools. Respect explicit pins and favor a different
 * configured Source and model identifier for the second assessment. Neither establishes
 * model-family or evidential independence.
 * At most one slot prefers a relevant previously observed exact instance; the other remains
 * description-ranked for independent exploration. No observed episode proves competence.
 * Factory calls construct text-only adapters, not inference calls.
 */
internal fun selectCollectiveSpecialists(
    question: String,
    roster: List<RoleDefinition>,
    facilitatorSources: List<ModelUse>,
    modelFor: ((String?) -> QueenModel?)?,
    fallback: Map<String, QueenModel>,
    experience: List<CollectiveExpertiseMatch> = emptyList(),
): List<Pair<RoleDefinition, QueenModel>> {
    val candidates = roster.filter {
        it.enabled && it.executionSource == RoleExecutionSource.Agent && it.id != BuiltInRoles.Orchestrator.id
    }.groupBy { it.id }.values.mapNotNull { it.singleOrNull() }
        .sortedWith(bySimilarityTo<RoleDefinition>(question) { "${it.name} ${it.description}" }.thenBy { it.id.value })
    val used = mutableSetOf<String>()
    val usedModels = mutableSetOf<String>()
    val sourceIds = (modelFor?.invoke(null)?.uses.orEmpty() + facilitatorSources).map { it.sourceId }.distinct()
    val selected = mutableListOf<Pair<RoleDefinition, QueenModel>>()
    // Resolve a historical pointer only against the current eligible role revision and route.
    // Preserve pins and never grant a role/model solely because its past identity was observed.
    val experienced = candidates.firstNotNullOfOrNull { role ->
        val ids = experience.filter { it.roleId == role.id.value && it.matchedEpisodes > 0 }
            .map { it.specialistId }.toSet()
        if (ids.isEmpty()) return@firstNotNullOfOrNull null
        val pin = role.preferredProviderId?.value
        val possible = if (modelFor == null) {
            listOfNotNull(fallback[role.id.value]?.takeIf { model ->
                pin == null || (model.uses.isNotEmpty() && model.uses.all { it.sourceId == pin })
            })
        } else {
            (pin?.let { listOf(it) } ?: sourceIds).mapNotNull { source ->
                modelFor(source)?.takeIf { it.uses.isNotEmpty() && it.uses.all { use -> use.sourceId == source } }
                    ?.let { voiceModel(it, role.name, role.promptInstructions()) }
            }
        }
        possible.firstOrNull { specialistInstanceId(role, it.uses) in ids }?.let { role to it }
    }
    val ordered = listOfNotNull(experienced?.first) + candidates.filterNot { it.id == experienced?.first?.id }
    for (role in ordered) {
        val pin = role.preferredProviderId?.value
        val choices = if (pin != null) listOf(pin) else sourceIds.sortedBy { it in used }
        val model = if (experienced != null && role.id == experienced.first.id) experienced.second
        else if (modelFor == null) {
            fallback[role.id.value]?.takeIf {
                pin == null || (it.uses.isNotEmpty() && it.uses.all { use -> use.sourceId == pin })
            }
        } else {
            val available = choices.mapNotNull { source ->
                modelFor(source)?.takeIf { it.uses.isNotEmpty() && it.uses.all { use -> use.sourceId == source } }
            }
            // Prefer distinct configured identifiers among unused Sources; unknown is not diversity.
            val chosen = if (usedModels.isEmpty() || pin != null) available.firstOrNull()
            else available.sortedWith(compareBy<QueenModel> { candidate ->
                candidate.uses.any { it.sourceId in used }
            }.thenBy { candidate ->
                val ids = candidate.uses.mapNotNull { it.modelId?.takeIf(String::isNotBlank) }
                ids.isEmpty() || ids.any { it in usedModels }
            }).firstOrNull()
            chosen?.let { voiceModel(it, role.name, role.promptInstructions()) }
        } ?: continue
        selected += role to model
        used += model.uses.map { it.sourceId }
        usedModels += model.uses.mapNotNull { it.modelId?.takeIf(String::isNotBlank) }
    }
    return selected
}
