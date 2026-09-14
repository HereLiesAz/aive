package com.hereliesaz.geministrator.memory

/**
 * Small, single-purpose memory workers. A production installation may back every role with a
 * different on-device SLM/LoRA adapter. Roles are intentionally narrower than consolidation
 * stages so no model needs to learn the entire memory-maintenance problem.
 */
enum class MemoryMicroAgentRole {
    Sectioner,
    SalienceFilter,
    NounTagger,
    VerbTagger,
    PhraseSynthesizer,
    SummarySynthesizer,
    CategoryClassifier,
    AssociationLinker,
    ConflictResolver,
    CondensationRewriter,
}

data class MemoryMicroAgentModelSpec(
    val modelId: String,
    val adapterId: String? = null,
    val quantization: String? = null,
    val maxInputItems: Int = 24,
    val maxInputChars: Int = 12_000,
    val maxOutputChars: Int = 8_000,
    val maxMutations: Int = 96,
    /** Memory maintenance is local-first. Set false only for explicit development fallbacks. */
    val localOnly: Boolean = true,
) {
    init {
        require(modelId.isNotBlank())
        require(maxInputItems > 0)
        require(maxInputChars > 0)
        require(maxOutputChars > 0)
        require(maxMutations > 0)
    }
}

interface MemoryMicroAgent {
    val role: MemoryMicroAgentRole
    val model: MemoryMicroAgentModelSpec

    suspend fun process(packet: MemoryWorkPacket): MemoryMutationBatch
}

/**
 * Platform inference boundary. Android/Desktop implementations can use different local runtimes
 * while the memory system remains runtime-agnostic.
 */
interface MemoryMicroAgentInferenceRuntime {
    suspend fun isAvailable(model: MemoryMicroAgentModelSpec): Boolean

    suspend fun infer(request: MemoryMicroAgentInferenceRequest): MemoryMicroAgentInferenceResult
}

data class MemoryMicroAgentInferenceRequest(
    val role: MemoryMicroAgentRole,
    val model: MemoryMicroAgentModelSpec,
    val prompt: String,
    val maxOutputChars: Int = model.maxOutputChars,
)

data class MemoryMicroAgentInferenceResult(
    val text: String,
)

/**
 * Routes a bounded consolidation packet to the smallest appropriate specialist(s). Tags are
 * deliberately split between noun and verb models and merged only after both bounded jobs return.
 *
 * Noun/verb are semantic indexing roles, not ordinary English POS tagging. Code is first-class:
 * a callable symbol may be indexed as a noun/entity while its action stem is independently indexed
 * as a verb/action. Deterministic code hints reduce syntax-discovery work for the small models but
 * remain advisory; the micro-agent owns the semantic decision.
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
    }

    override suspend fun process(packet: MemoryWorkPacket): MemoryMutationBatch {
        val roles = rolesFor(packet.stage)
        val batches = roles.map { role ->
            val agent = requireNotNull(agentsByRole[role]) { "No memory micro-agent registered for $role" }
            val routedPacket = packet
                .withCodeSemanticHints(role)
                .withRoleInstruction(role)
            enforceInputBudget(agent, routedPacket)
            val batch = agent.process(routedPacket)
            enforceOutputBudget(agent, batch)
            validateRoleOutput(role, routedPacket, batch)
            batch
        }
        return mergeBatches(batches)
    }

    /**
     * Returns a consolidation policy that cannot create a packet larger than any active specialist
     * can accept. This makes model context limits a deterministic system invariant.
     */
    fun constrainPolicy(base: MemoryConsolidationPolicy = MemoryConsolidationPolicy()): MemoryConsolidationPolicy {
        val active = REQUIRED_ROLES.mapNotNull(agentsByRole::get)
        return base.copy(
            maxPacketItems = minOf(base.maxPacketItems, active.minOf { it.model.maxInputItems }),
            maxPacketChars = minOf(base.maxPacketChars, active.minOf { it.model.maxInputChars }),
            maxMutationsPerPacket = minOf(base.maxMutationsPerPacket, active.minOf { it.model.maxMutations }),
        )
    }

    suspend fun resolveConflict(packet: MemoryWorkPacket): MemoryMutationBatch {
        val agent = requireNotNull(agentsByRole[MemoryMicroAgentRole.ConflictResolver]) {
            "No conflict-resolution micro-agent is registered"
        }
        val routedPacket = packet.withRoleInstruction(MemoryMicroAgentRole.ConflictResolver)
        enforceInputBudget(agent, routedPacket)
        return agent.process(routedPacket).also { batch ->
            enforceOutputBudget(agent, batch)
            validateRoleOutput(MemoryMicroAgentRole.ConflictResolver, routedPacket, batch)
        }
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

    private fun enforceInputBudget(agent: MemoryMicroAgent, packet: MemoryWorkPacket) {
        require(packet.items.size + packet.neighborhood.size <= agent.model.maxInputItems) {
            "${agent.role} packet has too many items for ${agent.model.modelId}"
        }
        val chars = packet.items.sumOf { it.text.length } + packet.neighborhood.sumOf { it.text.length }
        require(chars <= agent.model.maxInputChars) {
            "${agent.role} packet has $chars chars; ${agent.model.modelId} limit is ${agent.model.maxInputChars}"
        }
    }

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
            MemoryMicroAgentRole.Sectioner -> {
                require(batch.nodesToAdd.isEmpty() && batch.edgesToAdd.isEmpty())
            }
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
                        it.relation == MemoryRelationKind.AssociatedWith ||
                        it.relation == MemoryRelationKind.ConflictsWith
                })
            }
            MemoryMicroAgentRole.ConflictResolver -> {
                require(batch.sectionsToAdd.isEmpty())
                require(batch.nodesToAdd.size <= 1)
                require(batch.nodesToAdd.all { it.kind == MemoryNodeKind.Summary })
                require(batch.edgesToAdd.all {
                    it.relation == MemoryRelationKind.ResolvesConflict ||
                        it.relation == MemoryRelationKind.AssociatedWith
                })
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
        require(merged.sectionsToAdd.map { it.id }.distinct().size == merged.sectionsToAdd.size) {
            "Memory micro-agents returned duplicate section IDs"
        }
        require(merged.nodesToAdd.map { it.id }.distinct().size == merged.nodesToAdd.size) {
            "Memory micro-agents returned duplicate node IDs"
        }
        require(merged.edgesToAdd.map { it.id }.distinct().size == merged.edgesToAdd.size) {
            "Memory micro-agents returned duplicate edge IDs"
        }
        return merged
    }

    private fun MemoryWorkPacket.withRoleInstruction(role: MemoryMicroAgentRole): MemoryWorkPacket = copy(
        instruction = buildString {
            appendLine("MICRO-AGENT ROLE: ${role.name}. Perform only this role.")
            when (role) {
                MemoryMicroAgentRole.NounTagger -> appendLine(
                    "NOUN means a semantic entity/reference, not merely an English noun. In code include relevant " +
                        "symbols, callable identities, types, files, modules, APIs, endpoints, data structures, " +
                        "configuration keys, branches, and other artifacts. Metadata '$CODE_NOUN_HINTS' contains " +
                        "advisory code candidates; keep, normalize, split, ignore, or supplement them as semantics require.",
                )
                MemoryMicroAgentRole.VerbTagger -> appendLine(
                    "VERB means a semantic action/transformation, not merely an English verb. In code include calls, " +
                        "CRUD operations, parsing, validation, serialization, data-flow operations, build/test/deploy, " +
                        "Git/shell actions, HTTP methods, and actions implied by identifiers. Metadata '$CODE_VERB_HINTS' " +
                        "contains advisory candidates. A callable may simultaneously exist as a noun entity and imply a verb action.",
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
