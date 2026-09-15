package com.hereliesaz.geministrator.memory

/** Small, single-purpose local memory workers. */
enum class MemoryMicroAgentRole {
    Sectioner,
    SalienceFilter,
    NounTagger,
    VerbTagger,
    PhraseSynthesizer,
    SummarySynthesizer,
    CategoryClassifier,
    AssociationLinker,
    CondensationRewriter,
}

data class MemoryMicroAgentModelSpec(
    val modelId: String,
    val adapterId: String? = null,
    val quantization: String? = null,
    val maxContextTokens: Int = 4_096,
    val maxInputItems: Int = 24,
    val maxInputChars: Int = 12_000,
    val maxOutputChars: Int = 8_000,
    val maxMutations: Int = 96,
    val promptOverheadReserveChars: Int = 1_024,
    val localOnly: Boolean = true,
    val requirements: MemoryModelRequirements = MemoryModelRequirements.generation(),
    val deployment: MemoryMicroAgentDeploymentManifest = MemoryMicroAgentDeploymentManifest.portableOnnx(
        artifactId = modelId,
        quantization = quantization ?: "int8",
    ),
) {
    init {
        require(modelId.isNotBlank())
        require(maxContextTokens > 0)
        require(maxInputItems > 0)
        require(maxInputChars > 0)
        require(maxOutputChars > 0)
        require(maxMutations > 0)
        require(promptOverheadReserveChars >= 0)
        require(promptOverheadReserveChars < maxInputChars)
        require(!localOnly || deployment.supportsAllHaivePlatforms()) {
            "Local memory model $modelId must support Android, Windows, macOS, Linux, and Web"
        }
    }

    val maxContentChars: Int get() = maxInputChars - promptOverheadReserveChars
}

interface MemoryMicroAgent {
    val role: MemoryMicroAgentRole
    val model: MemoryMicroAgentModelSpec

    suspend fun process(packet: MemoryWorkPacket): MemoryMutationBatch
}

/** Autoregressive runtime contract used by the eight Qwen-style clerks. */
interface MemoryGenerativeInferenceRuntime {
    val platform: MemoryMicroAgentPlatform
    val capabilityDetector: HardwareCapabilityDetector
    val computePreference: MemoryComputePreference

    suspend fun isAvailable(
        model: MemoryMicroAgentModelSpec,
        artifact: MemoryMicroAgentArtifact,
    ): Boolean

    suspend fun generate(request: MemoryGenerativeInferenceRequest): MemoryGenerativeInferenceResult

    suspend fun executionReport(modelId: String): MemoryExecutionReport? = null
}

data class MemoryGenerativeInferenceRequest(
    val role: MemoryMicroAgentRole,
    val model: MemoryMicroAgentModelSpec,
    val artifact: MemoryMicroAgentArtifact,
    val prompt: String,
    val maxOutputChars: Int = model.maxOutputChars,
) {
    init {
        require(role != MemoryMicroAgentRole.AssociationLinker)
        require(model.requirements.workload == MemoryInferenceWorkload.AutoregressiveGeneration)
        require(artifact.platform in model.deployment.platforms)
        require(prompt.isNotBlank())
    }
}

data class MemoryGenerativeInferenceResult(
    val text: String,
    val execution: MemoryExecutionReport? = null,
)

/** Embedding runtime contract used by MiniLM-style semantic association. */
interface MemoryEmbeddingInferenceRuntime {
    val platform: MemoryMicroAgentPlatform
    val capabilityDetector: HardwareCapabilityDetector
    val computePreference: MemoryComputePreference

    suspend fun isAvailable(
        model: MemoryMicroAgentModelSpec,
        artifact: MemoryMicroAgentArtifact,
    ): Boolean

    suspend fun embed(request: MemoryEmbeddingInferenceRequest): MemoryEmbeddingInferenceResult

    suspend fun executionReport(modelId: String): MemoryExecutionReport? = null
}

data class MemoryEmbeddingInferenceRequest(
    val model: MemoryMicroAgentModelSpec,
    val artifact: MemoryMicroAgentArtifact,
    val texts: List<String>,
) {
    init {
        require(model.requirements.workload == MemoryInferenceWorkload.Embedding)
        require(artifact.platform in model.deployment.platforms)
        require(texts.isNotEmpty())
        require(texts.all { it.isNotBlank() })
    }
}

data class MemoryEmbeddingInferenceResult(
    val vectors: List<List<Float>>,
    val execution: MemoryExecutionReport? = null,
)

/**
 * Routes one bounded packet to the smallest specialist(s) required for that stage.
 *
 * The memory layer is deliberately associative rather than conscious. Association workers may
 * connect memories by shared topics or semantics, but they must not decide that two memories
 * contradict one another, determine which is true, or reconcile them. That reasoning belongs to
 * an ordinary orchestrated agent after recall; its explicit reasoning later enters memory as a
 * normal session episode.
 */
class MemoryMicroAgentRouter(
    agents: Collection<MemoryMicroAgent>,
) : MemoryManagerAgent {
    private val agentsByRole = agents.associateBy(MemoryMicroAgent::role)

    init {
        require(agentsByRole.size == agents.size) { "Memory micro-agent roles must be unique" }
        REQUIRED_ROLES.forEach { role ->
            require(role in agentsByRole) { "Missing memory micro-agent for $role" }
        }
        agentsByRole.values.forEach { agent ->
            require(!agent.model.localOnly || agent.model.deployment.supportsAllHaivePlatforms()) {
                "${agent.role} has an incomplete local deployment manifest"
            }
            val expectedWorkload = if (agent.role == MemoryMicroAgentRole.AssociationLinker) {
                MemoryInferenceWorkload.Embedding
            } else {
                MemoryInferenceWorkload.AutoregressiveGeneration
            }
            require(agent.model.requirements.workload == expectedWorkload) {
                "${agent.role} requires $expectedWorkload inference, not ${agent.model.requirements.workload}"
            }
        }
    }

    override suspend fun process(packet: MemoryWorkPacket): MemoryMutationBatch {
        val batches = rolesFor(packet.stage).map { role ->
            val agent = requireNotNull(agentsByRole[role])
            val routed = packet
                .withCodeSemanticHints(role)
                .withRoleInstruction(role)
                .fitToModel(agent.model)
            enforceInputBudget(agent, routed)
            agent.process(routed).also { batch ->
                enforceOutputBudget(agent, batch)
                validateRoleOutput(role, routed, batch)
            }
        }
        return mergeBatches(batches)
    }

    fun constrainPolicy(base: MemoryConsolidationPolicy = MemoryConsolidationPolicy()): MemoryConsolidationPolicy {
        val active = REQUIRED_ROLES.mapNotNull(agentsByRole::get)
        return base.copy(
            maxPacketItems = minOf(base.maxPacketItems, active.minOf { it.model.maxInputItems }),
            maxPacketChars = minOf(base.maxPacketChars, active.minOf { it.model.maxContentChars }),
            maxMutationsPerPacket = minOf(base.maxMutationsPerPacket, active.minOf { it.model.maxMutations }),
        )
    }

    private fun rolesFor(stage: MemoryConsolidationStage): List<MemoryMicroAgentRole> = when (stage) {
        MemoryConsolidationStage.Sectioning -> listOf(MemoryMicroAgentRole.Sectioner)
        MemoryConsolidationStage.Salience -> listOf(MemoryMicroAgentRole.SalienceFilter)
        MemoryConsolidationStage.Tags -> listOf(MemoryMicroAgentRole.NounTagger, MemoryMicroAgentRole.VerbTagger)
        MemoryConsolidationStage.Phrases -> listOf(MemoryMicroAgentRole.PhraseSynthesizer)
        MemoryConsolidationStage.Summaries -> listOf(MemoryMicroAgentRole.SummarySynthesizer)
        MemoryConsolidationStage.Categories -> listOf(MemoryMicroAgentRole.CategoryClassifier)
        MemoryConsolidationStage.Associations -> listOf(MemoryMicroAgentRole.AssociationLinker)
        MemoryConsolidationStage.Condensation -> listOf(MemoryMicroAgentRole.CondensationRewriter)
        MemoryConsolidationStage.Complete -> error("Complete memory jobs cannot be routed")
    }

    private fun MemoryWorkPacket.fitToModel(model: MemoryMicroAgentModelSpec): MemoryWorkPacket {
        require(items.size <= model.maxInputItems) {
            "Primary packet has ${items.size} items; ${model.modelId} limit is ${model.maxInputItems}"
        }
        val fixedChars = instruction.length + packetKey.length
        val primaryChars = items.sumOf(MemoryWorkItem::estimatedInputChars)
        require(fixedChars + primaryChars <= model.maxInputChars) {
            "Primary packet exceeds ${model.modelId} input budget"
        }

        var remainingItems = model.maxInputItems - items.size
        var remainingChars = model.maxInputChars - fixedChars - primaryChars
        val fittedNeighborhood = buildList {
            for (item in neighborhood) {
                if (remainingItems <= 0 || remainingChars <= 0) break
                val cost = item.estimatedInputChars()
                if (cost > remainingChars) continue
                add(item)
                remainingItems -= 1
                remainingChars -= cost
            }
        }
        return copy(neighborhood = fittedNeighborhood)
    }

    private fun enforceInputBudget(agent: MemoryMicroAgent, packet: MemoryWorkPacket) {
        require(packet.items.size + packet.neighborhood.size <= agent.model.maxInputItems)
        require(packet.estimatedInputChars() <= agent.model.maxInputChars) {
            "${agent.role} rendered packet exceeds ${agent.model.modelId} character budget"
        }
    }

    private fun MemoryWorkPacket.estimatedInputChars(): Int =
        instruction.length + packetKey.length +
            items.sumOf(MemoryWorkItem::estimatedInputChars) +
            neighborhood.sumOf(MemoryWorkItem::estimatedInputChars)

    private fun MemoryWorkItem.estimatedInputChars(): Int =
        id.length + kind.length + text.length + metadata.entries.sumOf { (key, value) -> key.length + value.length + 2 }

    private fun enforceOutputBudget(agent: MemoryMicroAgent, batch: MemoryMutationBatch) {
        require(batch.size <= agent.model.maxMutations) {
            "${agent.role} returned ${batch.size} mutations; limit is ${agent.model.maxMutations}"
        }
        val chars = batch.sectionsToAdd.sumOf { it.text.length } + batch.nodesToAdd.sumOf { it.text.length }
        require(chars <= agent.model.maxOutputChars) {
            "${agent.role} returned $chars text chars; ${agent.model.modelId} limit is ${agent.model.maxOutputChars}"
        }
    }

    private fun validateRoleOutput(
        role: MemoryMicroAgentRole,
        packet: MemoryWorkPacket,
        batch: MemoryMutationBatch,
    ) {
        when (role) {
            MemoryMicroAgentRole.Sectioner ->
                require(batch.nodesToAdd.isEmpty() && batch.edgesToAdd.isEmpty())

            MemoryMicroAgentRole.SalienceFilter -> {
                require(batch.sectionsToAdd.isEmpty() && batch.edgesToAdd.isEmpty())
                require(batch.nodesToAdd.all { it.kind == MemoryNodeKind.Context })
            }

            MemoryMicroAgentRole.NounTagger -> validateSemanticRole(
                batch,
                setOf(MemoryNodeKind.NounTag),
                setOf(MemoryRelationKind.Indexes),
            )

            MemoryMicroAgentRole.VerbTagger -> validateSemanticRole(
                batch,
                setOf(MemoryNodeKind.VerbTag),
                setOf(MemoryRelationKind.Indexes),
            )

            MemoryMicroAgentRole.PhraseSynthesizer -> validateSemanticRole(
                batch,
                setOf(MemoryNodeKind.Phrase),
                setOf(MemoryRelationKind.Composes),
            )

            MemoryMicroAgentRole.SummarySynthesizer -> validateSemanticRole(
                batch,
                setOf(MemoryNodeKind.Summary),
                setOf(MemoryRelationKind.Summarizes),
            )

            MemoryMicroAgentRole.CategoryClassifier -> validateSemanticRole(
                batch,
                setOf(MemoryNodeKind.Category),
                setOf(MemoryRelationKind.Categorizes),
            )

            MemoryMicroAgentRole.AssociationLinker -> {
                require(batch.sectionsToAdd.isEmpty() && batch.nodesToAdd.isEmpty())
                require(batch.edgesToAdd.all {
                    it.relation == MemoryRelationKind.SimilarTo ||
                        it.relation == MemoryRelationKind.AssociatedWith
                }) { "Association micro-agents may only express similarity/relatedness" }
            }

            MemoryMicroAgentRole.CondensationRewriter -> {
                require(batch.sectionsToAdd.isEmpty())
                require(batch.nodesToAdd.size <= 1)
                val inputKinds = packet.items.mapNotNull { item ->
                    item.kind.removePrefix("node:").takeIf { item.kind.startsWith("node:") }
                        ?.let { runCatching { MemoryNodeKind.valueOf(it) }.getOrNull() }
                }.toSet()
                require(batch.nodesToAdd.all { it.kind in inputKinds })
                require(batch.edgesToAdd.all {
                    it.relation == MemoryRelationKind.CondensedFrom ||
                        it.relation == MemoryRelationKind.Supersedes ||
                        it.relation == MemoryRelationKind.AssociatedWith
                })
            }
        }
    }

    private fun validateSemanticRole(
        batch: MemoryMutationBatch,
        allowedKinds: Set<MemoryNodeKind>,
        allowedRelations: Set<MemoryRelationKind>,
    ) {
        require(batch.sectionsToAdd.isEmpty())
        require(batch.nodesToAdd.all { it.kind in allowedKinds })
        require(batch.edgesToAdd.all { it.relation in allowedRelations })
    }

    private fun mergeBatches(batches: List<MemoryMutationBatch>): MemoryMutationBatch {
        val merged = MemoryMutationBatch(
            sectionsToAdd = batches.flatMap(MemoryMutationBatch::sectionsToAdd),
            nodesToAdd = batches.flatMap(MemoryMutationBatch::nodesToAdd),
            edgesToAdd = batches.flatMap(MemoryMutationBatch::edgesToAdd),
        )
        require(merged.sectionsToAdd.map { it.id }.distinct().size == merged.sectionsToAdd.size)
        require(merged.nodesToAdd.map { it.id }.distinct().size == merged.nodesToAdd.size)
        require(merged.edgesToAdd.map { it.id }.distinct().size == merged.edgesToAdd.size)
        return merged
    }

    private fun MemoryWorkPacket.withRoleInstruction(role: MemoryMicroAgentRole): MemoryWorkPacket = copy(
        instruction = buildString {
            appendLine("MICRO-AGENT ROLE: ${role.name}. Perform only this role.")
            when (role) {
                MemoryMicroAgentRole.NounTagger -> appendLine(
                    "NOUN means a semantic entity/reference, not merely an English noun. In code include relevant symbols, callable identities, types, files, modules, APIs, endpoints, data structures, configuration keys, branches, and other artifacts. Metadata '$CODE_NOUN_HINTS' contains advisory candidates; keep, normalize, split, ignore, or supplement them as semantics require.",
                )
                MemoryMicroAgentRole.VerbTagger -> appendLine(
                    "VERB means a semantic action/transformation, not merely an English verb. In code include calls, CRUD operations, parsing, validation, serialization, data-flow operations, build/test/deploy, Git/shell actions, HTTP methods, and actions implied by identifiers. Metadata '$CODE_VERB_HINTS' contains advisory candidates. A callable may simultaneously exist as a noun entity and imply a verb action.",
                )
                MemoryMicroAgentRole.AssociationLinker -> appendLine(
                    "Associate memories only by topical or semantic relatedness. Do not infer contradiction, truth, falsity, conflict resolution, or which memory supersedes another. Conscious reconciliation belongs to a normal orchestrated agent and will later enter memory through ordinary session consolidation.",
                )
                else -> Unit
            }
            append(instruction)
        },
    )

    companion object {
        val REQUIRED_ROLES: Set<MemoryMicroAgentRole> = setOf(
            MemoryMicroAgentRole.Sectioner,
            MemoryMicroAgentRole.SalienceFilter,
            MemoryMicroAgentRole.NounTagger,
            MemoryMicroAgentRole.VerbTagger,
            MemoryMicroAgentRole.PhraseSynthesizer,
            MemoryMicroAgentRole.SummarySynthesizer,
            MemoryMicroAgentRole.CategoryClassifier,
            MemoryMicroAgentRole.AssociationLinker,
            MemoryMicroAgentRole.CondensationRewriter,
        )
    }
}
