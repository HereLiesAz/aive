package com.hereliesaz.geministrator.providers.llm

import com.hereliesaz.geministrator.memory.MemoryChunkId
import com.hereliesaz.geministrator.memory.MemoryConsolidationStage
import com.hereliesaz.geministrator.memory.MemoryEdge
import com.hereliesaz.geministrator.memory.MemoryEdgeId
import com.hereliesaz.geministrator.memory.MemoryEpisodeId
import com.hereliesaz.geministrator.memory.MemoryManagerAgent
import com.hereliesaz.geministrator.memory.MemoryMutationBatch
import com.hereliesaz.geministrator.memory.MemoryNode
import com.hereliesaz.geministrator.memory.MemoryNodeId
import com.hereliesaz.geministrator.memory.MemoryNodeKind
import com.hereliesaz.geministrator.memory.MemoryRelationKind
import com.hereliesaz.geministrator.memory.MemorySection
import com.hereliesaz.geministrator.memory.MemorySectionId
import com.hereliesaz.geministrator.memory.MemoryWorkItem
import com.hereliesaz.geministrator.memory.MemoryWorkPacket
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * LLM-backed curator for the memory graph. The model only sees one bounded [MemoryWorkPacket] and
 * returns a declarative proposal. It never receives persistence credentials or graph write access.
 */
class TextGenerationMemoryManagerAgent(
    private val api: TextGenerationApi,
    private val json: Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    },
    private val nowEpochMillis: () -> Long = ::memoryManagerNow,
) : MemoryManagerAgent {
    override suspend fun process(packet: MemoryWorkPacket): MemoryMutationBatch {
        val response = api.generate(packet.toManagerPrompt()).text.extractJsonObject()
        val proposal = json.decodeFromString(MemoryManagerProposal.serializer(), response)
        return proposal.toMutationBatch(packet, nowEpochMillis())
    }
}

@Serializable
private data class MemoryManagerProposal(
    val sections: List<SectionDraft> = emptyList(),
    val nodes: List<NodeDraft> = emptyList(),
    val links: List<LinkDraft> = emptyList(),
)

@Serializable
private data class SectionDraft(
    val text: String,
    val sourceIds: List<String>,
    val metadata: Map<String, String> = emptyMap(),
)

@Serializable
private data class NodeDraft(
    val key: String,
    val kind: String,
    val text: String,
    val sourceIds: List<String> = emptyList(),
    val salience: Float = 0.5f,
    val confidence: Float = 1f,
    val metadata: Map<String, String> = emptyMap(),
)

@Serializable
private data class LinkDraft(
    val from: String,
    val to: String,
    val relation: String,
    val weight: Float = 1f,
    val metadata: Map<String, String> = emptyMap(),
)

private fun MemoryManagerProposal.toMutationBatch(
    packet: MemoryWorkPacket,
    nowEpochMillis: Long,
): MemoryMutationBatch {
    require(sections.all { it.text.isNotBlank() && it.sourceIds.isNotEmpty() }) {
        "Memory section proposals require text and at least one source"
    }
    require(nodes.map(NodeDraft::key).all(String::isNotBlank)) { "Memory node proposal keys must not be blank" }
    require(nodes.map(NodeDraft::key).distinct().size == nodes.size) { "Memory node proposal keys must be unique" }

    val workItems = (packet.items + packet.neighborhood).associateBy(MemoryWorkItem::id)
    val packetIds = workItems.keys
    val sourceOrdinals = packet.items.associate { item ->
        item.id to (item.metadata["ordinal"]?.toIntOrNull() ?: 0)
    }

    val builtSections = sections.mapIndexed { index, draft ->
        require(draft.sourceIds.all { it in packetIds }) {
            "Memory section references content outside its work packet"
        }
        val baseOrdinal = draft.sourceIds.minOfOrNull { sourceOrdinals[it] ?: 0 } ?: 0
        MemorySection(
            id = MemorySectionId("${packet.queueId.value}:${packet.stage.name}:${packet.packetKey}:section:$index"),
            episodeId = packet.episodeId,
            sourceChunkIds = draft.sourceIds.mapTo(linkedSetOf(), ::MemoryChunkId),
            ordinal = baseOrdinal * 1000 + index,
            text = draft.text.trim(),
            metadata = draft.metadata,
        )
    }

    val nodeIdsByKey = nodes.mapIndexed { index, draft ->
        draft.key to MemoryNodeId(
            "${packet.queueId.value}:${packet.stage.name}:${packet.packetKey}:node:$index:${draft.key.safeIdPart()}",
        )
    }.toMap()

    val builtNodes = nodes.map { draft ->
        require(draft.sourceIds.isNotEmpty()) { "Memory node ${draft.key} requires sourceIds" }
        require(draft.sourceIds.all { it in packetIds }) {
            "Memory node ${draft.key} references content outside its work packet"
        }
        val kind = runCatching { MemoryNodeKind.valueOf(draft.kind) }
            .getOrElse { error("Unknown memory node kind ${draft.kind}") }

        val inheritedEpisodes = draft.sourceIds
            .flatMap { sourceId -> workItems[sourceId]?.sourceEpisodeIds().orEmpty() }
            .toMutableSet()
            .apply { add(packet.episodeId) }
        val inheritedSections = draft.sourceIds
            .flatMap { sourceId -> workItems[sourceId]?.sourceSectionIds().orEmpty() }
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
            metadata = draft.metadata,
        )
    }

    val knownNodeIds = workItems.values
        .filter { it.kind.startsWith("node:") }
        .mapTo(hashSetOf()) { it.id }
    val builtEdges = links.mapIndexed { index, link ->
        val from = nodeIdsByKey[link.from] ?: MemoryNodeId(link.from)
        val to = nodeIdsByKey[link.to] ?: MemoryNodeId(link.to)
        require(from.value in knownNodeIds || from in nodeIdsByKey.values) {
            "Memory link source ${link.from} is outside its work packet"
        }
        require(to.value in knownNodeIds || to in nodeIdsByKey.values) {
            "Memory link target ${link.to} is outside its work packet"
        }
        val relation = runCatching { MemoryRelationKind.valueOf(link.relation) }
            .getOrElse { error("Unknown memory relation ${link.relation}") }
        MemoryEdge(
            id = MemoryEdgeId("${packet.queueId.value}:${packet.stage.name}:${packet.packetKey}:edge:$index"),
            from = from,
            to = to,
            relation = relation,
            weight = link.weight.coerceIn(0f, 1f),
            createdAtEpochMillis = nowEpochMillis,
            metadata = link.metadata,
        )
    }

    return MemoryMutationBatch(
        sectionsToAdd = builtSections,
        nodesToAdd = builtNodes,
        edgesToAdd = builtEdges,
    )
}

private fun MemoryWorkPacket.toManagerPrompt(): String = buildString {
    appendLine("You are Haive's Memory Manager. Perform exactly one bounded memory-maintenance task.")
    appendLine("You are not solving the user's task. You may only curate the supplied memory packet.")
    appendLine("STAGE: ${stage.name}")
    appendLine("PACKET: $packetKey")
    appendLine("INSTRUCTION: $instruction")
    appendLine()
    appendLine("INPUT ITEMS:")
    items.forEach { item ->
        appendLine("- ID=${item.id} KIND=${item.kind}")
        appendLine(item.text)
    }
    if (neighborhood.isNotEmpty()) {
        appendLine()
        appendLine("BOUNDED EXISTING MEMORY NEIGHBORHOOD:")
        neighborhood.forEach { item ->
            appendLine("- ID=${item.id} KIND=${item.kind}")
            appendLine(item.text)
        }
    }
    appendLine()
    appendLine("Return one JSON object only. Do not use Markdown fences.")
    appendLine("Schema:")
    appendLine("{\"sections\":[{\"text\":\"...\",\"sourceIds\":[\"input-id\"],\"metadata\":{}}],")
    appendLine(" \"nodes\":[{\"key\":\"local-key\",\"kind\":\"Context|NounTag|VerbTag|Phrase|Summary|Category\",\"text\":\"...\",\"sourceIds\":[\"input-id\"],\"salience\":0.0,\"confidence\":1.0,\"metadata\":{}}],")
    appendLine(" \"links\":[{\"from\":\"local-key-or-node-id\",\"to\":\"local-key-or-node-id\",\"relation\":\"Indexes|Composes|Summarizes|Categorizes|SimilarTo|AssociatedWith|ConflictsWith|ResolvesConflict|Supersedes|CondensedFrom\",\"weight\":1.0,\"metadata\":{}}]}")
    appendLine("Use empty arrays for mutation types that are irrelevant to this stage.")
    appendLine("Node sourceIds must be IDs already present in this packet; local keys are only for links.")
    appendLine("Never reference an ID that is not in this packet unless it is a local node key you create in the same response.")
    if (stage == MemoryConsolidationStage.Condensation) {
        appendLine("Create exactly one node derived from ALL input node IDs.")
        appendLine("For every input node, emit both CondensedFrom and Supersedes links from the new node to that source.")
    }
    appendLine("Preserve disagreements with ConflictsWith; do not erase one memory merely because another conflicts with it.")
}

private fun MemoryWorkItem.sourceEpisodeIds(): List<MemoryEpisodeId> = metadata["sourceEpisodeIds"]
    .orEmpty()
    .split(',')
    .map(String::trim)
    .filter(String::isNotEmpty)
    .map(::MemoryEpisodeId)

private fun MemoryWorkItem.sourceSectionIds(): List<MemorySectionId> = metadata["sourceSectionIds"]
    .orEmpty()
    .split(',')
    .map(String::trim)
    .filter(String::isNotEmpty)
    .map(::MemorySectionId)

/** The outermost JSON object in a model reply, tolerating markdown fences and surrounding prose. */
internal fun String.extractJsonObject(source: String = "Memory manager"): String {
    val trimmed = trim()
        .removePrefix("```json")
        .removePrefix("```")
        .removeSuffix("```")
        .trim()
    val start = trimmed.indexOf('{')
    val end = trimmed.lastIndexOf('}')
    require(start >= 0 && end > start) { "$source did not return a JSON object" }
    return trimmed.substring(start, end + 1)
}

private fun String.safeIdPart(): String = buildString {
    this@safeIdPart.forEach { char ->
        when {
            char.isLetterOrDigit() -> append(char.lowercaseChar())
            char == '-' || char == '_' -> append(char)
            else -> append('-')
        }
    }
}.trim('-').take(48).ifBlank { "node" }

@OptIn(ExperimentalTime::class)
private fun memoryManagerNow(): Long = Clock.System.now().toEpochMilliseconds()
