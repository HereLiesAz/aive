package com.hereliesaz.geministrator.memory

/**
 * Deterministic fast path for the noun/entity and verb/action tagging stages.
 *
 * The epoch-8 specialist remains the fallback. We bypass it only when every item in the bounded
 * packet has strong technical evidence that Haive can extract mechanically. Natural language,
 * proper names, ambiguous morphology, and mixed/partial packets continue through the trained
 * specialist rather than being guessed at by rules.
 */
class ProgrammaticSemanticTaggerMicroAgent(
    private val fallback: MemoryMicroAgent,
) : MemoryMicroAgent {
    override val role: MemoryMicroAgentRole = fallback.role
    override val model: MemoryMicroAgentModelSpec = fallback.model

    init {
        require(role == MemoryMicroAgentRole.NounTagger || role == MemoryMicroAgentRole.VerbTagger) {
            "ProgrammaticSemanticTaggerMicroAgent can wrap only NounTagger or VerbTagger"
        }
    }

    override suspend fun process(packet: MemoryWorkPacket): MemoryMutationBatch {
        require(packet.stage == MemoryConsolidationStage.Tags)
        if (packet.items.isEmpty()) return MemoryMutationBatch()

        val extracted = packet.items.map { item ->
            val hints = extractCodeSemanticHints(item.text)
            val relevant = when (role) {
                MemoryMicroAgentRole.NounTagger -> hints.nounCandidates
                MemoryMicroAgentRole.VerbTagger -> hints.verbCandidates
                else -> emptyList()
            }.map(String::trim).filter(String::isNotEmpty).distinct()
            ProgrammaticTagInput(
                item = item,
                candidates = relevant,
                stronglyTechnical = item.text.looksStronglyTechnical(),
            )
        }

        val canBypassModel = extracted.all { input ->
            input.stronglyTechnical && input.candidates.isNotEmpty() && input.item.kind == "node:${MemoryNodeKind.Context.name}"
        }
        if (!canBypassModel) return fallback.process(packet)

        val namespace = "${packet.queueId.value}:${packet.stage.name}:${packet.packetKey}:${role.name}:programmatic"
        val nodes = mutableListOf<MemoryNode>()
        val edges = mutableListOf<MemoryEdge>()

        extracted.forEachIndexed { itemIndex, input ->
            val inheritedEpisodes = input.item.semanticSourceEpisodeIds().toMutableSet().apply { add(packet.episodeId) }
            val inheritedSections = input.item.semanticSourceSectionIds().toSet()
            val salience = input.item.metadata["salience"]?.toFloatOrNull()?.coerceIn(0f, 1f) ?: 0.5f

            input.candidates.take(MAX_PROGRAMMATIC_TAGS_PER_ITEM).forEachIndexed { candidateIndex, rawCandidate ->
                val candidate = normalizeProgrammaticCandidate(rawCandidate)
                if (candidate.isBlank()) return@forEachIndexed
                val nodeId = MemoryNodeId(
                    "$namespace:node:$itemIndex:$candidateIndex:${candidate.programmaticTagIdPart()}",
                )
                val nodeKind = when (role) {
                    MemoryMicroAgentRole.NounTagger -> MemoryNodeKind.NounTag
                    MemoryMicroAgentRole.VerbTagger -> MemoryNodeKind.VerbTag
                    else -> error("Unsupported semantic tag role $role")
                }
                nodes += MemoryNode(
                    id = nodeId,
                    kind = nodeKind,
                    text = candidate,
                    sourceEpisodeIds = inheritedEpisodes,
                    sourceSectionIds = inheritedSections,
                    salience = salience,
                    confidence = PROGRAMMATIC_TAG_CONFIDENCE,
                    createdAtEpochMillis = input.item.createdAtEpochMillisOrZero(),
                    metadata = mapOf(
                        "microAgentRole" to role.name,
                        "semanticSource" to "programmatic-code",
                        "modelBypassed" to "true",
                        "fallbackModel" to model.modelId,
                    ),
                )
                edges += MemoryEdge(
                    id = MemoryEdgeId("$namespace:edge:$itemIndex:$candidateIndex"),
                    from = nodeId,
                    to = MemoryNodeId(input.item.id),
                    relation = MemoryRelationKind.Indexes,
                    weight = 1f,
                    createdAtEpochMillis = input.item.createdAtEpochMillisOrZero(),
                    metadata = mapOf(
                        "microAgentRole" to role.name,
                        "semanticSource" to "programmatic-code",
                        "modelBypassed" to "true",
                    ),
                )
            }
        }

        if (nodes.isEmpty()) return fallback.process(packet)
        return MemoryMutationBatch(nodesToAdd = nodes, edgesToAdd = edges)
    }
}

internal fun Collection<MemoryMicroAgent>.withProgrammaticSemanticFastPaths(): List<MemoryMicroAgent> = map { agent ->
    when (agent.role) {
        MemoryMicroAgentRole.NounTagger,
        MemoryMicroAgentRole.VerbTagger,
        -> if (agent is ProgrammaticSemanticTaggerMicroAgent) agent else ProgrammaticSemanticTaggerMicroAgent(agent)
        else -> agent
    }
}

private data class ProgrammaticTagInput(
    val item: MemoryWorkItem,
    val candidates: List<String>,
    val stronglyTechnical: Boolean,
)

private fun normalizeProgrammaticCandidate(value: String): String =
    value.trim().replace(Regex("\\s+"), " ").take(160)

private fun MemoryWorkItem.semanticSourceEpisodeIds(): List<MemoryEpisodeId> = metadata["sourceEpisodeIds"]
    .orEmpty()
    .split(',')
    .map(String::trim)
    .filter(String::isNotEmpty)
    .map(::MemoryEpisodeId)

private fun MemoryWorkItem.semanticSourceSectionIds(): List<MemorySectionId> = metadata["sourceSectionIds"]
    .orEmpty()
    .split(',')
    .map(String::trim)
    .filter(String::isNotEmpty)
    .map(::MemorySectionId)

private fun MemoryWorkItem.createdAtEpochMillisOrZero(): Long =
    metadata["createdAtEpochMillis"]?.toLongOrNull() ?: 0L

private fun String.programmaticTagIdPart(): String = buildString {
    this@programmaticTagIdPart.forEach { char ->
        when {
            char.isLetterOrDigit() -> append(char.lowercaseChar())
            char == '-' || char == '_' -> append(char)
            else -> append('-')
        }
    }
}.trim('-').take(48).ifBlank { "tag" }

private const val MAX_PROGRAMMATIC_TAGS_PER_ITEM = 64
private const val PROGRAMMATIC_TAG_CONFIDENCE = 0.98f
