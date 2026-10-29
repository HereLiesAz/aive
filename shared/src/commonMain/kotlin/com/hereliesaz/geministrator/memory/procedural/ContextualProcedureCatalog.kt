package com.hereliesaz.geministrator.memory.procedural

import com.hereliesaz.geministrator.memory.MemoryBanks
import com.hereliesaz.geministrator.memory.MemoryEpisodeId
import com.hereliesaz.geministrator.memory.MemoryLineage

/**
 * Read-only link to Aive's contextual memory. An untrusted candidate is shown only while its
 * referenced source episodes remain present in its producing workflow's own lineage.
 * Presence is not verification and this component never enables execution.
 */
class ContextualProcedureCatalog(
    private val procedures: ProcedureBankCatalog,
    private val memories: MemoryBanks,
    private val lineage: MemoryLineage,
) {
    suspend fun sourceBackedCandidates(workflowId: String): List<ProcedureCandidate> {
        val cached = mutableMapOf<String, Set<MemoryEpisodeId>>()
        val result = mutableListOf<ProcedureCandidate>()
        for (candidate in procedures.visible(workflowId)) {
            val producer = candidate.definition.ref.workflowId
            var episodes = cached[producer]
            if (episodes == null) {
                val banks = listOf(producer) + lineage.ancestorsOf(producer)
                episodes = banks.flatMap { bank ->
                    memories.store(bank).read().episodes.filter { it.workflowRunId == bank }.map { it.id }
                }.toSet()
                cached[producer] = episodes
            }
            if (candidate.definition.sourceEpisodeIds.all { it in episodes }) result += candidate
        }
        return result
    }
}
