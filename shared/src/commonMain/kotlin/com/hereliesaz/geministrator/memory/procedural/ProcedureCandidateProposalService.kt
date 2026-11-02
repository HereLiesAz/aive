package com.hereliesaz.geministrator.memory.procedural

import com.hereliesaz.geministrator.memory.MemoryBanks
import com.hereliesaz.geministrator.memory.MemoryEpisode
import com.hereliesaz.geministrator.memory.memoryBankKey
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Declarative template only: never a script or an executable program. */
@Serializable
private data class ProcedureCandidateTemplate(
    val schemaVersion: Int = 1,
    val kind: ProcedureKind,
    val steps: List<ProcedureStep>,
    val parameters: List<ProcedureParameter>,
    val observedOrderOnly: Boolean,
)

/**
 * Explicit proposal entry point. Agents may present a pattern for consideration, but this service
 * always reconstructs it from the live workflow's own trusted bank and contextual episode IDs.
 * Nothing here approves, promotes, executes, or infers arbitrary code from a model transcript.
 */
class ProcedureCandidateProposalService(
    private val procedures: ProcedureBankCatalog,
    private val memories: MemoryBanks,
) {
    suspend fun discover(workflowId: String): List<ProcedurePattern> {
        val workflow = requireNotNull(memoryBankKey(workflowId))
        return ProcedureCandidateExtractor.discover(
            workflow, procedures.own(workflow).read().traces, memories.store(workflow).read().episodes,
        )
    }

    suspend fun propose(
        workflowId: String,
        pattern: ProcedurePattern,
        name: String,
        descriptors: Map<String, ProcedureOperationDescriptor>,
        atEpochMillis: Long,
    ): ProcedureReference {
        val workflow = requireNotNull(memoryBankKey(workflowId))
        require(pattern.workflowId == workflow)
        require(name.matches(Regex("[a-z][a-z0-9.-]{0,79}"))) { "Invalid procedure name" }
        require(atEpochMillis >= 0)
        // No mutable or privileged action can enter the proposal manifest without a separate policy.
        val (steps, parameters) = requireNotNull(ProcedureCompatibility.proposalSteps(pattern, descriptors)) {
            "No eligible host-declared read-only operation descriptors"
        }
        val sourceText = Json.encodeToString(
            ProcedureCandidateTemplate.serializer(),
            ProcedureCandidateTemplate(kind = pattern.kind, steps = steps, parameters = parameters,
                observedOrderOnly = pattern.observedOrderOnly),
        )
        val artifact = ProcedureArtifact.fromSource(workflow, sourceText)
        val own = procedures.own(workflow)
        while (true) {
            val current = own.read()
            val episodes = memories.store(workflow).read().episodes
            val currentPatterns = ProcedureCandidateExtractor.discover(workflow, current.traces, episodes)
            require(pattern in currentPatterns) { "Procedure candidate has stale or missing evidence" }
            val observed = current.traces.associateBy { it.receiptKey }
            val evidencePairs = pattern.steps.flatMap { step ->
                step.receipts.map { receipt ->
                    val trace = requireNotNull(observed[receipt])
                    val episode = requireNotNull(linkedSourceEpisode(trace, episodes))
                    require(episode.id in step.sourceEpisodeIds)
                    trace to episode
                }
            }
            val derivedSourceIds = evidencePairs.mapTo(linkedSetOf()) { it.second.id }
            require(derivedSourceIds == pattern.sourceEpisodeIds && derivedSourceIds.isNotEmpty())
            current.definitions.firstOrNull {
                it.ref.name == name &&
                    it.kind == pattern.kind &&
                    it.contentDigest == artifact.digest &&
                    it.sourceEpisodeIds == derivedSourceIds
            }?.let { return it.ref }
            val version = (current.definitions.filter { it.ref.name == name }.maxOfOrNull { it.ref.version } ?: 0) + 1
            val ref = ProcedureReference(workflow, name, version)
            val definition = ProcedureDefinition(
                ref = ref,
                kind = pattern.kind,
                summary = if (pattern.kind == ProcedureKind.Reflex) "Observed single-executor operation pattern"
                    else "Observed multi-step operation order; dependencies unverified",
                contentDigest = artifact.digest,
                sourceEpisodeIds = derivedSourceIds,
                parameters = parameters,
                steps = steps,
                createdAtEpochMillis = atEpochMillis,
            )
            val evidence = evidencePairs.mapIndexed { index, (trace, episode) ->
                ProcedureEvidence(
                    id = "${ref.name}@${ref.version}#${index + 1}",
                    procedure = ref,
                    sourceEpisodeId = episode.id,
                    taskRunId = trace.taskRunId,
                    receiptId = trace.receiptKey,
                    outcome = ProcedureEvidenceOutcome.Completed,
                    recordedAtEpochMillis = trace.recordedAtEpochMillis,
                )
            }
            val mutation = ProcedureMutation(
                definitionsToAdd = listOf(definition),
                evidenceToAdd = evidence,
                artifactsToAdd = if (current.artifacts.any { it.digest == artifact.digest }) emptyList() else listOf(artifact),
            )
            if (own.commit(current.revision, mutation)) return ref
        }
    }

    private fun linkedSourceEpisode(trace: ProcedureTrace, episodes: List<MemoryEpisode>): MemoryEpisode? =
        episodes.singleOrNull { episode ->
            episode.purged == null &&
                episode.workflowRunId == trace.workflowId &&
                episode.projectId == trace.projectId &&
                episode.taskRunId == trace.taskRunId &&
                episode.taskDefinitionId == trace.taskDefinitionId &&
                episode.sourceSessionId == "executor:${trace.taskRunId}:${trace.attempt}" &&
                episode.userPrompt == "Execution receipt ${trace.receiptKey}: ${trace.contextualSummary()}"
        }
}
