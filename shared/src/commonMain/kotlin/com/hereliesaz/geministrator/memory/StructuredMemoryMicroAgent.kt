package com.hereliesaz.geministrator.memory

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/** Runtime-agnostic local memory worker shared by Android, desktop, and web. */
class StructuredMemoryMicroAgent(
    override val role: MemoryMicroAgentRole,
    override val model: MemoryMicroAgentModelSpec,
    private val runtime: MemoryMicroAgentInferenceRuntime,
    private val json: Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    },
    private val nowEpochMillis: () -> Long = ::microAgentNowEpochMillis,
) : MemoryMicroAgent {
    init {
        require(model.deployment.artifactsFor(runtime.platform).isNotEmpty()) {
            "Model ${model.modelId} has no ${runtime.platform} deployment artifact"
        }
    }

    override suspend fun process(packet: MemoryWorkPacket): MemoryMutationBatch {
        val artifact = selectArtifact()
        val prompt = packet.renderMicroAgentPrompt(role)
        require(prompt.length <= model.maxInputChars) {
            "Rendered ${role.name} prompt has ${prompt.length} chars; limit is ${model.maxInputChars}"
        }
        val result = runtime.infer(
            MemoryMicroAgentInferenceRequest(
                role = role,
                model = model,
                artifact = artifact,
                prompt = prompt,
            ),
        )
        require(result.text.length <= model.maxOutputChars) {
            "${role.name} output has ${result.text.length} chars; limit is ${model.maxOutputChars}"
        }
        val proposal = json.decodeFromString(
            MicroAgentProposal.serializer(),
            result.text.extractMicroAgentJson(),
        )
        return proposal.toMutationBatch(packet, role, nowEpochMillis())
    }

    private suspend fun selectArtifact(): MemoryMicroAgentArtifact {
        for (candidate in model.deployment.artifactsFor(runtime.platform)) {
            if (runtime.isAvailable(model, candidate)) return candidate
        }
        error("No available ${runtime.platform} artifact for ${model.modelId}/${role.name}")
    }
}

@Serializable
private data class MicroAgentProposal(
    val sections: List<MicroSectionDraft> = emptyList(),
    val nodes: List<MicroNodeDraft> = emptyList(),
    val links: List<MicroLinkDraft> = emptyList(),
)

@Serializable
private data class MicroSectionDraft(
    val text: String,
    val sourceIds: List<String>,
    val metadata: Map<String, String> = emptyMap(),
)

@Serializable
private data class MicroNodeDraft(
    val key: String,
    val kind: String,
    val text: String,
    val sourceIds: List<String>,
    val salience: Float = 0.5f,
    val confidence: Float = 1f,
    val metadata: Map<String, String> = emptyMap(),
)

@Serializable
private data class MicroLinkDraft(
    val from: String,
    val to: String,
    val relation: String,
    val weight: Float = 1f,
    val metadata: Map<String, String> = emptyMap(),
)

private fun MicroAgentProposal.toMutationBatch(
    packet: MemoryWorkPacket,
    role: MemoryMicroAgentRole,
    nowEpochMillis: Long,
): MemoryMutationBatch {
    require(sections.all { it.text.isNotBlank() && it.sourceIds.isNotEmpty() })
    require(nodes.all { it.key.isNotBlank() && it.text.isNotBlank() && it.sourceIds.isNotEmpty() })
    require(nodes.map(MicroNodeDraft::key).distinct().size == nodes.size)

    val workItems = (packet.items + packet.neighborhood).associateBy(MemoryWorkItem::id)
    val packetIds = workItems.keys
    val namespace = "${packet.queueId.value}:${packet.stage.name}:${packet.packetKey}:${role.name}"

    val builtSections = sections.mapIndexed { index, draft ->
        require(draft.sourceIds.all { it in packetIds })
        MemorySection(
            id = MemorySectionId("$namespace:section:$index"),
            episodeId = packet.episodeId,
            sourceChunkIds = draft.sourceIds.mapTo(linkedSetOf(), ::MemoryChunkId),
            ordinal = draft.sourceIds
                .mapNotNull { workItems[it]?.metadata?.get("ordinal")?.toIntOrNull() }
                .minOrNull()
                ?: index,
            text = draft.text.trim(),
            metadata = draft.metadata + ("microAgentRole" to role.name),
        )
    }

    val nodeIdsByKey = nodes.mapIndexed { index, draft ->
        draft.key to MemoryNodeId("$namespace:node:$index:${draft.key.microSafeIdPart()}")
    }.toMap()

    val builtNodes = nodes.map { draft ->
        require(draft.sourceIds.all { it in packetIds })
        val kind = runCatching { MemoryNodeKind.valueOf(draft.kind) }
            .getOrElse { error("Unknown memory node kind ${draft.kind}") }
        val inheritedEpisodes = draft.sourceIds
            .flatMap { workItems[it]?.microSourceEpisodeIds().orEmpty() }
            .toMutableSet()
            .apply { add(packet.episodeId) }
        val inheritedSections = draft.sourceIds
            .flatMap { workItems[it]?.microSourceSectionIds().orEmpty() }
            .toMutableSet()
        if (packet.stage == MemoryConsolidationStage.Salience) {
            draft.sourceIds.mapTo(inheritedSections, ::MemorySectionId)
        }
        MemoryNode(
            id = requireNotNull(nodeIdsByKey[draft.key]),
            kind = kind,
            text = draft.text.trim(),
            sourceEpisodeIds = inheritedEpisodes,
            sourceSectionIds = inheritedSections,
            salience = draft.salience.coerceIn(0f, 1f),
            confidence = draft.confidence.coerceIn(0f, 1f),
            createdAtEpochMillis = nowEpochMillis,
            metadata = draft.metadata + ("microAgentRole" to role.name),
        )
    }

    val knownNodeIds = workItems.values
        .filter { it.kind.startsWith("node:") }
        .mapTo(hashSetOf()) { it.id }
    val builtEdges = links.mapIndexed { index, link ->
        val from = nodeIdsByKey[link.from] ?: MemoryNodeId(link.from)
        val to = nodeIdsByKey[link.to] ?: MemoryNodeId(link.to)
        require(from.value in knownNodeIds || from in nodeIdsByKey.values)
        require(to.value in knownNodeIds || to in nodeIdsByKey.values)
        val relation = runCatching { MemoryRelationKind.valueOf(link.relation) }
            .getOrElse { error("Unknown memory relation ${link.relation}") }
        MemoryEdge(
            id = MemoryEdgeId("$namespace:edge:$index"),
            from = from,
            to = to,
            relation = relation,
            weight = link.weight.coerceIn(0f, 1f),
            createdAtEpochMillis = nowEpochMillis,
            metadata = link.metadata + ("microAgentRole" to role.name),
        )
    }

    return MemoryMutationBatch(
        sectionsToAdd = builtSections,
        nodesToAdd = builtNodes,
        edgesToAdd = builtEdges,
    )
}

private fun MemoryWorkPacket.renderMicroAgentPrompt(role: MemoryMicroAgentRole): String = buildString {
    appendLine("You are a local Haive memory micro-agent.")
    appendLine("ROLE: ${role.name}")
    appendLine("STAGE: ${stage.name}")
    appendLine("PACKET: $packetKey")
    appendLine(instruction)
    appendLine()
    appendLine("INPUT ITEMS")
    items.forEach(::appendMicroWorkItem)
    if (neighborhood.isNotEmpty()) {
        appendLine()
        appendLine("BOUNDED MEMORY NEIGHBORHOOD")
        neighborhood.forEach(::appendMicroWorkItem)
    }
    appendLine()
    appendLine("Return exactly one JSON object and nothing else.")
    appendLine("{\"sections\":[{\"text\":\"...\",\"sourceIds\":[\"id\"],\"metadata\":{}}],")
    appendLine(" \"nodes\":[{\"key\":\"local-key\",\"kind\":\"Context|NounTag|VerbTag|Phrase|Summary|Category\",\"text\":\"...\",\"sourceIds\":[\"id\"],\"salience\":0.5,\"confidence\":1.0,\"metadata\":{}}],")
    appendLine(" \"links\":[{\"from\":\"local-key-or-visible-node-id\",\"to\":\"local-key-or-visible-node-id\",\"relation\":\"Indexes|Composes|Summarizes|Categorizes|SimilarTo|AssociatedWith|Supersedes|CondensedFrom\",\"weight\":1.0,\"metadata\":{}}]}")
    appendLine("Use only IDs visible in this packet as sourceIds or link endpoints, except local keys created in this same response.")
    appendLine("Never infer contradiction, truth, falsity, or conflict resolution. Memory micro-agents only organize and associate supplied material.")
}

private fun StringBuilder.appendMicroWorkItem(item: MemoryWorkItem) {
    append("- ID=").append(item.id).append(" KIND=").append(item.kind).appendLine()
    if (item.metadata.isNotEmpty()) {
        append("  META=")
        append(item.metadata.entries.joinToString(" | ") { (key, value) -> "$key=$value" })
        appendLine()
    }
    appendLine(item.text)
}

private fun MemoryWorkItem.microSourceEpisodeIds(): List<MemoryEpisodeId> = metadata["sourceEpisodeIds"]
    .orEmpty().split(',').map(String::trim).filter(String::isNotEmpty).map(::MemoryEpisodeId)

private fun MemoryWorkItem.microSourceSectionIds(): List<MemorySectionId> = metadata["sourceSectionIds"]
    .orEmpty().split(',').map(String::trim).filter(String::isNotEmpty).map(::MemorySectionId)

private fun String.extractMicroAgentJson(): String {
    val cleaned = trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
    val start = cleaned.indexOf('{')
    val end = cleaned.lastIndexOf('}')
    require(start >= 0 && end > start) { "Memory micro-agent did not return JSON" }
    return cleaned.substring(start, end + 1)
}

private fun String.microSafeIdPart(): String = buildString {
    this@microSafeIdPart.forEach { char ->
        when {
            char.isLetterOrDigit() -> append(char.lowercaseChar())
            char == '-' || char == '_' -> append(char)
            else -> append('-')
        }
    }
}.trim('-').take(48).ifBlank { "node" }

@OptIn(ExperimentalTime::class)
private fun microAgentNowEpochMillis(): Long = Clock.System.now().toEpochMilliseconds()
